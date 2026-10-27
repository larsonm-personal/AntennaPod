import argparse
import getpass
import hashlib
import http.client
import json
import math
import os
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request

from downloadEpisode import Redirects, Requests, episode_url_from_log
from watchEpisodeRoute import now


class StopCapture(Exception):
    pass


class BudgetedRequests(Requests):
    def __init__(self, redirects, state, write, maximum):
        super().__init__(redirects)
        self.state, self.write, self.maximum = state, write, maximum

    def http_request(self, request):
        if self.state["requests"] >= self.maximum:
            raise StopCapture("request budget exhausted")
        self.state["requests"] += 1
        super().http_request(request)
        self.write({"event": "send", "number": self.state["requests"], **self.redirects.requests[-1]})
        return request

    https_request = http_request


class SampleRedirects(Redirects):
    def redirect_request(self, request, response, code, message, headers, url):
        if urllib.parse.urlsplit(url).scheme not in ("http", "https"):
            raise urllib.error.HTTPError(request.full_url, code, "Unsupported redirect", headers, response)
        response.close()
        return super().redirect_request(request, response, code, message, headers, url)


def correlated_keys(path, original):
    if path is None:
        return []
    by_length = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        event = json.loads(line)
        if event.get("event") != "check" or event.get("status") != 200:
            continue
        if event.get("original_url") != original:
            raise ValueError("Observations belong to a different original URL")
        length = tuple(event.get("selected_headers", {}).get("Content-Length", []))
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(event["final_url"]).query, keep_blank_values=True)
        by_length.setdefault(length, []).append({key: tuple(value) for key, value in query.items()})
    if len(by_length) < 2 or any(len(queries) < 2 for queries in by_length.values()):
        return []
    all_queries = [query for queries in by_length.values() for query in queries]
    candidates = set.intersection(*(set(query) for query in all_queries))
    return sorted(key for key in candidates if all(
        len({query[key] for query in queries}) == 1 for queries in by_length.values())
        and len({queries[0][key] for queries in by_length.values()}) == len(by_length))


def signature(record, keys):
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(record["final_url"]).query, keep_blank_values=True)
    return record["length"], {key: query.get(key) for key in keys}


def compare(before, after):
    intervals = sorted({tuple(record["interval"]) for record in before + after})
    result = []
    for interval in intervals:
        left = next((record for record in before if tuple(record["interval"]) == interval), None)
        right = next((record for record in after if tuple(record["interval"]) == interval), None)
        result.append({"interval": list(interval), "before": left["number"] if left else None,
                       "after": right["number"] if right else None,
                       "equal": left["sha256"] == right["sha256"]
                       if left and right and left["comparable"] and right["comparable"] else None})
    return result


def characterize(url, folder, args):
    folder.mkdir(mode=0o700)
    state = {"requests": 0, "payload_bytes": 0}
    records = []
    keys = correlated_keys(args.observations, url)
    with (folder / "events.jsonl").open("x", encoding="utf-8") as journal:
        def write(event):
            journal.write(json.dumps(event) + "\n")
            journal.flush()
            os.fsync(journal.fileno())

        def request(target, label, interval=None):
            if interval and state["payload_bytes"] + interval[1] - interval[0] + 2 > args.payload_bytes:
                raise StopCapture("payload budget exhausted before request")
            redirects = SampleRedirects(private=True)
            redirects.max_redirections, redirects.max_repeats = 5, 1
            opener = urllib.request.build_opener(redirects, BudgetedRequests(redirects, state, write, args.max_requests))
            headers = {"User-Agent": "AntennaPod/3.13.0-beta1", "Accept-Encoding": "identity" if interval else "gzip"}
            if interval:
                headers.update(Range="bytes=%d-%d" % tuple(interval), **{"Icy-MetaData": "1"})
            record = {"event": "response", "number": len(records) + 1, "label": label,
                      "requested_url": target, "method": "GET" if interval else "HEAD", "interval": interval,
                      "started_utc": now(), "comparable": False, "diagnostic_sampling": bool(interval)}
            records.append(record)
            started = time.monotonic()
            body = b""
            try:
                try:
                    response = opener.open(urllib.request.Request(target, headers=headers, method=record["method"]),
                                           timeout=args.timeout)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    record.update(status=response.status, final_url=response.url, headers=list(response.headers.items()),
                                  length=response.headers.get("Content-Length"),
                                  content_range=response.headers.get("Content-Range"))
                    if response.status in (401, 403, 429):
                        raise StopCapture("access denial or rate limit; no substitution or retry")
                    if interval and response.status in (200, 206):
                        cap = interval[1] - interval[0] + 2
                        record["file"] = "sample-%03d.bin" % record["number"]
                        try:
                            with (folder / record["file"]).open("xb") as output:
                                while len(body) < cap:
                                    if time.monotonic() - started >= args.timeout:
                                        raise TimeoutError("Sample read time budget exhausted")
                                    chunk = response.read1(min(4096, cap - len(body)))
                                    if not chunk:
                                        break
                                    body += chunk
                                    state["payload_bytes"] += len(chunk)
                                    output.write(chunk)
                        finally:
                            record.update(bytes_saved=len(body), sha256=hashlib.sha256(body).hexdigest())
                        match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", record["content_range"] or "")
                        if response.status == 206 and match:
                            first, last, total = map(int, match.groups())
                            record["comparable"] = (first == interval[0] and last == interval[1] and total > last
                                and len(body) == last - first + 1 and "error" not in record
                                and len(response.headers.get_all("Content-Range", [])) == 1
                                and len(response.headers.get_all("Content-Length", [])) <= 1
                                and response.headers.get("Content-Encoding") in (None, "identity")
                                and record["length"] in (None, str(len(body))))
                            record["range_total"] = total
                        record["body_capped"] = len(body) == cap
                        if response.status == 206 and not record["comparable"]:
                            record["meaning"] = "incoherent, encoded, oversized or truncated range; excluded from comparison"
                        if response.status == 200:
                            record["meaning"] = "Range ignored; bounded prefix only, not the requested interval"
            except (OSError, urllib.error.URLError, http.client.HTTPException) as error:
                record["error"] = type(error).__name__
            finally:
                record.update(finished_utc=now(), elapsed_seconds=time.monotonic() - started,
                              redirects=redirects.observations, outgoing_hops=redirects.requests)
                write(record)
            print(label, "status", record.get("status", "transport error"), flush=True)
            return record

        write({"event": "start", "time_utc": now(), "original_url": url, "correlated_query_keys": keys,
               "max_requests_including_redirects": args.max_requests, "max_payload_bytes": args.payload_bytes,
               "hours": args.hours, "interval_seconds": args.interval, "window_bytes": args.window_bytes})
        try:
            baseline = request(url, "baseline_resolution")
            if baseline.get("status") != 200 or baseline.get("error") or not (baseline.get("length") or "").isdigit():
                raise StopCapture("baseline HEAD lacks successful known length")
            total = int(baseline["length"])
            if total <= 0:
                raise StopCapture("empty baseline")
            intervals = [[offset, min(total - 1, offset + args.window_bytes - 1)]
                         for offset in sorted({0, total // 2, total * 3 // 4})]
            pinned = baseline["final_url"]
            before = [request(pinned, "baseline_sample", interval) for interval in intervals]
            fresh, reason = baseline, "bounded monitoring window ended without observed signature change"
            deadline = time.monotonic() + args.hours * 3600
            for unused in range(max(0, math.ceil(args.hours * 3600 / args.interval) - 1)):
                time.sleep(min(args.interval, max(0, deadline - time.monotonic())))
                if time.monotonic() >= deadline:
                    break
                candidate = request(url, "fresh_resolution")
                if candidate.get("status") == 200 and not candidate.get("error"):
                    fresh = candidate
                    if signature(fresh, keys) != signature(baseline, keys):
                        reason = "fresh HEAD length or empirically correlated key pattern changed"
                        break
            write({"event": "comparison_trigger", "reason": reason, "fresh_resolution_number": fresh["number"]})
            pinned_head = request(pinned, "pinned_recheck")
            after = [request(pinned, "pinned_sample", interval) for interval in intervals] \
                if pinned_head.get("status") == 200 and not pinned_head.get("error") else []
            fresh_samples = [request(fresh["final_url"], "fresh_sample", interval) for interval in intervals]
            comparisons = {"baseline_vs_pinned": compare(before, after),
                           "baseline_vs_fresh": compare(before, fresh_samples),
                           "pinned_vs_fresh": compare(after, fresh_samples)}
            write({"event": "comparisons", **comparisons,
                   "meaning": "same absolute byte intervals only; sparse equality does not prove whole audio identity"})
            for name, pairs in comparisons.items():
                print(name, "equal", sum(pair["equal"] is True for pair in pairs),
                      "different", sum(pair["equal"] is False for pair in pairs),
                      "unknown", sum(pair["equal"] is None for pair in pairs), flush=True)
            write({"event": "finished", "time_utc": now(), **state})
            print("Finished. Compare private interval hashes; this is not an audible-skip verdict.")
            return 0
        except (StopCapture, KeyboardInterrupt) as error:
            write({"event": "stopped", "time_utc": now(), "reason": str(error) if isinstance(error, StopCapture)
                   else "interrupted", **state})
            print("Stopped; private evidence retained. No alternate URL or identity attempted.")
            return 1


def main():
    parser = argparse.ArgumentParser(description="Bounded local server characterization, not a playback reproduction")
    parser.add_argument("url", nargs="?")
    parser.add_argument("--trace-log", type=pathlib.Path)
    parser.add_argument("--prompt-url", action="store_true")
    parser.add_argument("--observations", type=pathlib.Path, help="Optional private overnight events.jsonl")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--interval", type=float, default=900)
    parser.add_argument("--hours", type=float, default=8)
    parser.add_argument("--window-bytes", type=int, default=65536)
    parser.add_argument("--max-requests", type=int, default=96)
    parser.add_argument("--payload-bytes", type=int, default=1048576)
    parser.add_argument("--timeout", type=float, default=20)
    args = parser.parse_args()
    if sum(bool(value) for value in (args.url, args.trace_log, args.prompt_url)) != 1:
        parser.error("Choose one URL input method.")
    if not (60 <= args.interval <= 3600 and 0 < args.hours <= 24 and 1 <= args.window_bytes <= 262144
            and 1 <= args.max_requests <= 256 and 1 <= args.payload_bytes <= 8 * 1048576 and 1 <= args.timeout <= 60):
        parser.error("Invalid bounds; see characterization documentation.")
    try:
        url = episode_url_from_log(args.trace_log) if args.trace_log else args.url
        if args.prompt_url:
            url = getpass.getpass("Episode AUDIO URL (hidden): ").strip()
        parts = urllib.parse.urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
            parser.error("Use an HTTP(S) episode URL without embedded credentials.")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        return characterize(url, args.output, args)
    except (OSError, ValueError) as error:
        print("Characterization stopped:", type(error).__name__, "(check local inputs/output).")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

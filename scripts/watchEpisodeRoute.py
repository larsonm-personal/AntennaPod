import argparse
import datetime
import getpass
import http.client
import json
import math
import os
import pathlib
import time
import urllib.error
import urllib.parse
import urllib.request

from downloadEpisode import Redirects, Requests, episode_url_from_log


HEADERS = ("Content-Length", "ETag", "Last-Modified", "Location")


class HeadRedirects(Redirects):
    def redirect_request(self, request, response, code, message, headers, url):
        if request.get_method() != "HEAD" or urllib.parse.urlsplit(url).scheme not in ("http", "https"):
            raise urllib.error.HTTPError(request.full_url, code, "Unsupported HEAD redirect", headers, response)
        return super().redirect_request(request, response, code, message, headers, url)


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def check(url):
    redirects = HeadRedirects(private=True)
    redirects.max_redirections = 5
    redirects.max_repeats = 1
    opener = urllib.request.build_opener(redirects, Requests(redirects))
    started = time.monotonic()
    result = {"event": "check", "started_utc": now(), "original_url": url}
    try:
        request = urllib.request.Request(url, method="HEAD", headers={
            "User-Agent": "AntennaPod/3.13.0-beta1", "Accept-Encoding": "gzip"})
        try:
            response = opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            result["error"] = "HTTPError"
            result["terminal"] = True
            response = error
        with response:
            result.update(status=response.status, final_url=response.url,
                          headers=list(response.headers.items()),
                          selected_headers={name: response.headers.get_all(name, []) for name in HEADERS})
    except (OSError, urllib.error.URLError, http.client.HTTPException, ValueError) as error:
        result["error"] = type(error).__name__
    finally:
        result.update(finished_utc=now(), elapsed_seconds=time.monotonic() - started,
                      redirects=redirects.observations, outgoing_hops=redirects.requests,
                      body_bytes_read=0)
    return result


def changes(previous, current):
    if previous is None:
        return ["baseline"]
    fields = [key for key in ("status", "error", "final_url") if previous.get(key) != current.get(key)]
    old_chain = [(hop["status"], hop["from_route"], hop["to_route"]) for hop in previous["redirects"]]
    new_chain = [(hop["status"], hop["from_route"], hop["to_route"]) for hop in current["redirects"]]
    if old_chain != new_chain:
        fields.append("redirect_chain")
    for name in HEADERS:
        if previous.get("selected_headers", {}).get(name) != current.get("selected_headers", {}).get(name):
            fields.append(name)
    return fields


def watch(url, folder, interval, hours):
    folder.mkdir(mode=0o700)
    count = math.ceil(hours * 3600 / interval)
    deadline = time.monotonic() + hours * 3600
    previous, failures = None, 0
    with (folder / "events.jsonl").open("x", encoding="utf-8") as journal:
        def write(event):
            journal.write(json.dumps(event) + "\n")
            journal.flush()
            os.fsync(journal.fileno())

        write({"event": "start", "time_utc": now(), "original_url": url,
               "interval_seconds": interval, "hours": hours, "max_checks": count,
               "mode": "HEAD only; no audio body; fresh resolution of original URL each check"})
        try:
            for index in range(count):
                if time.monotonic() >= deadline:
                    break
                result = check(url)
                result["changed_fields"] = changes(previous, result)
                result["number"] = index + 1
                write(result)
                print(result["finished_utc"], "check", index + 1, "status", result.get("status", "network error"),
                      "changed:", ", ".join(result["changed_fields"]) or "none", flush=True)
                previous = result
                failures = failures + 1 if result.get("error") else 0
                if result.get("terminal") or failures >= 3:
                    write({"event": "stopped", "time_utc": now(),
                           "reason": "HTTP error (including rate limit) or three consecutive transport errors"})
                    print("Stopped safely; inspect the private journal. No fallback or alternate identity attempted.")
                    return 1
                if index + 1 < count:
                    delay = min(interval, max(0, deadline - time.monotonic()))
                    time.sleep(delay)
            write({"event": "finished", "time_utc": now()})
            return 0
        except KeyboardInterrupt:
            write({"event": "interrupted", "time_utc": now()})
            print("Stopped; completed checks remain in the private journal.")
            return 1


def main():
    parser = argparse.ArgumentParser(description="Local overnight HEAD-only route watch; no audio downloads")
    parser.add_argument("url", nargs="?", help="Episode audio URL (may remain in shell history)")
    parser.add_argument("--trace-log", type=pathlib.Path)
    parser.add_argument("--prompt-url", action="store_true")
    parser.add_argument("--interval", type=float, default=900, help="Seconds between checks, 60–3600; default 900")
    parser.add_argument("--hours", type=float, default=8, help="Maximum duration, greater than 0 through 24")
    parser.add_argument("--output", required=True, type=pathlib.Path, help="New private capture directory")
    args = parser.parse_args()
    if sum(bool(value) for value in (args.url, args.trace_log, args.prompt_url)) != 1:
        parser.error("Choose exactly one of URL, --trace-log or --prompt-url.")
    if not 60 <= args.interval <= 3600 or not 0 < args.hours <= 24:
        parser.error("Interval must be 60–3600 seconds and duration greater than 0 through 24 hours.")
    try:
        url = episode_url_from_log(args.trace_log) if args.trace_log else args.url
        if args.prompt_url:
            url = getpass.getpass("Episode AUDIO URL (hidden): ").strip()
        parts = urllib.parse.urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
            parser.error("Use an HTTP(S) audio URL without embedded credentials.")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        print("HEAD-only monitor. Private URLs stay in events.jsonl; no audio or GET fallback.")
        return watch(url, args.output, args.interval, args.hours)
    except (OSError, ValueError) as error:
        print("Monitor stopped:", type(error).__name__, "(check input or choose a new capture directory).")
        return 1
    except KeyboardInterrupt:
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

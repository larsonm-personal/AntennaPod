import argparse
import datetime
import email.utils
import hashlib
import http.client
import json
import pathlib
import re
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


FEED_URL = "https://audioboom.com/channels/5173020.rss"
EPISODE_TITLE = "What the hell is going on in Europe?"
EPISODE_DATE = datetime.date(2026, 10, 1)
HEADERS = ("Content-Type", "Content-Length", "Accept-Ranges", "Content-Range",
           "ETag", "Last-Modified", "Content-Encoding")


def origin(url):
    parts = urllib.parse.urlsplit(url)
    return parts.scheme + "://" + (parts.hostname or "")


def route(url):
    parts = urllib.parse.urlsplit(url)
    category = "download" if "/download/" in parts.path else "other"
    return {"origin": origin(url), "path_kind": category,
            "path_sha256": hashlib.sha256(parts.path.encode()).hexdigest(),
            "url_sha256": hashlib.sha256(url.encode()).hexdigest()}


def enclosure(opener):
    with opener.open(FEED_URL, timeout=60) as response:
        if response.status != 200:
            raise ValueError("Expected a complete HTTP 200 RSS response.")
        xml = response.read(10 * 1024 * 1024 + 1)
    if len(xml) > 10 * 1024 * 1024:
        raise ValueError("RSS exceeds the 10 MiB limit.")
    matches = []
    for item in ET.fromstring(xml).findall("./channel/item"):
        if item.findtext("title", "").strip() != EPISODE_TITLE:
            continue
        published = item.findtext("pubDate", "")
        if email.utils.parsedate_to_datetime(published).date() == EPISODE_DATE:
            matches.append((item, published))
    if len(matches) != 1:
        raise ValueError("Expected exactly one RSS episode matching the title and date; found " + str(len(matches)))
    item, published = matches[0]
    audio = [entry for entry in item.findall("enclosure")
             if entry.get("type", "").startswith("audio/") and entry.get("url")]
    if len(audio) != 1:
        raise ValueError("The selected RSS episode must have exactly one audio enclosure.")
    return urllib.parse.urljoin(FEED_URL, audio[0].get("url")), published


class Redirects(urllib.request.HTTPRedirectHandler):
    def __init__(self):
        self.observations = []

    def redirect_request(self, request, response, code, message, headers, url):
        self.observations.append({"status": code, "from_route": route(request.full_url),
                                  "to_route": route(url)})
        redirected = super().redirect_request(request, response, code, message, headers, url)
        if redirected is not None and request.get_method() == "HEAD":
            redirected.method = "HEAD"
        return redirected


def staged(opener, redirects, url, published, output, args):
    folder = output.with_suffix(output.suffix + ".capture")
    folder.mkdir(mode=0o700)
    manifest = {"experiment": "pinned_url_load_error_retry", "source_kind":
                "direct_url" if args.url else "rss_enclosure", "source_route": route(url),
                "episode_title": None if args.url else EPISODE_TITLE, "episode_date": published,
                "delay_seconds": args.delay, "prefix_bytes": args.prefix_bytes,
                "feed_redirects": list(redirects.observations), "requests": [], "outcome": "incomplete"}

    def request(target, method="GET", byte_range=None):
        redirects.observations.clear()
        headers = {"Accept-Encoding": "identity", "User-Agent":
                   next(value for name, value in opener.addheaders if name.lower() == "user-agent")}
        if method == "GET":
            headers["Icy-MetaData"] = "1"
        if byte_range:
            headers["Range"] = "bytes=%d-%d" % byte_range
        record = {"method": method, "requested_route": route(target), "request_headers": headers,
                  "time_utc": datetime.datetime.now(datetime.timezone.utc).isoformat()}
        manifest["requests"].append(record)
        try:
            response = opener.open(urllib.request.Request(target, headers=headers, method=method), timeout=60)
        except urllib.error.HTTPError as error:
            record["status"] = error.code
            record["headers"] = {name: error.headers.get(name) for name in HEADERS}
            record["final_route"] = route(error.url)
            if method != "HEAD" or not 400 <= error.code <= 599:
                raise
            response = error
        finally:
            record["redirects"] = list(redirects.observations)
        record.update(status=response.status, final_route=route(response.url),
                      headers={name: response.headers.get(name) for name in HEADERS})
        return response, record

    def save(response, record, name, prefix=None, cap=256 * 1024 * 1024):
        digest = hashlib.sha256()
        record.update(file=name, bytes_saved=0)
        try:
            with (folder / name).open("xb") as stream:
                while prefix is None or record["bytes_saved"] < prefix:
                    count = 65536 if prefix is None else min(65536, prefix - record["bytes_saved"])
                    count = min(count, cap + 1 - record["bytes_saved"])
                    block = response.read(count)
                    if not block:
                        break
                    stream.write(block)
                    digest.update(block)
                    record["bytes_saved"] += len(block)
                    if record["bytes_saved"] > cap:
                        raise ValueError("Response exceeds its capture byte limit; evidence preserved.")
        finally:
            record["sha256"] = digest.hexdigest()
        if prefix is not None:
            if record["bytes_saved"] != prefix:
                raise ValueError("First response ended before the requested prefix.")
        elif record["headers"]["Content-Length"] is not None:
            if record["bytes_saved"] != int(record["headers"]["Content-Length"]):
                raise ValueError("Incomplete response; raw bytes and metadata preserved.")

    def validate_range(record, start, end):
        match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", record["headers"]["Content-Range"] or "")
        if record["status"] != 206 or not match:
            raise ValueError("Expected HTTP 206 with a numeric Content-Range; no reconstruction.")
        first, last, total = map(int, match.groups())
        if first != start or last != min(end, total - 1) or last < first:
            raise ValueError("Content-Range does not match the requested interval; no reconstruction.")
        if record["bytes_saved"] != last - first + 1:
            raise ValueError("Range body length mismatch; no reconstruction.")
        if record["headers"]["Content-Encoding"] not in (None, "identity"):
            raise ValueError("Encoded range bodies are unsupported; no reconstruction.")
        return total

    try:
        with request(url, "HEAD")[0] as response:
            pinned = response.url
        response, first = request(pinned)
        with response:
            if response.status != 200 or not response.headers.get("Content-Length"):
                raise ValueError("Initial GET must return 200 and a known length; staged capture unsupported.")
            total = int(response.headers["Content-Length"])
            if not args.prefix_bytes < total <= 256 * 1024 * 1024:
                raise ValueError("Initial length must exceed the prefix and be at most 256 MiB.")
            if response.headers.get("Content-Encoding") not in (None, "identity"):
                raise ValueError("Encoded initial bodies are unsupported.")
            save(response, first, "prefix.bin", args.prefix_bytes)
        print("Prefix saved. Waiting", args.delay, "seconds before the pinned-URL range request.", flush=True)
        time.sleep(args.delay)
        response, second = request(pinned, byte_range=(args.prefix_bytes, total - 1))
        with response:
            name = "restart-response.mp3" if response.status == 200 else "suffix.bin"
            save(response, second, name)
        if second["status"] == 200:
            manifest["outcome"] = "server_ignored_range_standalone_response_no_app_equivalence_inference"
            print("Server ignored Range. Standalone response preserved; nothing appended.")
            return 0
        new_total = validate_range(second, args.prefix_bytes, total - 1)
        manifest["changed_headers"] = [name for name in ("ETag", "Last-Modified")
                                       if first["headers"][name] != second["headers"][name]]
        manifest.update(initial_total=total, retry_total=new_total)
        with (folder / "reconstructed.mp3").open("xb") as combined:
            for name in ("prefix.bin", "suffix.bin"):
                with (folder / name).open("rb") as part:
                    shutil.copyfileobj(part, combined)
        manifest["reconstruction"] = {"file": "reconstructed.mp3", "parts": ["prefix.bin", "suffix.bin"],
                                      "join_byte": args.prefix_bytes,
                                      "meaning": "concatenated validated responses, not proof of an audible skip"}
        if args.overlap:
            start, end = max(0, args.prefix_bytes - 65536), min(total - 1, args.prefix_bytes + 65535)
            response, diagnostic = request(pinned, byte_range=(start, end))
            diagnostic["diagnostic_only"] = True
            with response:
                save(response, diagnostic, "overlap.bin", cap=end - start + 1)
            validate_range(diagnostic, start, end)
            overlap = (folder / "overlap.bin").read_bytes()
            with (folder / "prefix.bin").open("rb") as prefix:
                prefix.seek(start)
                before = prefix.read(args.prefix_bytes - start)
            with (folder / "suffix.bin").open("rb") as suffix:
                after = suffix.read(len(overlap) - len(before))
            manifest["overlap"] = {"matches_prefix": overlap[:len(before)] == before,
                                   "matches_suffix": overlap[len(before):] == after,
                                   "meaning": "later diagnostic request; not part of production retry"}
        manifest["outcome"] = "captured_for_analysis_not_a_skip_verdict"
        return 0
    except BaseException as error:
        manifest["error"] = {"kind": type(error).__name__, "http_status": getattr(error, "code", None)}
        raise
    finally:
        (folder / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        print("Private staged evidence:", folder)


def main():
    parser = argparse.ArgumentParser(description="Download one public MP3 and local response metadata.")
    parser.add_argument("url", nargs="?", help="Optional direct public audio URL; bypasses RSS selection")
    parser.add_argument("-o", "--output", type=pathlib.Path, help="Output path (default: your Downloads folder)")
    parser.add_argument("--staged", action="store_true", help="Interrupt a prefix, wait, then retry the pinned URL")
    parser.add_argument("--delay", type=float, default=60, help="Staged pause in seconds (0–86400; default 60)")
    parser.add_argument("--prefix-bytes", type=int, default=1048576)
    parser.add_argument("--overlap", action="store_true", help="One extra diagnostic range of at most 128 KiB")
    args = parser.parse_args()
    if not 0 <= args.delay <= 86400 or not 1 <= args.prefix_bytes <= 64 * 1024 * 1024:
        parser.error("Delay must be 0–86400 seconds and prefix 1–67108864 bytes.")
    parts = urllib.parse.urlsplit(args.url or FEED_URL)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        parser.error("Use a public HTTP(S) URL without embedded credentials.")
    filename = "antennapod-europe-staged.mp3" if args.staged else "antennapod-europe-rss.mp3"
    output = (args.output or pathlib.Path.home() / "Downloads" / filename).expanduser().resolve()
    metadata = output.with_suffix(output.suffix + ".json")
    if output.exists() or metadata.exists():
        parser.error("Output or metadata file already exists; choose another --output path.")
    if args.staged and output.with_suffix(output.suffix + ".capture").exists():
        parser.error("Capture directory already exists; choose another --output path.")
    created = []
    redirects = Redirects()
    try:
        opener = urllib.request.build_opener(redirects)
        url, published = (args.url, None) if args.url else enclosure(opener)
        parts = urllib.parse.urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
            raise ValueError("The enclosure is not a public HTTP(S) URL without embedded credentials.")
        feed_redirects = list(redirects.observations)
        output.parent.mkdir(parents=True, exist_ok=True)
        if args.staged:
            return staged(opener, redirects, url, published, output, args)
        redirects.observations.clear()
        with metadata.open("x", encoding="utf-8") as details:
            created.append(metadata)
            with output.open("xb") as audio:
                created.append(output)
                with opener.open(url, timeout=60) as response:
                    if response.status != 200:
                        raise ValueError("Expected a complete HTTP 200 response.")
                    if response.headers.get_content_type() in ("text/html", "application/xhtml+xml"):
                        raise ValueError("Received an HTML page rather than audio; download stopped.")
                    saved = 0
                    while block := response.read(1024 * 1024):
                        audio.write(block)
                        saved += len(block)
                    length = response.headers.get("Content-Length")
                    if saved == 0 or (length is not None and saved != int(length)):
                        raise ValueError("Empty or incomplete download; partial files removed.")
                    json.dump({"source_kind": "direct_url" if args.url else "rss_enclosure",
                               "episode_title": None if args.url else EPISODE_TITLE,
                               "episode_date": published, "source_route": route(url),
                               "feed_redirects": feed_redirects,
                               "status": response.status, "final_route": route(response.url),
                               "redirects": redirects.observations, "bytes_saved": saved,
                               "headers": {name: response.headers.get(name) for name in
                                           ("Content-Type", "Content-Length", "Accept-Ranges",
                                            "Content-Range", "ETag", "Last-Modified", "Content-Encoding")}},
                              details, indent=2)
        print("Saved:", output)
        print("Metadata:", metadata)
        return 0
    except urllib.error.HTTPError as error:
        print("Download stopped: HTTP", error.code, "(no retry or alternate identity attempted).")
    except urllib.error.URLError:
        print("Download stopped: network or TLS error (no retry attempted).")
    except (ValueError, ET.ParseError) as error:
        print("Download stopped:", error)
    except (OSError, http.client.HTTPException) as error:
        print("Download stopped:", type(error).__name__, "(check connection and output permissions).")
    except KeyboardInterrupt:
        print("Capture interrupted; any staged evidence was preserved.")
    for path in created:
        path.unlink(missing_ok=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

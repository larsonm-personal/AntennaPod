import argparse
import datetime
import email.utils
import hashlib
import http.client
import json
import pathlib
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


FEED_URL = "https://audioboom.com/channels/5173020.rss"
EPISODE_TITLE = "What the hell is going on in Europe?"
EPISODE_DATE = datetime.date(2026, 10, 1)


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
        return super().redirect_request(request, response, code, message, headers, url)


def main():
    parser = argparse.ArgumentParser(description="Download one public MP3 and local response metadata.")
    parser.add_argument("url", nargs="?", help="Optional direct public audio URL; bypasses RSS selection")
    parser.add_argument("-o", "--output", type=pathlib.Path,
                        default=pathlib.Path.home() / "Downloads" / "antennapod-europe-rss.mp3")
    args = parser.parse_args()
    parts = urllib.parse.urlsplit(args.url or FEED_URL)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        parser.error("Use a public HTTP(S) URL without embedded credentials.")
    output = args.output.expanduser().resolve()
    metadata = output.with_suffix(output.suffix + ".json")
    if output.exists() or metadata.exists():
        parser.error("Output or metadata file already exists; choose another --output path.")
    created = []
    redirects = Redirects()
    try:
        opener = urllib.request.build_opener(redirects)
        url, published = (args.url, None) if args.url else enclosure(opener)
        parts = urllib.parse.urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
            raise ValueError("The enclosure is not a public HTTP(S) URL without embedded credentials.")
        feed_redirects = list(redirects.observations)
        redirects.observations.clear()
        output.parent.mkdir(parents=True, exist_ok=True)
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
    for path in created:
        path.unlink(missing_ok=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

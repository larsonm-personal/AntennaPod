import argparse
import http.client
import json
import pathlib
import urllib.error
import urllib.parse
import urllib.request


DEFAULT_URL = "https://audioboom.com/posts/8959025-what-the-hell-is-going-on-in-europe.mp3?download=1"


def origin(url):
    parts = urllib.parse.urlsplit(url)
    return parts.scheme + "://" + (parts.hostname or "")


class Redirects(urllib.request.HTTPRedirectHandler):
    def __init__(self):
        self.observations = []

    def redirect_request(self, request, response, code, message, headers, url):
        self.observations.append({"status": code, "from_origin": origin(request.full_url),
                                  "to_origin": origin(url)})
        return super().redirect_request(request, response, code, message, headers, url)


def main():
    parser = argparse.ArgumentParser(description="Download one public MP3 and local response metadata.")
    parser.add_argument("url", nargs="?", default=DEFAULT_URL, help="Direct public episode/enclosure URL, not RSS")
    parser.add_argument("-o", "--output", type=pathlib.Path,
                        default=pathlib.Path.home() / "Downloads" / "antennapod-europe.mp3")
    args = parser.parse_args()
    parts = urllib.parse.urlsplit(args.url)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password:
        parser.error("Use a public HTTP(S) URL without embedded credentials.")
    output = args.output.expanduser().resolve()
    metadata = output.with_suffix(output.suffix + ".json")
    if output.exists() or metadata.exists():
        parser.error("Output or metadata file already exists; choose another --output path.")
    created = []
    redirects = Redirects()
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        with metadata.open("x", encoding="utf-8") as details:
            created.append(metadata)
            with output.open("xb") as audio:
                created.append(output)
                with urllib.request.build_opener(redirects).open(args.url, timeout=60) as response:
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
                    json.dump({"status": response.status, "final_origin": origin(response.url),
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
    except ValueError as error:
        print("Download stopped:", error)
    except (OSError, http.client.HTTPException) as error:
        print("Download stopped:", type(error).__name__, "(check connection and output permissions).")
    for path in created:
        path.unlink(missing_ok=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

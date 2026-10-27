import contextlib
import http.server
import io
import json
import pathlib
import sys
import tempfile
import threading
from unittest import mock

import downloadEpisode as capture


requests = []
mode = "stable"
network = None
original = b"abcdefghijklmnop"
changed = b"ABCDEFGHIJKLMNOP"


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def record(self):
        requests.append((self.command, self.path, self.headers.get("Range"), self.headers.get("If-Range")))

    def redirect(self, location):
        self.send_response(302)
        self.send_header("Location", location)
        self.end_headers()

    def do_HEAD(self):
        self.record()
        if self.path == "/entry":
            self.redirect("/pinned?secret=PRIVATE")
            return
        self.send_response(403 if mode in ("head403", "head403get403") else 200)
        self.send_header("Content-Length", "999")
        self.end_headers()

    def do_GET(self):
        self.record()
        assert self.headers.get("User-Agent") == ("AntennaPod/3.13.0-beta1" if network else
                                                  "Python-urllib/" + capture.urllib.request.__version__)
        if mode in ("head403get403", "get403"):
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b"private error evidence")
            return
        if self.path == "/feed":
            if mode == "feed403":
                self.send_response(403)
                self.end_headers()
                self.wfile.write(b"feed denied")
                return
            url = f"http://127.0.0.1:{self.server.server_port}/entry"
            if mode == "feed_missing":
                url += "-different"
            duration = "invalid" if mode == "feed_invalid" else "1:02:03"
            item = f'<item><enclosure url="{url}"/><itunes:duration>{duration}</itunes:duration></item>'
            xml = ('<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>' + item
                   + (item if mode == "feed_ambiguous" else "") + '</channel></rss>').encode()
            if mode == "feed_bad_xml":
                xml = b"<broken"
            self.send_response(200)
            self.send_header("Content-Length", str(len(xml)))
            self.end_headers()
            self.wfile.write(xml)
            return
        requested_range = self.headers.get("Range")
        if not requested_range and self.path.startswith("/pinned"):
            self.redirect("/get-target?secret=PRIVATE")
            return
        body, status, start, end, total = original, 200, 0, len(original) - 1, len(original)
        if requested_range:
            if mode == "416":
                self.send_response(416)
                self.end_headers()
                return
            if mode == "changed":
                body = changed
            if mode == "third_overlap" and requested_range.startswith("bytes=0-"):
                body = changed
            if mode == "shorter":
                body = changed[:12]
            total = len(body)
            start, end = map(int, requested_range[6:].split("-"))
            end = min(end, total - 1)
            if mode != "ignored":
                status, body = 206, body[start:end + 1]
        self.send_response(status)
        if status == 206:
            first = start + (1 if mode == "wrong" else 0)
            self.send_header("Content-Range", f"bytes {first}-{end}/{total}")
        self.send_header("X-Fixture", "one")
        self.send_header("X-Fixture", "two")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("ETag", '"B"' if requested_range and mode in ("changed", "shorter") else '"A"')
        self.end_headers()
        self.wfile.write(body[:-1] if requested_range and mode == "truncated" else body)


def run_case(directory, server):
    requests.clear()
    output = pathlib.Path(directory) / (mode + (network or "") + ".mp3")
    sys.argv = ["capture", f"http://127.0.0.1:{server.server_port}/entry", "-o", str(output),
                "--staged", "--delay", "0", "--prefix-bytes", "8"]
    if mode in ("stable", "changed", "third_overlap"):
        sys.argv.append("--overlap")
    if network:
        sys.argv.extend(["--network-test", network, "--half"])
        sys.argv.pop(1)
        log_path = pathlib.Path(directory) / "private-log.txt"
        log_path.write_text('123 456 resolver.original=' + json.dumps(
            f"http://127.0.0.1:{server.server_port}/entry") + "\n", encoding="utf-8")
        sys.argv.extend(["--trace-log", str(log_path)])
    with contextlib.redirect_stdout(io.StringIO()) as log, mock.patch("builtins.input", return_value="") as prompt:
        code = capture.main()
    if network:
        assert prompt.call_count == (0 if mode in ("head403get403", "get403") else 1)
        if prompt.called:
            assert ("SAME" if network == "control" else "CELLULAR") in prompt.call_args.args[0]
    folder = output.with_suffix(".mp3.capture")
    data = json.loads((folder / "manifest.json").read_text())
    assert "secret=PRIVATE" not in log.getvalue()
    if network:
        assert data["network_test"] == network
        assert data["requests"][0]["redirects"][0]["location"] == "/pinned?secret=PRIVATE"
        assert data["requests"][0]["request_headers"]["Accept-Encoding"] == "gzip"
        if mode == "head403get403":
            assert len(requests) == 2 and len(data["requests"]) == 1
            assert data["requests"][0]["status"] == 403
            assert code == 1
            print("PASS:", mode, network)
            return
        elif mode == "get403":
            assert code == 1 and len(requests) == 3
            assert (folder / data["requests"][1]["error_file"]).read_bytes() == b"private error evidence"
            assert prompt.call_count == 0
            print("PASS:", mode, network)
            return
        else:
            assert [v for k, v in data["requests"][1]["response_headers"] if k == "X-Fixture"] == ["one", "two"]
            assert data["requests"][1]["body_finished_utc"]
            assert data["pause_seconds"] >= 0
            outgoing = data["requests"][1]["outgoing_hops"]
            assert len(outgoing) == 2
            assert all(hop["method"] == "GET" for hop in outgoing)
            assert any(name.lower() == "host" for name, value in outgoing[0]["headers"])
    else:
        assert "PRIVATE" not in json.dumps(data)
    assert [request[0] for request in requests[:2]] == ["HEAD", "HEAD"], requests
    assert all(record["request_headers"]["User-Agent"] == ("AntennaPod/3.13.0-beta1" if network else
                   "Python-urllib/" + capture.urllib.request.__version__)
               for record in data["requests"])
    if mode in ("head403", "head403get403"):
        assert data["requests"][0]["status"] == 403
        assert data["requests"][0]["final_route"] == data["requests"][1]["requested_route"]
    if mode == "head403get403":
        assert len(requests) == 3 and requests[2][0] == "GET"
        assert requests[2][1] == requests[1][1]
        assert data["requests"][1]["status"] == 403
        assert not (folder / "prefix.bin").exists()
    else:
        assert requests[2][1].startswith("/pinned") and requests[3][1].startswith("/get-target")
        assert requests[4][1].startswith("/pinned") and requests[4][2] == "bytes=8-15"
        assert requests[4][3] is None
        assert (folder / "prefix.bin").read_bytes() == original[:8]
    if mode in ("stable", "changed", "shorter", "third_overlap", "head403"):
        assert code == 0, log.getvalue()
        tail = original[8:] if mode in ("stable", "third_overlap", "head403") else changed[8:12 if mode == "shorter" else 16]
        assert (folder / "reconstructed.mp3").read_bytes() == original[:8] + tail
        if mode == "changed":
            assert data["overlap"]["matches_prefix"] is False
            assert data["overlap"]["matches_suffix"] is True
        if mode == "shorter":
            assert data["retry_total"] == 12
        if mode == "third_overlap":
            assert data["overlap"]["matches_prefix"] is False
            assert data["overlap"]["matches_suffix"] is False
    elif mode == "ignored":
        assert code == 0 and (folder / "restart-response.mp3").read_bytes() == original
        assert not (folder / "reconstructed.mp3").exists()
    else:
        assert code == 1 and not (folder / "reconstructed.mp3").exists(), log.getvalue()
        if mode in ("wrong", "truncated"):
            assert (folder / "suffix.bin").exists()
    print("PASS:", mode, network or "legacy")


def feed_cases(directory, server):
    global mode, network
    network = "control"
    for mode in ("feed_ok", "feed_invalid", "feed_missing", "feed_ambiguous", "feed403", "feed_bad_xml"):
        requests.clear()
        output = pathlib.Path(directory) / (mode + ".mp3")
        sys.argv = ["capture", f"http://127.0.0.1:{server.server_port}/entry", "--network-test", "control",
                    "--half", "--delay", "0", "--prompt-feed-url", "--output", str(output)]
        with mock.patch.object(capture.getpass, "getpass", return_value=
                               f"http://127.0.0.1:{server.server_port}/feed"), \
                mock.patch("builtins.input", return_value=""), contextlib.redirect_stdout(io.StringIO()):
            code = capture.main()
        folder = output.with_suffix(".mp3.capture")
        data = json.loads((folder / "manifest.json").read_text())
        assert requests[0][:2] == ("GET", "/feed")
        assert data["requests"][0]["role"] == "rss"
        if mode in ("feed_ok", "feed_invalid"):
            assert code == 0
            assert data["feed"]["itunes_duration_seconds"] == (3723 if mode == "feed_ok" else None)
            assert data["feed"]["duration_status"] == ("parsed" if mode == "feed_ok" else "invalid")
        else:
            assert code == 1 and len(requests) == 1
        assert (folder / ("error-1.bin" if mode == "feed403" else "feed.xml")).exists()
        print("PASS:", mode)


def streaming_redirect_case():
    reached_destination = threading.Event()
    release = threading.Event()

    class StreamingHandler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.path == "/start":
                self.send_response(302)
                self.send_header("Location", "/done")
                self.send_header("Content-Length", str(1024 * 1024))
                self.end_headers()
                self.wfile.write(b"x" * 65537)
                self.wfile.flush()
                release.wait(5)
            else:
                reached_destination.set()
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"OK")

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), StreamingHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    redirects = capture.Redirects(private=True)
    try:
        opener = capture.urllib.request.build_opener(redirects)
        with opener.open(f"http://127.0.0.1:{server.server_port}/start", timeout=1) as response:
            assert response.read() == b"OK"
        assert reached_destination.is_set()
        assert not release.is_set()
        evidence = redirects.observations[0]
        assert evidence["body_capped"] is True
        assert capture.base64.b64decode(evidence["body_base64"]) == b"x" * 65536
        print("PASS: oversized streaming redirect follows without draining withheld body")
    finally:
        release.set()
        server.shutdown()
        server.server_close()


def input_cases(directory):
    log_path = pathlib.Path(directory) / "ambiguous.txt"
    log_path.write_text('1 2 resolver.original="https://example.invalid/private-one"\n'
                        '1 2 resolver.original="https://example.invalid/private-two"\n', encoding="utf-8")
    sys.argv = ["capture", "--trace-log", str(log_path), "--network-test", "switch"]
    with mock.patch.object(capture.urllib.request, "build_opener") as opener, contextlib.redirect_stderr(io.StringIO()):
        try:
            capture.main()
            raise AssertionError("Ambiguous log accepted")
        except SystemExit as error:
            assert error.code == 2
        opener.assert_not_called()
    sys.argv = ["capture", "--prompt-url", "--network-test", "control"]
    with mock.patch.object(capture.getpass, "getpass", return_value=""), contextlib.redirect_stderr(io.StringIO()):
        try:
            capture.main()
            raise AssertionError("Empty private prompt accepted")
        except SystemExit as error:
            assert error.code == 2
    print("PASS: ambiguous log and empty private prompt stop before requests")


if __name__ == "__main__":
    streaming_redirect_case()
    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory() as directory:
            input_cases(directory)
            for mode in ("stable", "changed", "shorter", "ignored", "wrong", "truncated", "416",
                         "head403", "head403get403", "third_overlap"):
                run_case(directory, server)
            for network in ("control", "switch"):
                for mode in ("stable", "changed", "head403get403", "third_overlap", "ignored", "truncated", "get403"):
                    run_case(directory, server)
            feed_cases(directory, server)
    finally:
        server.shutdown()
        server.server_close()

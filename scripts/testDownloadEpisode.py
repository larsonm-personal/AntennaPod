import contextlib
import http.server
import io
import json
import pathlib
import sys
import tempfile
import threading

import downloadEpisode as capture


requests = []
mode = "stable"
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
        assert self.headers.get("User-Agent") == "Python-urllib/" + capture.urllib.request.__version__
        if mode == "head403get403":
            self.send_response(403)
            self.end_headers()
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
        self.send_header("Content-Length", str(len(body)))
        self.send_header("ETag", '"B"' if requested_range and mode in ("changed", "shorter") else '"A"')
        self.end_headers()
        self.wfile.write(body[:-1] if requested_range and mode == "truncated" else body)


def run_case(directory, server):
    requests.clear()
    output = pathlib.Path(directory) / (mode + ".mp3")
    sys.argv = ["capture", f"http://127.0.0.1:{server.server_port}/entry", "-o", str(output),
                "--staged", "--delay", "0", "--prefix-bytes", "8"]
    if mode in ("stable", "changed", "third_overlap"):
        sys.argv.append("--overlap")
    with contextlib.redirect_stdout(io.StringIO()) as log:
        code = capture.main()
    folder = output.with_suffix(".mp3.capture")
    data = json.loads((folder / "manifest.json").read_text())
    assert "PRIVATE" not in json.dumps(data) + log.getvalue()
    assert [request[0] for request in requests[:2]] == ["HEAD", "HEAD"], requests
    assert all(record["request_headers"]["User-Agent"] == "Python-urllib/" + capture.urllib.request.__version__
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
    print("PASS:", mode)


if __name__ == "__main__":
    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory() as directory:
            for mode in ("stable", "changed", "shorter", "ignored", "wrong", "truncated", "416",
                         "head403", "head403get403", "third_overlap"):
                run_case(directory, server)
    finally:
        server.shutdown()
        server.server_close()

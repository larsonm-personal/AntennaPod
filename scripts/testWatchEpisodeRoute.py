import contextlib
import http.client
import http.server
import io
import json
import pathlib
import tempfile
import sys
import threading
from unittest import mock

import watchEpisodeRoute as watch


requests = []
variant = "a"
status = 200
body_bytes_read = []


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_HEAD(self):
        requests.append((self.command, self.path))
        if self.path == "/start":
            self.send_response(302)
            self.send_header("Location", "/" + variant + "?private=secret")
        elif self.path.startswith("/loop"):
            self.send_response(302)
            self.send_header("Location", "/loop" + str(len(requests)))
        elif self.path == "/ftp":
            self.send_response(302)
            self.send_header("Location", "ftp://example.invalid/audio")
        else:
            self.send_response(status)
            self.send_header("Content-Length", "999999999")
            self.send_header("ETag", variant)
            self.send_header("Last-Modified", "Mon, 26 Oct 2026 00:00:00 GMT")
            if status == 429:
                self.send_header("Retry-After", "3600")
        self.end_headers()

    def do_GET(self):
        requests.append((self.command, self.path))
        self.send_error(500)


class Clock:
    def __init__(self):
        self.value = 0
        self.sleeps = []

    def monotonic(self):
        return self.value

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.value += seconds


def counted_read(response, *args, **kwargs):
    body = real_read(response, *args, **kwargs)
    body_bytes_read.append(len(body))
    return body


server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
url = f"http://127.0.0.1:{server.server_port}/start"
real_read = http.client.HTTPResponse.read
try:
    with mock.patch.object(http.client.HTTPResponse, "read", counted_read):
        first = watch.check(url)
        same = watch.check(url)
        assert watch.changes(first, same) == []
        variant = "b"
        changed = watch.check(url)
        assert set(watch.changes(first, changed)) == {"final_url", "redirect_chain", "ETag"}
        assert first["status"] == 200 and first["selected_headers"]["Content-Length"] == ["999999999"]
        assert first["redirects"][0]["method"] == "HEAD"
        assert sum(body_bytes_read) == 0
        assert all(method == "HEAD" for method, path in requests)
        print("PASS: HEAD-only redirects, no body consumed, unchanged and changed evidence")
        with tempfile.TemporaryDirectory() as directory:
            folder = pathlib.Path(directory)
            clock = Clock()
            requests.clear()
            with mock.patch.object(watch.time, "monotonic", clock.monotonic), \
                    mock.patch.object(watch.time, "sleep", clock.sleep), contextlib.redirect_stdout(io.StringIO()) as output:
                assert watch.watch(url, folder / "normal", 900, 8) == 0
            events = [json.loads(line) for line in (folder / "normal/events.jsonl").read_text().splitlines()]
            assert len([event for event in events if event["event"] == "check"]) == 32
            assert clock.sleeps == [900] * 31
            assert len(requests) == 64 and all(method == "HEAD" for method, path in requests)
            assert events[-1]["event"] == "finished"
            assert "secret" not in output.getvalue() and "http" not in output.getvalue()
            print("PASS: eight-hour/15-minute schedule is 32 checks; console hides URLs")
            for status in (401, 403, 405, 429):
                requests.clear()
                with contextlib.redirect_stdout(io.StringIO()), mock.patch.object(watch.time, "sleep") as sleep:
                    assert watch.watch(url, folder / str(status), 900, 8) == 1
                sleep.assert_not_called()
                events = [json.loads(line) for line in (folder / str(status) / "events.jsonl").read_text().splitlines()]
                assert events[1]["status"] == status and events[-1]["event"] == "stopped"
                assert len(requests) == 2 and all(method == "HEAD" for method, path in requests)
                if status == 429:
                    assert ["Retry-After", "3600"] in events[1]["headers"]
            print("PASS: 401/403/405/429 terminal with no GET fallback or repeat")
            status = 200
            with mock.patch.object(watch.time, "sleep", side_effect=KeyboardInterrupt), \
                    contextlib.redirect_stdout(io.StringIO()):
                assert watch.watch(url, folder / "interrupted", 900, 8) == 1
            assert json.loads((folder / "interrupted/events.jsonl").read_text().splitlines()[-1])["event"] == "interrupted"
            print("PASS: interruption preserves flushed checks")
            clock = Clock()
            with mock.patch.object(watch.urllib.request.OpenerDirector, "open",
                                   side_effect=watch.urllib.error.URLError("private transport detail")) as opening, \
                    mock.patch.object(watch.time, "monotonic", clock.monotonic), \
                    mock.patch.object(watch.time, "sleep", clock.sleep), contextlib.redirect_stdout(io.StringIO()):
                assert watch.watch(url, folder / "transport", 900, 8) == 1
            assert opening.call_count == 3 and clock.sleeps == [900, 900]
            assert all(call.kwargs["timeout"] == 20 for call in opening.call_args_list)
            events = [json.loads(line) for line in (folder / "transport/events.jsonl").read_text().splitlines()]
            assert all(event["error"] == "URLError" for event in events if event["event"] == "check")
            print("PASS: timed transport errors recorded; stop after three consecutive failures")
        requests.clear()
        result = watch.check(url.replace("/start", "/loop0"))
        assert result["terminal"] and len(requests) <= 6
        result = watch.check(url.replace("/start", "/ftp"))
        assert result["terminal"] and result["status"] == 302
        assert all(method == "HEAD" for method, path in requests)
        print("PASS: redirect hop bound and non-HTTP redirect rejection")
        for interval, hours in (("0", "8"), ("900", "25")):
            with mock.patch.object(sys, "argv", ["watch", url, "--interval", interval, "--hours", hours,
                                                "--output", "unused"]), \
                    mock.patch.object(watch, "check") as checking, contextlib.redirect_stderr(io.StringIO()):
                try:
                    watch.main()
                    raise AssertionError("Invalid bounds accepted")
                except SystemExit as error:
                    assert error.code == 2
                checking.assert_not_called()
        print("PASS: invalid timing bounds rejected before requests")
finally:
    server.shutdown()
    server.server_close()

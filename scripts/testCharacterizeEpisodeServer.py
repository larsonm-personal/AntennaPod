import contextlib
import http.server
import io
import json
import pathlib
import tempfile
import threading
import time
from types import SimpleNamespace
from unittest import mock

import characterizeEpisodeServer as capture


real_sleep = time.sleep
mode = "stable"
requests = []
resolutions = 0
body_a = bytes(range(256)) * 16
body_b = bytes(reversed(range(256))) * 16


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_HEAD(self):
        global resolutions
        requests.append(("HEAD", self.path, None))
        if self.path == "/original":
            resolutions += 1
            self.send_response(302)
            variant = "b" if resolutions > 1 and mode not in ("stable", "hidden_mutation") else "a"
            self.send_header("Location", "/resolved?variant=" + variant + "&session=" + str(resolutions))
        else:
            expired = mode in ("expired", "gone") and resolutions > 1 and "variant=a" in self.path
            self.send_response((403 if mode == "expired" else 404) if expired else 200)
            self.send_header("Content-Length", str(4096 if "variant=a" in self.path or mode == "key_switch" else 4000))
        self.end_headers()

    def do_GET(self):
        requests.append(("GET", self.path, self.headers.get("Range")))
        assert self.headers.get("If-Range") is None
        start, end = map(int, self.headers["Range"][6:].split("-"))
        body = body_b if "variant=b" in self.path or (mode in ("mutation", "hidden_mutation") and resolutions > 1) else body_a
        if mode == "range416" and resolutions > 1:
            self.send_response(416)
            self.send_header("Content-Range", "bytes */1024")
            self.end_headers()
            return
        self.send_response(200 if mode == "ignored" else 206)
        if mode != "ignored":
            self.send_header("Content-Range", f"bytes {start + (1 if mode == 'wrong_range' else 0)}-{end}/{len(body)}")
        self.send_header("Content-Length", str(len(body) if mode == "ignored" else end - start + 1))
        self.end_headers()
        if mode == "timeout":
            self.wfile.write(body[start:start + 2])
            self.wfile.flush()
            real_sleep(0.15)
            return
        result = body if mode == "ignored" else body[start:end + 1]
        if mode == "short":
            result = result[:-1]
        try:
            self.wfile.write(result)
        except (BrokenPipeError, ConnectionResetError):
            pass


class Clock:
    value = 0

    def monotonic(self):
        return self.value

    def sleep(self, duration):
        self.value += duration


def run(folder, server, request_limit=96, payload_limit=1048576):
    global resolutions
    resolutions = 0
    requests.clear()
    clock = Clock()
    args = SimpleNamespace(observations=None, max_requests=request_limit, payload_bytes=payload_limit,
                           timeout=0.03 if mode == "timeout" else 2, hours=0.001,
                           interval=1, window_bytes=8)
    if mode == "key_switch":
        args.observations = folder.parent / "observations.jsonl"
        args.observations.write_text("\n".join(json.dumps({"event": "check", "status": 200,
            "original_url": f"http://127.0.0.1:{server.server_port}/original",
            "final_url": f"http://127.0.0.1:{server.server_port}/resolved?variant={variant}&session={i}",
            "selected_headers": {"Content-Length": [length]}})
            for i, (variant, length) in enumerate((("a", "4096"), ("b", "4000"), ("a", "4096"), ("b", "4000")))), encoding="utf-8")
        assert capture.correlated_keys(args.observations, f"http://127.0.0.1:{server.server_port}/original") == ["variant"]
    with mock.patch.object(capture.time, "monotonic", clock.monotonic), \
            mock.patch.object(capture.time, "sleep", clock.sleep), contextlib.redirect_stdout(io.StringIO()) as console:
        result = capture.characterize(f"http://127.0.0.1:{server.server_port}/original", folder, args)
    events = [json.loads(line) for line in (folder / "events.jsonl").read_text().splitlines()]
    assert "session=" not in console.getvalue() and "http" not in console.getvalue()
    assert len(requests) <= request_limit
    assert events[-1]["payload_bytes"] <= payload_limit
    assert events[-1]["payload_bytes"] == sum(e.get("bytes_saved", 0) for e in events)
    return result, events


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        for mode in ("stable", "switch", "mutation", "expired", "ignored", "short", "timeout", "wrong_range", "range416", "key_switch", "hidden_mutation", "gone"):
            code, events = run(root / mode, server)
            samples = [e for e in events if e.get("interval")]
            if mode == "expired":
                assert code == 1 and events[-1]["event"] == "stopped"
                denied = next(e for e in events if e.get("status") == 403)
                assert denied["label"] == "pinned_recheck"
                assert events[-2] == denied
            else:
                assert code == 0
                comparison = next(e for e in events if e["event"] == "comparisons")
                pinned = [item["equal"] for item in comparison["baseline_vs_pinned"]]
                fresh = [item["equal"] for item in comparison["baseline_vs_fresh"]]
                if mode == "stable":
                    assert pinned == fresh == [True] * 3
                    assert resolutions == 4
                elif mode in ("switch", "key_switch"):
                    assert pinned == [True] * 3 and fresh == [False] * 3
                elif mode in ("mutation", "hidden_mutation"):
                    assert pinned == fresh == [False] * 3
                elif mode == "gone":
                    assert pinned == [None] * 3 and fresh == [False] * 3
                else:
                    assert pinned == fresh == [None] * 3
                    assert all(not sample["comparable"] for sample in samples
                               if mode != "range416" or sample["label"] != "baseline_sample")
                if mode == "ignored":
                    assert all(e["bytes_saved"] == 9 and e["status"] == 200 for e in samples)
                if mode == "timeout":
                    assert all(e["bytes_saved"] == 2 and e["error"] == "TimeoutError" for e in samples)
            baseline = [e for e in events if e.get("label") == "baseline_sample"]
            later = [e for e in events if e.get("label") == "pinned_sample"]
            assert all(e["requested_url"] == baseline[0]["requested_url"] for e in later)
            print("PASS:", mode)
        mode = "stable"
        for label, request_limit, payload_limit in (("request_budget", 3, 1048576), ("redirect_budget", 1, 1048576), ("payload_budget", 96, 9)):
            code, events = run(root / label, server, request_limit, payload_limit)
            assert code == 1 and "budget" in events[-1]["reason"]
            print("PASS:", label)
        args = SimpleNamespace(observations=None, max_requests=96, payload_bytes=1048576,
                               timeout=2, hours=1, interval=900, window_bytes=8)
        with mock.patch.object(capture.time, "sleep", side_effect=KeyboardInterrupt), \
                contextlib.redirect_stdout(io.StringIO()):
            interrupted = root / "interrupted"
            assert capture.characterize(f"http://127.0.0.1:{server.server_port}/original", interrupted, args) == 1
        events = [json.loads(line) for line in (interrupted / "events.jsonl").read_text().splitlines()]
        assert events[-1]["reason"] == "interrupted"
        assert len(list(interrupted.glob("sample-*.bin"))) == 3
        assert not any(event.get("label") == "pinned_recheck" for event in events)
        print("PASS: interruption retains baseline without new requests")
finally:
    server.shutdown()
    server.server_close()

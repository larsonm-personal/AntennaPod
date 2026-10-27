import argparse
import base64
import decimal
import datetime
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from downloadEpisode import feed_episode


LIMITATIONS = ["Program alignment/cross-correlation and missing ending: not measured.",
               "Durations and valid MP3 frames do not establish content continuity.",
               "Overlap is a separate later response, potentially a third variant."]


def fingerprint(path):
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1048576), b""):
            size += len(block)
            digest.update(block)
    return {"bytes": size, "MB": size / 1048576, "sha256": digest.hexdigest()}


def synchsafe(data):
    if len(data) != 4 or any(value & 128 for value in data):
        raise ValueError("Invalid ID3 synchsafe size")
    return sum(value << (7 * (3 - index)) for index, value in enumerate(data))


def id3(path):
    result = {"TLEN": [], "status": "no leading ID3v2 tag"}
    with path.open("rb") as source:
        header = source.read(10)
        if header[:3] != b"ID3":
            return result
        if len(header) != 10:
            raise ValueError("Truncated ID3 header")
        version = header[3]
        result["version"] = version
        size = synchsafe(header[6:10])
        result["tag_bytes"] = size + 10
        if version not in (3, 4) or header[5] & 128 or size > 16 * 1048576:
            result["status"] = "not parsed: unsupported version, unsynchronization or oversized tag"
            return result
        tag = source.read(size)
    if len(tag) != size:
        raise ValueError("Truncated ID3 tag")
    offset = 0
    if header[5] & 64:
        offset = synchsafe(tag[:4]) if version == 4 else 4 + int.from_bytes(tag[:4], "big")
    result["status"] = "parsed"
    while offset + 10 <= len(tag) and tag[offset] != 0:
        frame = tag[offset:offset + 10]
        length = synchsafe(frame[4:8]) if version == 4 else int.from_bytes(frame[4:8], "big")
        body = tag[offset + 10:offset + 10 + length]
        if len(body) != length:
            raise ValueError("Truncated ID3 frame")
        if frame[:4] == b"TLEN":
            item = {"raw_base64": base64.b64encode(body).decode("ascii"), "raw_hex": body.hex(),
                    "milliseconds": None}
            if frame[9] or not body:
                item["status"] = "not parsed: flagged or empty frame"
            else:
                try:
                    text = body[1:].decode({0: "latin1", 1: "utf-16", 2: "utf-16-be", 3: "utf-8"}[body[0]])
                    item["text"] = text.rstrip("\x00")
                    item["status"] = "empty" if not item["text"] else "non-numeric"
                    if item["text"].isdecimal():
                        item.update(milliseconds=int(item["text"]), status="numeric")
                except (KeyError, UnicodeError):
                    item["status"] = "invalid encoding"
            result["TLEN"].append(item)
        offset += 10 + length
    return result


def seek_headers(data, start):
    result = {"status": "first MPEG layer III frame not identified", "Xing": None, "Info": None, "VBRI": None}
    for offset in range(len(data) - 4):
        word = int.from_bytes(data[offset:offset + 4], "big")
        version, layer, bitrate, rate = (word >> 19) & 3, (word >> 17) & 3, (word >> 12) & 15, (word >> 10) & 3
        if word & 0xffe00000 != 0xffe00000 or version == 1 or layer != 1 or bitrate in (0, 15) or rate == 3:
            continue
        mono = (word >> 6) & 3 == 3
        side = (17 if mono else 32) if version == 3 else (9 if mono else 17)
        xing = offset + 4 + side
        result.update(status="checked standard offsets in first plausible MPEG layer III frame",
                      frame_byte=start + offset)
        for name, position in (("Xing", xing), ("Info", xing), ("VBRI", offset + 36)):
            if data[position:position + 4] == name.encode():
                result[name] = start + position
        break
    return result


def audio_report(path, decode):
    report = {"file": path.name, **fingerprint(path), "id3": id3(path)}
    start = report["id3"].get("tag_bytes", 0)
    with path.open("rb") as source:
        source.seek(start)
        report["seek_headers"] = seek_headers(source.read(65536), start)
    probe = shutil.which("ffprobe")
    if not probe:
        report["audio_measurements"] = "not measured: ffprobe is not installed"
        return report
    base = [probe, "-v", "error", "-protocol_whitelist", "file,pipe", "-f", "mp3", "-i", str(path)]
    with tempfile.TemporaryFile() as errors:
        metadata = subprocess.run(base + ["-show_entries", "stream=codec_name,sample_rate,channels,bit_rate:"
                                         "format=duration,bit_rate", "-of", "json"],
                                  stdout=subprocess.PIPE, stderr=errors, check=False)
        report["probe_exit_code"] = metadata.returncode
        report["probe"] = json.loads(metadata.stdout or b"{}")
        report["probe_duration_kind"] = "demuxer estimate; not decoded duration"
        packets = subprocess.Popen(base + ["-show_packets", "-show_entries", "packet=duration_time,size",
                                           "-of", "compact=p=0:nk=0"], stdout=subprocess.PIPE, stderr=errors)
        duration, count, packet_bytes = decimal.Decimal(0), 0, 0
        missing_duration = 0
        for line in packets.stdout:
            fields = dict(part.split("=", 1) for part in line.decode("ascii").strip().split("|") if "=" in part)
            if "size" not in fields:
                continue
            count += 1
            packet_bytes += int(fields["size"])
            try:
                duration += decimal.Decimal(fields["duration_time"])
            except (KeyError, decimal.InvalidOperation):
                missing_duration += 1
        report["encoded_packets"] = {"count": count, "bytes": packet_bytes,
                                     "duration_seconds": float(duration) if count and not missing_duration else None,
                                     "missing_duration": missing_duration,
                                     "exit_code": packets.wait(),
                                     "meaning": "sum of demuxed packet durations, not decoded samples"}
        errors.seek(0, 2)
        report["probe_error_output_bytes"] = errors.tell()
        errors.seek(0)
        report["probe_error_excerpt"] = errors.read(4096).decode("utf-8", errors="replace")
    report["decoded"] = {"status": "not measured: use --decode and install ffmpeg"}
    if decode and shutil.which("ffmpeg"):
        with tempfile.TemporaryFile() as errors:
            process = subprocess.Popen([shutil.which("ffmpeg"), "-v", "error", "-nostdin",
                                        "-protocol_whitelist", "file,pipe", "-f", "mp3", "-i", str(path),
                                        "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "48000",
                                        "-f", "s16le", "pipe:1"], stdout=subprocess.PIPE, stderr=errors)
            size = sum(len(block) for block in iter(lambda: process.stdout.read(65536), b""))
            code = process.wait()
            errors.seek(0, 2)
            report["decoded"] = {"status": "decoded output measured" if code == 0 and errors.tell() == 0 else
                                 "partial or errored decode; duration is not proof of completeness", "exit_code": code,
                                 "error_output_bytes": errors.tell(), "pcm_bytes": size,
                                 "seconds": size / 96000,
                                 "meaning": "output samples after mono 48 kHz s16 conversion; inspect errors"}
            errors.seek(0)
            report["decoded"]["error_excerpt"] = errors.read(4096).decode("utf-8", errors="replace")
    return report


def feed_duration(snapshot, manifest):
    if not snapshot:
        return {"status": "not measured: no local RSS snapshot supplied"}
    source_hash = manifest.get("source_route", {}).get("url_sha256")
    root = ET.parse(snapshot)
    urls = {entry.get("url", "") for entry in root.findall("./channel/item/enclosure")
            if hashlib.sha256(entry.get("url", "").encode()).hexdigest() == source_hash}
    if len(urls) != 1:
        return {"status": "not measured: enclosure match missing or ambiguous"}
    return feed_episode(ET.tostring(root.getroot()), urls.pop())


def analyze(folder, snapshot, decode):
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    result = {"capture": folder.name, "outcome": manifest.get("outcome"),
              "network_test": manifest.get("network_test"), "pause_seconds": manifest.get("pause_seconds"),
              "feed_itunes_duration": manifest.get("feed") or feed_duration(snapshot, manifest), "requests": []}
    for request in manifest["requests"]:
        record = {key: request.get(key) for key in ("role", "method", "status", "time_utc", "response_time_utc",
                  "body_finished_utc", "requested_route", "final_route", "request_headers", "headers",
                  "bytes_saved", "sha256", "diagnostic_only")}
        name = request.get("file")
        if name and pathlib.Path(name).name == name:
            record["actual_file"] = fingerprint(folder / name)
            record["matches_manifest_hash"] = record["actual_file"]["sha256"] == request.get("sha256")
        result["requests"].append(record)
    result["overlap"] = manifest.get("overlap")
    result["reconstruction"] = manifest.get("reconstruction")
    audio = next((folder / name for name in ("reconstructed.mp3", "restart-response.mp3")
                  if (folder / name).is_file()), None)
    result["audio"] = audio_report(audio, decode) if audio else {"status": "not measured: no complete candidate"}
    decoded = result["audio"].get("decoded", {}).get("seconds")
    feed = result["feed_itunes_duration"].get("itunes_duration_seconds")
    result["decoded_minus_feed_seconds"] = decoded - feed if decoded is not None and feed is not None else None
    for entry in result["audio"].get("id3", {}).get("TLEN", []):
        value = entry["milliseconds"]
        entry["decoded_minus_TLEN_seconds"] = decoded - value / 1000 if decoded is not None and value is not None else None
    return result


def main():
    parser = argparse.ArgumentParser(description="Offline comparison of existing episode captures; no network requests")
    parser.add_argument("captures", nargs="+", type=pathlib.Path)
    parser.add_argument("--feed-snapshot", type=pathlib.Path, help="Optional LOCAL RSS XML; no feed URL fetching")
    parser.add_argument("--decode", action="store_true", help="Decode locally with ffmpeg; can take minutes")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; choose a new report filename.")
    try:
        captures = [analyze(path.resolve(), args.feed_snapshot, args.decode) for path in args.captures]
        report = {"created_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "limitations": LIMITATIONS, "captures": captures, "byte_identical_candidates": [],
                  "tool_versions": {}}
        for name in ("ffmpeg", "ffprobe"):
            executable = shutil.which(name)
            report["tool_versions"][name] = subprocess.run([executable, "-version"], stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, check=False).stdout.decode("utf-8", errors="replace").splitlines()[0] \
                if executable else "not installed"
        for i, first in enumerate(captures):
            for j, second in enumerate(captures[i + 1:], i + 1):
                left, right = first["audio"].get("sha256"), second["audio"].get("sha256")
                report["byte_identical_candidates"].append({"captures": [i, j],
                    "equal": left == right if left and right else None})
        with args.output.open("x", encoding="utf-8") as output:
            json.dump(report, output, indent=2)
        print("Saved local comparison report. Program alignment and ending completeness are not measured.")
        return 0
    except (OSError, ValueError, ET.ParseError, KeyError) as error:
        print("Analysis stopped:", type(error).__name__, "(check local files and installed tools).")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

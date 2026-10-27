import argparse
import array
import bisect
import hashlib
import json
import math
import pathlib
import shutil
import subprocess
import sys
import tempfile


def load_samples(folder):
    samples = []
    for line in (folder / 'events.jsonl').read_text(encoding='utf-8').splitlines():
        event = json.loads(line)
        if event.get('event') != 'response' or event.get('label') not in ('baseline_sample', 'fresh_sample'):
            continue
        if not event.get('comparable'):
            continue
        name = event.get('file', '')
        path = (folder / name).resolve()
        if path.parent != folder.resolve() or not path.is_file():
            raise ValueError('Sample file must be directly inside the capture directory')
        data = path.read_bytes()
        first, last = event['interval']
        if first < 0 or len(data) != last - first + 1 or hashlib.sha256(data).hexdigest() != event['sha256']:
            raise ValueError('Sample length or hash does not match its journal')
        samples.append((event, path, data))
    return samples


def mp3_frames(data):
    offset = 0
    if data[:3] == b'ID3' and len(data) >= 10:
        offset = 10 + sum((data[6 + index] & 127) << (21 - 7 * index) for index in range(4))
        if data[3] == 4 and data[5] & 16:
            offset += 10
    frames, seconds = [], 0.0
    while offset + 4 <= len(data):
        header = int.from_bytes(data[offset:offset + 4], 'big')
        version, layer = (header >> 19) & 3, (header >> 17) & 3
        rate_index, bitrate_index = (header >> 10) & 3, (header >> 12) & 15
        if header >> 21 != 2047 or version == 1 or layer != 1 or rate_index == 3 or bitrate_index in (0, 15):
            if data[offset:offset + 3] == b'TAG' and len(data) - offset == 128:
                break
            raise ValueError('Cannot establish a complete MP3 frame index for A')
        rate = (44100, 48000, 32000)[rate_index] // (1 if version == 3 else 2 if version == 2 else 4)
        rates = (0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320) if version == 3 else \
            (0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        size = (144 if version == 3 else 72) * rates[bitrate_index] * 1000 // rate + ((header >> 9) & 1)
        if offset + size > len(data):
            raise ValueError('Truncated final MP3 frame in A')
        frames.append((offset, seconds, size))
        seconds += (1152 if version == 3 else 576) / rate
        offset += size
    return frames


def frame_time(frames, offset):
    index = bisect.bisect_right(frames, (offset, float('inf'), float('inf'))) - 1
    return frames[index][1] if index >= 0 and offset < frames[index][0] + frames[index][2] else None


def encoded_matches(reference, sample):
    if len(sample) < 12288:
        return {'status': 'not measured: sample too short for interior blocks'}
    blocks = []
    for start in sorted({2048, len(sample) // 2 - 2048, len(sample) - 6144}):
        block = sample[start:start + 4096]
        found, offset = [], 0
        while len(found) < 9:
            offset = reference.find(block, offset)
            if offset < 0:
                break
            found.append(offset)
            offset += 1
        blocks.append({'sample_offset': start, 'a_offsets': found[:8], 'more_matches': len(found) > 8})
    shifts = [item['a_offsets'][0] - item['sample_offset'] for item in blocks
              if len(item['a_offsets']) == 1 and not item['more_matches']]
    coherent = len(shifts) == len(blocks) and len(set(shifts)) == 1
    return {'status': 'three unique interior blocks agree' if coherent else 'no unique consistent block alignment',
            'sample_start_in_a_bytes': shifts[0] if coherent else None, 'blocks': blocks,
            'meaning': 'encoded byte correspondence only; not a B playback timestamp'}


def envelope(path):
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(['ffmpeg', '-v', 'error', '-nostdin', '-protocol_whitelist', 'file,pipe',
                                    '-f', 'mp3', '-i', str(path), '-map', '0:a:0', '-vn', '-ac', '1', '-ar', '8000',
                                    '-f', 's16le', 'pipe:1'], stdout=subprocess.PIPE, stderr=errors)
        values = []
        try:
            while True:
                block = process.stdout.read(1600)
                if len(block) != 1600:
                    break
                pcm = array.array('h', block)
                if sys.byteorder != 'little':
                    pcm.byteswap()
                values.append(math.log1p(math.sqrt(sum(value * value for value in pcm) / len(pcm))))
            code = process.wait()
        finally:
            process.stdout.close()
            if process.poll() is None:
                process.kill()
                process.wait()
        errors.seek(0, 2)
        return values, {'exit_code': code, 'error_bytes': errors.tell(), 'decoded_seconds': len(values) / 10}


def correlate(reference, sample):
    sample = sample[5:-5]
    count = len(sample)
    if count < 15:
        return {'status': 'not measured: less than 1.5 seconds after edge exclusion'}
    if len(reference) < count:
        return {'status': 'not measured: reference shorter than trimmed sample'}
    mean = sum(sample) / count
    centered = [value - mean for value in sample]
    energy = sum(value * value for value in centered)
    if energy < 0.01 * count:
        return {'status': 'not measured: sample envelope has too little variation'}
    candidates = []
    total = sum(reference[:count])
    squares = sum(value * value for value in reference[:count])
    for start in range(len(reference) - count + 1):
        variance = max(0, squares - total * total / count)
        score = sum(centered[index] * reference[start + index] for index in range(count)) \
            / math.sqrt(energy * variance) if variance >= 0.01 * count else 0
        candidates.append((score, start))
        if start + count < len(reference):
            before, after = reference[start], reference[start + count]
            total += after - before
            squares += after * after - before * before
    candidates.sort(reverse=True)
    best = candidates[0]
    runner = next((item for item in candidates if abs(item[1] - best[1]) >= count), None)
    margin = best[0] - runner[0] if runner else None
    minimum_score, minimum_margin = (0.95, 0.15) if count < 20 else (0.90, 0.10)
    plausible = best[0] >= minimum_score and (margin is None or margin >= minimum_margin)
    return {'status': 'candidate local match' if plausible else 'ambiguous or no strong local match',
            'a_seconds_for_sample_after_edge_trim': best[1] / 10,
            'excluded_sample_edge_seconds': 0.5, 'compared_seconds': count / 10,
            'minimum_correlation': minimum_score, 'minimum_margin': minimum_margin,
            'short_fragment': count < 20, 'correlation': best[0], 'distinct_runner_up_correlation': runner[0] if runner else None,
            'margin': margin, 'meaning': '100 ms log-RMS envelope search; heuristic, confirm by listening; '
            'not a probability or a B timestamp; unknown decoder lead-in may shift the estimate'}


def compare(reference_path, folder, decode=False):
    reference = reference_path.read_bytes()
    samples = load_samples(folder)
    baseline = [(event, data) for event, path, data in samples if event['label'] == 'baseline_sample']
    if not baseline:
        raise ValueError('No valid baseline A samples')
    expected_starts = {0, len(reference) // 2, len(reference) * 3 // 4}
    if {event['interval'][0] for event, data in baseline} != expected_starts:
        raise ValueError('All three baseline A sample intervals are required')
    checks = [{'interval': event['interval'], 'equal': reference[event['interval'][0]:event['interval'][1] + 1] == data,
               'declared_total_matches': event.get('range_total') == len(reference)} for event, data in baseline]
    report = {'a_sha256': hashlib.sha256(reference).hexdigest(), 'a_bytes': len(reference), 'a_sample_checks': checks,
              'samples': [], 'limitations': 'Sparse samples do not establish whole-file identity, B timeline, '
              'ad layout, missing content or ending. All processing is local.'}
    if not all(item['equal'] and item['declared_total_matches'] for item in checks):
        report['status'] = 'supplied A does not match baseline evidence; alignment stopped'
        return report
    report['status'] = 'supplied A agrees with all available baseline sample intervals and lengths'
    decoded_a = None
    try:
        frames = mp3_frames(reference)
        report['a_frame_clock'] = 'MP3 frame clock, before encoder-delay/padding adjustment; not exact audible time'
    except ValueError as error:
        frames = []
        report['a_frame_clock'] = str(error)
    for event, path, data in samples:
        if event['label'] != 'fresh_sample':
            continue
        item = {'sample_number': event['number'], 'interval': event['interval'], 'sha256': event['sha256']}
        report['samples'].append(item)
        if event['interval'][0] == 0:
            item['status'] = 'initial metadata/header window excluded from audio alignment'
            continue
        item['a_requested_offset_frame_seconds'] = frame_time(frames, event['interval'][0]) if frames else None
        item['encoded'] = encoded_matches(reference, data)
        match = item['encoded'].get('sample_start_in_a_bytes')
        if frames and match is not None:
            consecutive = [frame for frame in frames if match + 2048 <= frame[0]
                           and frame[0] + frame[2] <= match + 6144]
            item['encoded']['complete_consecutive_frames_in_first_block'] = len(consecutive)
            original_time = frame_time(frames, event['interval'][0])
            matched_time = frame_time(frames, match)
            item['encoded'].update(a_requested_offset_frame_seconds=original_time,
                                   a_matched_offset_frame_seconds=matched_time,
                                   a_content_displacement_seconds=matched_time - original_time
                                   if matched_time is not None and original_time is not None else None)
        else:
            item['encoded']['frame_timing'] = 'not measured: no unique alignment or complete A frame index'
        if decode:
            if not shutil.which('ffmpeg'):
                item['decoded'] = {'status': 'not measured: ffmpeg is not installed'}
                continue
            if decoded_a is None:
                decoded_a, report['a_decode'] = envelope(reference_path)
            if report['a_decode']['exit_code'] or report['a_decode']['error_bytes']:
                item['decoded'] = {'status': 'not measured: reference decode reported errors'}
                continue
            decoded, diagnostic = envelope(path)
            item['sample_decode'] = diagnostic
            item['decoded'] = correlate(decoded_a, decoded)
            if diagnostic['exit_code'] or diagnostic['error_bytes']:
                item['decoded']['decode_warning'] = 'fragment decode reported errors; candidate requires manual confirmation'
    return report


def main():
    parser = argparse.ArgumentParser(description='Compare saved sparse MP3 samples with an existing full A, offline.')
    parser.add_argument('full_a', type=pathlib.Path)
    parser.add_argument('capture', type=pathlib.Path)
    parser.add_argument('--decode', action='store_true', help='Optional local FFmpeg envelope correlation')
    parser.add_argument('--output', required=True, type=pathlib.Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output already exists')
    result = compare(args.full_a, args.capture, args.decode)
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(result, output, indent=2)
    print(result['status'])
    return 0 if result['status'].startswith('supplied A agrees') else 1


if __name__ == '__main__':
    sys.exit(main())

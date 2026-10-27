import json
import hashlib
import math
import pathlib
import random
import shutil
import subprocess
import tempfile
import wave
import array
from unittest import mock

import alignEpisodeSamples as align


rng = random.Random(42)
reference = b''.join(b'\xff\xfb\x90\x00' + rng.randbytes(413) for unused in range(600))
frames = align.mp3_frames(reference)
assert len(frames) == 600
assert abs(align.frame_time(frames, 41700) - 100 * 1152 / 44100) < 1e-10
sample = reference[50000:50000 + 20000]
assert align.encoded_matches(reference, sample)['sample_start_in_a_bytes'] == 50000
assert align.encoded_matches(b'prefix' + reference, sample)['sample_start_in_a_bytes'] == 50006
assert align.encoded_matches(reference, rng.randbytes(20000))['sample_start_in_a_bytes'] is None
assert align.encoded_matches(reference + reference, sample)['sample_start_in_a_bytes'] is None
print('PASS: identical/shifted/absent/ambiguous blocks and actual MP3 frame clock')

signal = [rng.uniform(1, 8) for unused in range(1000)]
for start in (0, 123):
    result = align.correlate(signal, signal[start:start + 50])
    assert result['status'] == 'candidate local match'
    assert result['a_seconds_for_sample_after_edge_trim'] == (start + 5) / 10
assert align.correlate(signal, [rng.uniform(1, 8) for unused in range(50)])['status'] != 'candidate local match'
assert align.correlate(signal + signal, signal[100:150])['status'] != 'candidate local match'
assert 'little variation' in align.correlate(signal, [1] * 50)['status']
print('PASS: envelope matches, nonmatch, repeated ambiguity and constant signal')
short = signal[123:150]
result = align.correlate(signal, short)
assert result['status'] == 'candidate local match'
assert result['compared_seconds'] == 1.7 and result['a_seconds_for_sample_after_edge_trim'] == 12.8
assert result['minimum_correlation'] == 0.95 and result['minimum_margin'] == 0.15
assert result['short_fragment']
assert align.correlate(signal + signal, short)['status'] != 'candidate local match'
assert align.correlate(signal, [rng.uniform(1, 8) for unused in range(27)])['status'] != 'candidate local match'
assert '1.5 seconds' in align.correlate(signal, short[:24])['status']
assert 'little variation' in align.correlate(signal, [1] * 27)['status']
print('PASS: actual-length 27-bin fragment searched, with stricter score/margin and ambiguity rejection')


with tempfile.TemporaryDirectory() as directory:
    folder = pathlib.Path(directory)
    full = folder / 'a.mp3'
    full.write_bytes(reference)
    records = []
    for number, (label, offset) in enumerate([('baseline_sample', start) for start in
                                            (0, len(reference) // 2, len(reference) * 3 // 4)]
                                           + [('fresh_sample', 50000)], 1):
        start = offset + 20000 if label == 'fresh_sample' else offset
        data = reference[start:start + 20000]
        name = 'sample-%03d.bin' % number
        (folder / name).write_bytes(data)
        records.append({'event': 'response', 'label': label, 'number': number, 'file': name,
                        'comparable': True, 'interval': [offset, offset + len(data) - 1],
                        'range_total': len(reference), 'sha256': hashlib.sha256(data).hexdigest()})
    (folder / 'events.jsonl').write_text('\n'.join(map(json.dumps, records)))
    result = align.compare(full, folder)
    assert result['status'].startswith('supplied A agrees')
    assert result['samples'][0]['encoded']['a_content_displacement_seconds'] == \
        align.frame_time(frames, 70000) - align.frame_time(frames, 50000)
    assert result['samples'][0]['encoded']['complete_consecutive_frames_in_first_block'] >= 3
    with mock.patch.object(align.shutil, 'which', return_value=None):
        assert 'not installed' in align.compare(full, folder, True)['samples'][0]['decoded']['status']
    full.write_bytes(b'x' + reference[1:])
    result = align.compare(full, folder, True)
    assert 'does not match' in result['status'] and not result['samples']
    print('PASS: all baseline windows checked, mismatched A stops, missing decoder explicit')

    if shutil.which('ffmpeg'):
        audio = array.array('h')
        levels = [rng.uniform(0.05, 0.8) for unused in range(202)]
        for index in range(160000):
            step, fraction = divmod(index, 3200)
            amplitude = levels[step] + (levels[step + 1] - levels[step]) * fraction / 3200
            audio.append(int(30000 * amplitude * math.sin(index / 8000 * 440)))
        wav = folder / 'source.wav'
        with wave.open(str(wav), 'wb') as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(8000)
            output.writeframes(audio.tobytes())
        subprocess.run(['ffmpeg', '-v', 'error', '-i', str(wav), '-ar', '44100', '-b:a', '128k',
                        '-write_xing', '0', '-id3v2_version', '0', str(folder / 'real.mp3')], check=True)
        data = (folder / 'real.mp3').read_bytes()
        real_frames = align.mp3_frames(data)
        fragment = folder / 'fragment.bin'
        fragment.write_bytes(data[real_frames[200][0] + 37:real_frames[400][0] + 19])
        whole, diagnostic = align.envelope(folder / 'real.mp3')
        part, fragment_diagnostic = align.envelope(fragment)
        assert diagnostic['exit_code'] == 0 and diagnostic['error_bytes'] == 0
        match = align.correlate(whole, part)
        assert match['status'] == 'candidate local match', match
        assert abs(match['a_seconds_for_sample_after_edge_trim'] - (real_frames[201][1] + 0.5)) < 0.2
        print('PASS: real MP3 fragment with incomplete edges decodes and locates A within 200 ms')
        fragment.write_bytes(data[real_frames[200][0] + 37:real_frames[304][0] + 19])
        part, fragment_diagnostic = align.envelope(fragment)
        assert len(part) == 27, len(part)
        match = align.correlate(whole, part)
        assert match['compared_seconds'] == 1.7
        assert match['minimum_correlation'] == 0.95 and match['minimum_margin'] == 0.15
        if match['status'] == 'candidate local match':
            assert abs(match['a_seconds_for_sample_after_edge_trim'] - (real_frames[201][1] + 0.5)) < 0.2
        print('PASS: real 2.7-second MP3 fragment reaches short-fragment search: ' + match['status'])
    else:
        print('SKIPPED: real MP3 fragment check requires FFmpeg')

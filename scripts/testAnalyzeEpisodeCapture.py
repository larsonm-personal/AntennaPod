import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
from unittest import mock

import analyzeEpisodeCapture as analyze


def tag(value):
    body = b'\x03' + value
    frame = b'TLEN' + len(body).to_bytes(4, 'big') + b'\x00\x00' + body
    size = len(frame)
    header = bytes((size >> 21 & 127, size >> 14 & 127, size >> 7 & 127, size & 127))
    return b'ID3\x03\x00\x00' + header + frame


with tempfile.TemporaryDirectory() as directory:
    folder = pathlib.Path(directory)
    mp3 = folder / 'reconstructed.mp3'
    for value, expected in ((b'1234', 1234), (b'', None), (b'unknown', None)):
        mp3.write_bytes(tag(value))
        result = analyze.id3(mp3)
        assert result['TLEN'][0]['milliseconds'] == expected
        assert result['TLEN'][0]['raw_hex'] == (b'\x03' + value).hex()
    print('PASS: numeric, empty and non-numeric TLEN with raw bytes')
    for name in ('Xing', 'Info', 'VBRI'):
        frame = b'\xff\xfb\x90\x00' + b'\x00' * 32 + name.encode() + b'\x00' * 377
        assert analyze.seek_headers(frame, 100)[name] == 136
    print('PASS: Xing, Info and VBRI at expected header offsets')
    xml = folder / 'feed.xml' 
    xml.write_text('<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><item>'
                   '<enclosure url="https://example.invalid/audio"/><itunes:duration>62:03</itunes:duration>'
                   '</item></channel></rss>', encoding='utf-8')
    manifest = {'source_route': {'url_sha256': hashlib.sha256(b'https://example.invalid/audio').hexdigest()},
                'requests': [], 'outcome': 'fixture'}
    assert analyze.feed_duration(xml, manifest)['itunes_duration_seconds'] == 3723
    assert 'not measured' in analyze.feed_duration(None, manifest)['status']
    manifest['source_route']['url_sha256'] = 'wrong'
    assert 'not measured' in analyze.feed_duration(xml, manifest)['status']
    print('PASS: local RSS match, absent RSS and mismatched enclosure')
    with mock.patch.object(analyze.shutil, 'which', return_value=None):
        assert 'not measured' in analyze.audio_report(mp3, False)['audio_measurements']
    print('PASS: missing audio tool reported without invented duration')
    if shutil.which('ffmpeg') and shutil.which('ffprobe'):
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2',
                        '-metadata', 'TLEN=1234', '-id3v2_version', '3', '-y', str(mp3)], check=True)
        result = analyze.audio_report(mp3, True)
        assert result['id3']['TLEN'][0]['milliseconds'] == 1234
        assert result['encoded_packets']['count'] > 0
        assert 1.9 < result['encoded_packets']['duration_seconds'] < 2.2
        assert result['decoded']['exit_code'] == 0 and result['decoded']['error_output_bytes'] == 0
        assert abs(result['decoded']['seconds'] - 2) < 0.001
        assert any(result['seek_headers'][name] is not None for name in ('Xing', 'Info'))
        (folder / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
        assert analyze.analyze(folder, None, False)['audio']['sha256'] == result['sha256']
        manifest['feed'] = {'itunes_duration_seconds': 3}
        (folder / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
        output = folder / 'comparison.json'
        sys.argv = ['analyze', str(folder), str(folder), '--decode', '--output', str(output)]
        assert analyze.main() == 0
        comparison = json.loads(output.read_text())
        assert comparison['byte_identical_candidates'][0]['equal'] is True
        assert abs(comparison['captures'][0]['decoded_minus_feed_seconds'] + 1) < 0.001
        assert abs(comparison['captures'][0]['audio']['id3']['TLEN'][0]['decoded_minus_TLEN_seconds'] - 0.766) < 0.001
        no_xing = folder / 'without-header.mp3'
        subprocess.run(['ffmpeg', '-v', 'error', '-i', str(mp3), '-c', 'copy', '-write_xing', '0',
                        str(no_xing)], check=True)
        result = analyze.audio_report(no_xing, False)
        assert all(result['seek_headers'][name] is None for name in ('Xing', 'Info', 'VBRI'))
        print('PASS: generated MP3 packet/decode duration, seek headers, header absence and report fingerprint')
    else:
        print('SKIPPED: generated audio checks require ffmpeg and ffprobe')

import argparse
import array
import math
import pathlib
import subprocess
import shutil
import sys
import tempfile
import wave

RATE = 44100
DURATION = 120
EXTRA_AD_SECONDS = 2299 * 1152 / RATE
parser = argparse.ArgumentParser(description="Generate the local playback test fixtures")
parser.add_argument("output", type=pathlib.Path)
args = parser.parse_args()
if not shutil.which("ffmpeg"):
    parser.error("FFmpeg with libmp3lame is required on PATH; no tools are installed automatically.")
root = args.output
root.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as temporary:
    for name, ad_seconds, low, high in (
            ('tagged-a', 2 + EXTRA_AD_SECONDS, 500, 2000),
            ('tagged-b', 2, 3500, 5000)):
        slope = (high - low) / DURATION
        pcm = array.array('h')
        for sample in range(round((DURATION + ad_seconds) * RATE)):
            time = sample / RATE
            if 5 <= time < 5 + ad_seconds:
                phase = 2 * math.pi * 1200 * (time - 5)
            else:
                content_time = time if time < 5 else time - ad_seconds
                phase = 2 * math.pi * (low * content_time + slope * content_time ** 2 / 2)
            pcm.append(round(16000 * math.sin(phase)))
        if sys.byteorder != 'little':
            pcm.byteswap()
        source = pathlib.Path(temporary) / 'episode.wav'
        with wave.open(str(source), 'wb') as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(RATE)
            output.writeframes(pcm.tobytes())
        subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-i', str(source), '-codec:a', 'libmp3lame',
                        '-b:a', '192k', '-write_xing', '0', '-id3v2_version', '0',
                        str(root / f'{name}.mp3')], check=True)

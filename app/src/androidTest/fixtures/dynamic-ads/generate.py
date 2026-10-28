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
SLOPE = (10000 - 20) / DURATION
parser = argparse.ArgumentParser(description="Generate the local playback test fixtures")
parser.add_argument("output", type=pathlib.Path)
args = parser.parse_args()
if not shutil.which("ffmpeg"):
    parser.error("FFmpeg with libmp3lame is required on PATH; no tools are installed automatically.")
root = args.output
root.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as temporary:
    for name, ad_seconds, closing_seconds in (('resolved-a', 2 + EXTRA_AD_SECONDS, 0),
                                            ('resolved-b', 2, 0),
                                            ('resolved-equal-length', 2, EXTRA_AD_SECONDS)):
        pcm = array.array('h')
        for sample in range(round((DURATION + ad_seconds + closing_seconds) * RATE)):
            time = sample / RATE
            if time >= DURATION + ad_seconds:
                phase = 2 * math.pi * 1200 * (time - DURATION - ad_seconds)
            elif 5 <= time < 5 + ad_seconds:
                phase = 2 * math.pi * 1200 * (time - 5)
            else:
                content_time = time if time < 5 else time - ad_seconds
                phase = 2 * math.pi * (20 * content_time + SLOPE * content_time ** 2 / 2)
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

if (root / 'resolved-a.mp3').stat().st_size != (root / 'resolved-equal-length.mp3').stat().st_size:
    raise RuntimeError("Equal-length fixture differs in encoded length from A")

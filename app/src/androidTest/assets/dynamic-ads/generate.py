import array
import math
import pathlib
import subprocess
import tempfile
import wave

RATE = 44100
DURATION = 120
SLOPE = (10000 - 20) / DURATION
root = pathlib.Path(__file__).resolve().parent
with tempfile.TemporaryDirectory() as temporary:
    for ad_seconds in (2, 6):
        pcm = array.array('h')
        for sample in range((DURATION + ad_seconds) * RATE):
            time = sample / RATE
            if 5 <= time < 5 + ad_seconds:
                phase = 2 * math.pi * 1200 * (time - 5)
            else:
                content_time = time if time < 5 else time - ad_seconds
                phase = 2 * math.pi * (20 * content_time + SLOPE * content_time ** 2 / 2)
            pcm.append(round(16000 * math.sin(phase)))
        source = pathlib.Path(temporary) / 'episode.wav'
        with wave.open(str(source), 'wb') as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(RATE)
            output.writeframes(pcm.tobytes())
        subprocess.run(['ffmpeg', '-y', '-i', str(source), '-codec:a', 'libmp3lame',
                        '-b:a', '128k', '-write_xing', '0', '-id3v2_version', '0',
                        str(root / f'ad-{ad_seconds}s.mp3')], check=True)

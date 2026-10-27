# Dynamic-ad reconnect demo

`DynamicAdPlaybackTest` uses the app's `ExoPlayerUtils.buildPlayer` networking,
cache and playback configuration, with a forwarding wrapper around its real
audio sink. It does not exercise the playback service UI or Bluetooth resume.

The fixtures contain the same continuous 120-second, 20–10000 Hz linear program
sweep. A 1200 Hz ad is inserted at program second 5, lasting 2 or 6 seconds.
`python3 generate.py` regenerates both mono 44.1 kHz, 128 kbit/s MP3s using FFmpeg
with libmp3lame. No stepped tones or compressed-byte matching are used.

The HTTPBin dynamic-ad mode interrupts the initial response after 320000 bytes
(about 20 seconds). The reconnect serves either the original file or the longer-ad
variant at the same URL. HEAD does not advance the variant. Bounded byte ranges
have matching Content-Length and Content-Range; the initial short body deliberately
simulates a connection interruption. No validators are advertised.

The test estimates frequency from decoded PCM accepted by the normal sink, then
checks only windows behind its consumed position. Renderer-time offsets are removed;
an intervening PCM flush fails the test. Playback is 1x with silence skipping off.
The first second and original ad boundaries are excluded, with 250 ms content-time
tolerance for codec delay and frequency estimation. The sweep's later crossing of
the ad frequency is not excluded. This observes virtual audio consumption, not a
microphone recording. It checks the first 27 seconds, not end-of-file truncation.

Build with `./gradlew :app:assembleDebug` and
`./gradlew :app:assemblePlayDebugAndroidTest -PtestBuildType=debug`, install the Play
debug app and test APKs, then run:

```sh
adb shell am instrument -w -r -e class de.test.antennapod.playback.DynamicAdPlaybackTest de.danoeh.antennapod.core.tests/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s DynamicAdPlaybackTest:I HTTPBin:I
```

On API 30, the stable control passes (maximum error about 13 ms); the changed-ad
continuity assertion fails (about 4.013 seconds repeated). The failing assertion is
intentional in this demo branch. This demonstrates one simulated mechanism, not the
proven cause of every reported skip. There is no playback bug fix in this change.

## Generating the fixtures

Android instrumentation builds require Python 3 and FFmpeg with the libmp3lame
encoder on PATH. They install neither tool. Ordinary app builds do not generate
these fixtures. Set `-PfixturePython=python` (or an executable path) if Python 3
is not named `python3` on your machine.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assemblePlayDebugAndroidTest
```

The generator was checked with Python 3.12.14 and FFmpeg 7.1.5/libmp3lame. Two clean
generations must produce identical bytes with the same tools; encoder versions
may change the MP3 bytes. After changing tools, regenerate with
`./gradlew :app:generatePlaybackFixtures --rerun-tasks`. Generated files belong
only under the ignored build directory, never in Git.

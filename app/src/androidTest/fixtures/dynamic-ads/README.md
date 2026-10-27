# Playback across resolved URLs

The server redirects an episode URL to A or B. Each resolved URL keeps its bytes.
The fixtures share a continuous program sweep, with a two-second ad in A and a
six-second ad in B at program second five. The Android test build generates them under `app/build/generated/playbackFixtures`.

`ResolvedUrlPlaybackTest` plays A to 17 seconds, releases the player and cache,
then restores that timestamp after a fresh resolution. The controls keep A or
play a completed local download. The two changed-resolution cases retain or delete
the disk cache. A forwarding audio sink estimates the sweep's program position
from decoded PCM and checks only windows behind the real sink's consumed position.
It rejects a flush after PCM, and allows 250 ms of estimation error.

With retained A spans, resumed audio follows A before jumping backward into B.
With an empty cache, it follows B from the start, already about four seconds behind
A's program position. Both strict continuity assertions fail; the stable and local
file controls pass. Thus the mismatch alone does not identify cache mixing.

`Media3ServiceRestartTest` separately exercises the Android service through a
MediaController, including database position saving and playback resumption. It
checks routing and restored media time, not decoded service audio. It does not
simulate process death or a Wi-Fi/cellular handoff. Both service cases passed on
an API 30 AOSP emulator with Bluetooth disabled.

These are synthetic layout changes, not a replay of the reported incident. The
observed alternate file was shorter; its decoded program alignment is unknown.
There is no production fix here.

Build the debug app and Android test APK, install both, and run each class using
AndroidJUnitRunner. The changed-resolution continuity failures are intentional
in this review branch; setup timeouts are not successful reproductions.

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

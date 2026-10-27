# Playback across resolved URLs

`ResolvedEpisodeServer` starts with the original endpoint resolving to A, waits for
an explicit signal from the test, then switches fresh resolutions to B. A and B keep
their own bytes throughout. There is no rotation timer. Response throttling is a
separate setup action: after 512,000 bytes of each GET response, at most 1,024 bytes are sent per 200 ms.
Releasing it continues the body, without a manufactured EOF.

The fixtures contain the same continuous frequency sweep, with a synthetic segment
at program second five. B has two seconds there; A has an additional 2,299 MP3 frames
(60.055510204 seconds). The files use 192 kbps, 44.1 kHz MPEG-1 layer III. Placement
and a single shortened segment are assumptions, not recovered server behavior.

The first production player seeks to 70 seconds, after the synthetic segment, then
consumes 17 seconds before cancellation. The recreated player resumes at 87 seconds.
This avoids playing a minute of setup audio while retaining real cache population.
The test verifies every saved span against A and measures the contiguous cached range
containing the resumed position; the initial seek may leave an earlier gap. It walks
MP3 frames to locate the actual boundary and observes consumed audio on both sides.
A missing or complete cache still permits coherent playback. Requests and ranges are
recorded rather than prescribed.

The content assertion accepts a coherent A or B representation. A-before/B-after
windows expose the mixed baseline. The empty-cache comparison checks coherent B
separately: its program position is about 60.0555 seconds ahead of A at the restored
media timestamp. That displacement alone does not prove cache mixing. Stable A and
completed local downloads are controls.

The observer uses Media3's public audio-output message with the unchanged production
player. It forwards to the normal AudioTrack output and reads only the bytes each
write actually accepts, including partial writes. Zero crossings estimate the
sweep frequency and therefore program position. Accepted frame counts plus the
fixed seek start give media time at 1x speed; the real output-relative playback head
filters out unconsumed windows. A flush, release or replacement after PCM invalidates
the observation. Neither an internal renderer offset nor player-position logs form
the audio oracle.

`Media3ServiceRestartTest` separately checks actual service teardown, saved position
and playback resumption. It waits for main-thread shutdown and pending database
writes before reading the saved position. Routing is logged. It does not observe consumed service
audio or simulate process death or a Wi-Fi/cellular handoff. The short-pause check
requires the production 800 ms rewind within its documented time interval.

The measured B was 1,441,331 bytes shorter. At two sampled interior locations, three
unique encoded blocks each matched A exactly 1,441,331 bytes later, or 2,299 frames
later on A's MP3 frame clock. The synthetic pair has that frame-count difference and
is 1,441,332 bytes apart; one byte of MP3 padding differs from the observed byte delta.
The real edit location, full B timeline and ending remain unknown. Sparse matches do
not establish a single deletion, its cause, or the user's actual playback transition.
There is no production fix here.

## Build and run

Instrumentation builds require Python 3 and FFmpeg with libmp3lame on PATH; no tools
are installed automatically. Use `-PfixturePython=python` or an executable path if
Python 3 is not named `python3`. Ordinary app builds do not generate these fixtures.
The generator was checked with Python 3.12.14 and FFmpeg 7.1.5: two clean generations
produced identical bytes. Encoder versions may change the bytes. After changing
tools, run `./gradlew :app:generatePlaybackFixtures --rerun-tasks`.
MP3s are generated under the ignored `app/build/generated/playbackFixtures` directory.

```sh
./gradlew :app:assembleDebug
./gradlew :app:assemblePlayDebugAndroidTest
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk
adb shell am instrument -w -r -e class de.test.antennapod.playback.ResolvedUrlPlaybackTest de.danoeh.antennapod.core.tests/androidx.test.runner.AndroidJUnitRunner
```

Use `Media3ServiceRestartTest` or `ResolvedEpisodeServerTest` in the same runner
command for the lifecycle and HTTP checks. The baseline mixed-representation case
should fail at its content assertion. Setup, coverage, clock or cleanup failures
are not successful reproductions. The test is not yet frozen for patch evaluation.

# A passage disappears after the stream changes

An episode is partially cached from edition A. After the playback service restarts,
the enclosure URL resolves to edition B, which has a shorter inserted ad. Cached
byte offsets can now join different program passages. Three ordinary 10-second
rewinds can replay that join without recovering the missing passage.

`ShortRewindTest` checks program seconds 75–77 in consumed audio:

| Case | Expected |
|---|---|
| Partial A, then fresh B, restart and rewind | Passage heard from A or B |
| Partial A, then unchanged A, restart and rewind | Passage heard from A |
| Coherent B and rewind | Passage heard from B |

The first case fails on the unfixed application. The other two are healthy
controls. Nine additional checks cover the audio observer, passage matcher, and
HTTP fixture. Setup failures and failed controls make the result inconclusive;
they are not reported as a reproduced hazard.
The missing-passage assertion also requires error-free playback beyond the passage.

The **Stream change reproduction** PR check runs all 12 tests three times on one
API 30 debug emulator, without retries. Its job summary shows the three scenarios
and the failed assertion's measured program transitions. A reproduced hazard
keeps the check red. Full instrumentation and device logs are in `playback-repro`.
The workflow can also be started manually.

To run locally, install JDK 21, the Android SDK, Python 3, and FFmpeg with libmp3lame,
then start a dedicated emulator and select it with `ANDROID_SERIAL`:

```sh
python3 app/src/androidTest/fixtures/dynamic-ads/generate.py app/build/generated/playbackFixtures/dynamic-ads
./gradlew :app:assembleDebug
./gradlew :app:assemblePlayDebugAndroidTest
python3 .github/workflows/runPlaybackRepro.py
```

Fixture generation is explicit; ordinary instrumentation builds do not require
Python or FFmpeg. The reproduction clears the debug app's database and cache.

Both synthetic MP3s encode the same 120 seconds of program positions in separate
frequency bands. A contains about 60 seconds more advertising than B. The real
service, controller, decoder, and disk cache are used. The observer counts only
PCM behind the audio device's playback head and accepts recovery into either
edition. This demonstrates the cache/stream mechanism; it does not reconstruct
the exact phone incident, a network handoff, or uninterrupted playback.

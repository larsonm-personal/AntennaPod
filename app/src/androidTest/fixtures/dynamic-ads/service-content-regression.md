# Named passages after service restart

The service plays A, stops, and restarts after fresh resolutions select B. A and B
remain independently accessible and immutable. Playback uses the real service,
controller and production cache. The test neither inspects cache keys nor requires
an implementation to reuse or replace a particular source.

The oracle asks whether program passages at 75, 105 and 90 seconds remain reachable.
For each passage it tries the two known media timestamps, B then A, in that fixed
order, stopping once the passage has actually played. It repeats the complete list once, requiring every passage on that fixed
second sweep. The first sweep can provoke a legitimate edition replacement; its
unavailable or incoherent passage candidates are retained in the trace rather than preventing the replay.
A timestamp with less than six seconds
remaining in the currently reported duration is logged and skipped. This matters
because A's timestamp for these passages is beyond the end of B. End-of-stream or
an explicit error supplies no evidence of recovery. At least one candidate must
actually play each named passage during the verification sweep. A successful
candidate does not provoke an unnecessary seek in the other edition's coordinates.

Each observation retains its existing 60-second budget even if an error is reported,
so an asynchronous service recovery can complete. The seek marker follows the next
public seek discontinuity after the test command and a playback-thread barrier; it
does not require the resulting position to equal the request if recovery adjusts it.

Each candidate uses the calibrated public playback-thread seek marker and the
same predetermined one-second PCM segment: four quarter-second windows starting
eight windows after the marker. A recovered segment must identify program coordinates and progress normally;
its edition labels and output epochs may change. Matching the passage means its program coordinates lie within
the requested six-second interval. No global edition-switch count is imposed:
different user seeks may legitimately select different complete editions. All
observed windows are logged; the initial two seconds and audio outside each fixed
sample are not assertions of continuous integrity. This is bounded sampled
accessibility, not proof that every passage or every possible timestamp works.

Two controls run the identical passage plan against coherent A and coherent B.
The existing four service-observer controls retain their assertions; their shared
seek marker now allows an adjusted seek position. The eleven PCM-observer controls
and consumption bookkeeping remain unchanged. The frozen direct-player regression remains a separate mechanism test.

The live evidence established stable independently accessible URLs, a shorter B,
and unique encoded correspondence at two interior samples displaced by 2299 MP3
frames (60.055510204 seconds). It did not locate a deletion, reconstruct the full B
timeline, or establish the user's service lifecycle. The single shortened interval,
its placement, throttling to stage partial streaming, and restart are synthetic.
Tagged frequency bands make source and program coordinates observable and differ
throughout the program; they are not a claim about identical live program bytes.
The original shared-program fixtures are retained unchanged.

The contract and target plan are defined before testing a production fix. A baseline
pass is a legitimate result and does not justify adjusting the plan to force failure.

CI runs one nineteen-test selection per job: seven service cases, one passage-oracle control and the eleven
existing observer controls. Three debug jobs and three release jobs are independent,
with fail-fast disabled and no retries. Each retains its runner output, logcat and
command status under `service-audio-<variant>-<repetition>`. The broader inherited
Checks workflow is separate and still contains known failing mechanism tests.

## Local baseline

On 2026-10-29, the complete nineteen-test selection finished on the API 30 software
emulator in both variants: eighteen controls passed and only the new restart case
failed, at `Program passage 75 unavailable on the fixed replay`. Debug took 774.217
seconds; release took 622.633 seconds. Installed app and test APK hashes were checked
against the built files. Build, checkstyle, lint and runner fault controls passed.

At the B timestamp (77 seconds), the fixed observation contained A program
coordinates 17.065–17.815 seconds. That alone is valid for coherent A; its alternative
timestamp is 137.056 seconds. Here the alternative produced a source error, and the
fixed replay recovered neither candidate. This demonstrates failure of the bounded
passage-recovery plan, not absence of the passage at every conceivable timestamp.

## Hosted qualification is incomplete

Run 40821480410 at `82ec629bb` stopped all six restart cases at the duration
precondition, before the intended recovery assertion. Debug repetition 2 also timed
out preparing the replacement episode; release repetition 1 timed out in the
identical-content control. These are harness/readiness failures, not qualified
content results. The next diagnostic snapshot logs controller and player state
without changing assertions, targets, tolerances or deadlines, and prints a bounded
excerpt beside the full CI artifact. The production-fix comparison remains paused.

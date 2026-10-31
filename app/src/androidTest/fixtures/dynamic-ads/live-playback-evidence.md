# Live playback and rewind evidence, 2026-10-31

Three supplied diagnostic traces describe a reported backward repeat, a later
forward skip, and an attempt to recover the latter by rewinding. The last trace
is the strongest practical evidence: the player accepted the rewind and replayed
across the suspected join, while the listener still reported missing content.
It does not independently identify the omitted audio or establish that the
original server could no longer supply it.

This is a sanitized analysis. No episode identity, private URL, token, raw log or
audio is included. Trace 1 is the first listening report; trace 2 is the later
forward-skip report; trace 3 is its rewind follow-up. Line references refer to
those private originals. Times below are the recorded local times, UTC-07:00.
The reported version is 3.13.0-beta1; the exact installed commit is not established.

## Byte boundaries before the audible reports

| Event | Logged evidence | Interpretation |
| --- | --- | --- |
| Trace 1, 11:19:38–12:05:30 | Request 9 began at byte 32,073,705, reported total 130,512,915, and closed after 5,244,134 bytes. | Its exact next byte is 37,317,839. |
| Trace 1, 13:33:51 | Request 12 opened a different resolved URL at byte 37,317,839; the new total was 131,954,246. | The same byte coordinate continued into a larger response version. |
| Trace 1, around 13:39 | Playback positions advanced monotonically while the listener reported a backward repeat. | An audible repeat need not produce a player-timeline seek. |
| Trace 2, 14:18:12–14:38:38 | Request 18 began at byte 52,940,525, reported total 131,954,246, and closed after 7,112,621 bytes. | Its exact next byte is 60,053,146. |
| Trace 2, 14:52:13 | Request 21 opened a different resolved URL at byte 60,053,146; its total was 130,512,915. | This time the same byte coordinate continued into a smaller response version. |

The additions are exact: `32,073,705 + 5,244,134 = 37,317,839` and
`52,940,525 + 7,112,621 = 60,053,146`. Both total changes are 1,441,331 bytes.
The totals come from the denominator of Content-Range, not a partial response's
Content-Length. For example, request 21 served through byte 120,373,388 while
reporting a total of 130,512,915; the range end was not the resource end.

Sources: trace 1 lines 5175, 5233, 5252, 5429, 5496, 5554 and 5573;
trace 2 lines 5561, 5619, 5638, 5875, 5942, 6000 and 6019.
Request numbers recur in retained historical traces; matching the date and
stream is necessary before joining records.

## When playback reached the new bytes

Before the first continuation, the old buffered end was 25:39.892. Interpolating
nearby playback-position samples places its crossing around 13:38:44, near the
reported repeat. This is an inferred decoder/content boundary, not a recorded
audio discontinuity. A later read failure at 13:44:23 is a separate event.
The user's subsequent seek to the end reached STATE_ENDED at 13:54:02, with
position 5,483,105 ms against duration 5,483,101 ms. That is normal player-end
evidence, not proof that every preceding passage was played.

For the second continuation, the old buffered end was 41:27.196. Trace 2 records
41:24.510 at 14:58:26.893 and 41:29.515 at 14:58:31.894. Interpolation places the
crossing around 14:58:29.58. The listener reported a forward skip and paused
around 14:58:47 at 41:44.911, about 18 seconds beyond the inferred join. The
timeline itself did not jump there. This trace contains no subsequent recovery
seek, so it alone cannot establish whether rewinding would recover the passage.

Sources: trace 1 lines 5428, 5638–5644, 5713–5719 and 6045–6050;
trace 2 lines 5873, 6103–6110 and current-log position samples.
The earlier STATE_ENDED event in retained history belongs to the first session,
not to the later forward skip.

## The rewind and replay

Trace 3 records five explicit ten-second backward seeks:

| Local time | Position before -> after |
| --- | --- |
| 15:07:34.371 | 41:40.341 -> 41:30.341 |
| 15:07:34.533 | 41:30.371 -> 41:20.371 |
| 15:07:34.682 | 41:20.392 -> 41:10.392 |
| 15:07:35.253 | 41:10.719 -> 41:00.719 |
| 15:07:35.379 | 41:00.748 -> 40:50.748 |

The final target was 36.448 seconds before the inferred 41:27.196 join. Playback
then advanced through 41:23.250 at 15:08:08.164 and 41:28.249 at 15:08:13.166.
The inferred crossing was approximately 15:08:12.11. The pause gesture was at
15:08:15.960, with a paused position of 41:31.077: 3.881 seconds beyond the join.

The buffer end remained 45:56.519 throughout that replay. The current trace
records no new stream SOURCE, OPEN, RESOLVE, RANGE, CACHE or CLOSE event, or
playback error, around it. Decoder flushes occurred for the seeks, but there is
no evidence of a fresh stream version replacing the queued/buffered content.
A later READ for request 21 at 15:09:03 continued the existing response. This
supports replay of the already assembled stream rather than a fresh resolution.
It is not a claim that every possible network operation is logged.

The listener's report that the section remained missing is stronger evidence
than the original skip report alone: the rewind succeeded, crossed the suspected
join again, and did not visibly repair the stream. Without recorded audio or
matching source samples, the trace cannot prove the exact words or duration
omitted, distinguish every possible audio defect, or prove permanent absence
from the origin.

Sources: trace 3 lines 461, 483, 504, 532, 554, 589–642, 647–688 and 894.
Its current logcat ends at line 2021; retained historical records follow.
The 800 ms adjustment on resume at 15:08:37 (line 802) starts at 41:30.277,
already beyond the inferred join; it is not another full replay across it.

## What the tests cover, and what they do not

`ResolutionChangeTest` covers both length directions using real service restart,
production caching and immutable resolved endpoints. Both directions replay the
same fixed program targets, 75, 105 and 90 seconds. Each target tries the two
known edition-specific timestamps; every target must be reachable on the second
sweep. Coherent original reuse and replacement are both valid. Neither an error
nor an empty playlist counts as recovery.

These checks are relevant to the reported failure: in the synthetic shorter
replacement, target 75 falls inside the large program interval that the mixed
stream can skip. The reverse case also exposes a stale-duration/wrong-content
combination. Locally, both unfixed directions fail passage assertions, while the
unchanged proposed fix passes them in debug; the new reverse case also fails
unfixed and passes fixed in release. These are synthetic reproductions of the
mechanism, not replays of the private episode.

There is a material coverage gap. The test seeks to fixed candidate locations
and checks a one-second sample beginning two seconds after each seek marker.
It does not perform the user's five rewind taps and continuously verify content
through the original buffered join. The initial post-restart observation is not
a continuous-content assertion. A shorter omitted passage outside the fixed
targets or sample windows could escape this oracle. Passing all current passage
checks therefore does not prove that every local rewind across a join is safe.

The smallest useful follow-up would separately replay a predetermined program
interval spanning a staged join, then require a named passage inside that interval
to be heard after rewind. It must permit a legitimate fresh source and approximate
resume shift, retain consumed-audio evidence, and avoid choosing its target after
seeing a failure. This remains proposed coverage, not an implemented test.

The 60.055510204-second fixture displacement comes from the earlier independent
encoded-audio alignment captures, not from these logs' byte-length difference.
Its single changed interval, placement and edition-tagged sweeps remain synthetic.

## What the proposed fix changes

The separate fix reuses the cached stream's recorded resolved URL while useful
cached bytes remain. Before exposing new payload bytes, its validator checks
the actual GET's known total and resolved identity, with bounded overlap checks
where applicable. A detected incompatibility causes bounded service-owned
recovery with a new cache generation and a fresh media source/extractor. The
old decoded/buffered source is replaced, rather than retrying an old byte offset
against a new version. Approximate timestamp resume can still repeat or skip
context once; it must leave a coherent version that the listener can seek.

The logged total changes are detectable without an ETag. That supports preventing
this kind of new splice, but is not a replay of the exact device incident or a
promise to repair bytes already consumed. The evidence does not reveal a new
production defect in the proposed fix. The gap above warrants additional
independent replay coverage before claiming complete protection against every
short missing passage; it does not justify changing the fix to fit these logs.

# Observed server behavior and the next local characterization

This is an evidence-collection specification, not a claim that dynamic ads caused the
reported skip. The existing four-second synthetic repetition test remains a separate
mechanism demonstration. It is not a validated model of the private provider.

| Status | Evidence or question |
| --- | --- |
| Observed in the supplied clean app trace | Initial HEAD 302 → HEAD 200; three GETs reuse the exact resolved URL. Later Range requests follow explicit seeks. No network-error retry was captured. |
| Observed in the supplied overnight HEAD journal | 32 checks: 30 successful final 200 responses and two timeouts. The original URL and resolved host/path stayed constant; resolved query values changed. |
| Observed in those successful HEAD responses | Declared lengths: 127,079,742 bytes in 23 checks and 125,638,411 bytes in seven. Two values of a query parameter tracked those two lengths in this finite sample. Other session/request/time values changed routinely. No ETag or Last-Modified was supplied. |
| Previously reported capture result | The earlier network-switch captures of the longer representation were byte-identical; the listener confirmed a complete ending. This does not characterize the shorter representation or subsequent provider state. |
| Unknown | Whether each exact resolved URL pins audio bytes, changes internally over time, expires, redirects again, or behaves differently for HEAD versus GET. |
| Unknown | Whether the two lengths correspond to differing ad arrangements, underlying program changes, metadata differences or some other representation change. The correlated query value's meaning is not established. |
| Hypothesis to test | Fresh resolution selects a new representation while an older resolved URL remains stable. |
| Alternative hypotheses to test | Bytes behind a pinned URL change; URL expiry changes behavior; metadata changes without sampled-byte changes; sampled bytes change without metadata changes. |

No private URL, token value, audio or raw log belongs in this repository.

## Run the new bounded test

Windows Command Prompt, from the existing clone:

```bat
git pull --ff-only
py -3 scripts/characterizeEpisodeServer.py --trace-log "%USERPROFILE%\Downloads\full-logs (1).txt" --observations "%USERPROFILE%\Downloads\route-watch-1\events.jsonl" --output "%USERPROFILE%\Downloads\server-characterization-1"
```

Adjust local filenames. Alternatively use `--prompt-url` instead of `--trace-log` for
hidden **episode audio URL** entry. `--observations` is optional: without it, only
fresh HEAD length changes trigger early comparison. When supplied, observations must
refer to the same original URL. The tool identifies query keys with one distinct
value per observed length and distinct values across lengths, requiring at least two
observations of each length; volatile session keys
are not selected merely because each individual token happened only once. That is
an empirical correlation, not a declaration that a key identifies audio content.

The sequence is:

1. Resolve the original URL by HEAD. Preserve the exact final URL as the pinned URL.
2. From that URL, GET three diagnostic windows: the beginning, halfway byte offset,
   and three-quarter byte offset, each up to 65,536 bytes. Offsets derive from the
   baseline HEAD length and stay fixed throughout the run.
3. Every 15 minutes, resolve the original URL afresh by HEAD. Stop monitoring when its
   length or an empirically correlated key pattern changes, or after at most eight
   hours/up to 32 total baseline-plus-fresh resolutions. Routine token churn alone is
   not a trigger. Errors remain in the journal; unsuccessful polls do not replace the
   latest successful fresh resolution.
4. Recheck the **unchanged pinned URL** by HEAD. If successful, read its same three
   windows again. Also read those windows from the latest successful fresh resolution's
   exact URL, with no silent refresh or replacement. At the monitoring limit this may
   be the last successful observation, not a newly resolved URL; its record number and
   timestamp are explicit. If no later HEAD succeeds, that candidate remains baseline.
5. Compare SHA-256 only for validated responses covering the same absolute interval.
   Results separate baseline-vs-pinned, baseline-vs-fresh and pinned-vs-fresh.

These short, separately requested windows are **diagnostic sampling**, not an app
resume replay. No full stream, stitched MP3, additional overlap request, or decoded
content oracle is produced. Each window can encounter different server state; HEAD
and subsequent GET need not select the same representation. All actual redirects,
final URLs and timestamps are retained so these possibilities remain visible.

A coherent 206 must cover the exact requested interval, with consistent body length,
identity encoding and a valid total. Malformed ranges, short bodies, timeouts and
416 responses are retained but excluded from byte comparisons. A Range→200 response
is read only to the small sample cap and labeled an ignored-range prefix; it is never
compared as if it covered the requested offset. There is no padding or forced splice.
A failed pinned HEAD prevents pinned samples. A 404 may therefore leave those
comparisons unknown while the separately scheduled fresh candidate is sampled.
401, 403 and 429 stop the entire run immediately: no substitute URL, identity change,
automatic retry or expiry bypass. Transport failures are recorded without immediate
retries; later scheduled HEAD polls are distinct observations.

## Bounds and private output

Defaults are **96 HTTP request attempts including redirect hops**, **1 MB of response
body bytes read**, and three 64 KiB windows per phase (nine samples in the successful
case: about 0.56 MB). One extra byte per sample may be read to detect oversized
responses and counts toward the payload budget. Redirect/error bodies are closed
without capture. HEAD reads no body. This is an application-payload cap, not a limit
on TLS, HTTP headers, socket buffering, or bytes a noncompliant server sends.

`--hours` accepts greater than zero through 24; `--interval` is 60–3600 seconds;
`--window-bytes` is 1–262144; `--max-requests` is 1–256; `--payload-bytes` is 1–8388608;
`--timeout` is 1–60 seconds (default 20). MB means 1,048,576 bytes. A budget exhaustion
stops before further requests rather than silently raising the limit. The duration
bounds the monitoring phase; baseline/final samples and in-flight operations add
bounded work. Socket timeouts and elapsed-time checks constrain sample reads; these
are not an exact wall-clock deadline for OS DNS/proxy behavior. Redirect chains are
limited to five hops. Keep the PC awake; no network or power settings are changed.

The new private directory contains append-only, flushed `events.jsonl` and numbered
raw sample files. The journal includes pre-send request events (also useful if a check
is interrupted), URLs, headers/Locations, timing, declared lengths/ranges, body counts,
hashes, budgets, trigger reason and comparison results. Existing output directories
are never overwritten. Ctrl+C retains completed and partial evidence. Console messages
do not print URLs or tokens. The app's observed User-Agent/HEAD/GET header profile is
used consistently, but Python's HTTP/TLS/connection/proxy behavior is still different
from Android. There is no cookie jar or credential replay.

## How results refine the integration model

- Different fresh windows with stable pinned windows support a model separating fresh
  resolution from pinned URL use. They do not support forced mutation on every retry.
- Different bytes from the same requested pinned URL support investigating mutation or
  a changed redirect destination; consult actual final URLs and exact response ranges.
- Expiry/errors require an explicit expiry/error state in a future model, including the
  app's measured reaction. Do not replace them with an imagined successful new stream.
- Stable sampled bytes with changed metadata establish only sparse byte agreement.
  They do not prove complete representation identity, continuous program audio, or a
  complete ending. Content alignment requires a later, separately authorized capture.
- Unchanged HEAD metadata with changed bytes demonstrates why headers alone are not
  an audio identity oracle. Conversely, a metadata change alone is not a content skip.

Fixture modes cover each alternative independently: stable pinned data, fresh-only
switch, pinned mutation, mutation without metadata change, expiry/access denial,
ignored ranges, wrong/truncated ranges, timeouts and budgets. These are candidate
behaviors, not fitted probabilities or asserted real-server transitions. Session
stickiness, expiration lifetime, network dependence and stochastic transition rates
remain unknown until measured. No product fix or replacement integration mock is
included in this iteration.

Local validation: `py -3 scripts/testCharacterizeEpisodeServer.py`.

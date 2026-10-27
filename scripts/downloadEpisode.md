# Compare Wi-Fi control with a cellular-hotspot interruption

Use the existing diagnostic APK; no update is needed. Save its detailed log outside
Git, then run these in Windows Command Prompt in your clone (adjust the local log filename):

```bat
git pull --ff-only
py -3 scripts/downloadEpisode.py --trace-log "%USERPROFILE%\Downloads\full-logs (1).txt" --prompt-feed-url --network-test control --half --delay 60 --overlap --output "%USERPROFILE%\Downloads\wifi-control-feed.mp3"
py -3 scripts/downloadEpisode.py --trace-log "%USERPROFILE%\Downloads\full-logs (1).txt" --prompt-feed-url --network-test switch --half --delay 60 --overlap --output "%USERPROFILE%\Downloads\wifi-cell-feed.mp3"
```

At the hidden prompt, paste the **RSS subscription URL**. The episode audio URL is
read separately from the local diagnostic log. `--prompt-feed-url` adds one explicit
local RSS GET, saves its XML body as `feed.xml` plus response headers/routes/times,
and records matching `itunes:duration`. There is no feed fetch without this flag or
`--feed-url`; no feed address is guessed. An absent/ambiguous enclosure match stops
before audio requests and retains the RSS evidence. Invalid/missing duration stays
explicitly unmeasured. RSS is capped at 10 MB. New output names preserve earlier runs.

Start **each** command on Wi-Fi. The control pauses halfway, waits at least 60 seconds,
and asks you to stay on the same network before pressing Enter. For the second run,
wait for the prompt, connect the PC to your phone's **cellular** hotspot, then press
Enter. Disable the phone's Wi-Fi sharing and check Ethernet/VPN routes yourself;
the script neither changes nor verifies networks. Keep the control pause roughly as
long as the switch pause; actual pause times are recorded. Ctrl+C cancels and preserves
evidence. Run this pair once initially, rather than polling until ads change.

`--trace-log` reads the original episode audio URL **locally**, never uploads the log,
and refuses ambiguous logs containing multiple episode URLs. Alternatively use
`--prompt-url` for hidden local audio-URL entry, or the existing positional URL argument
(which can leave the URL in shell history). Supply an audio URL, not a subscription
RSS URL. Existing output directories are never overwritten.

The network modes use the observed diagnostic-app User-Agent `AntennaPod/3.13.0-beta1`,
HEAD with `Accept-Encoding: gzip`, and GET with `Accept-Encoding: identity` and
`Icy-MetaData: 1`. They resolve once by HEAD, pin its final URL, read half of the initial
GET length, close, pause, then request the remaining byte interval from that same URL
without If-Range. They do not re-resolve the original URL after switching networks.
The supplied clean log showed this URL reuse on seeks, not a failed network retry;
the halfway interruption is our experimental trigger, not an observed failure replay.
These modes stop on any HTTP 403 without trying another identity or route. Other
GET errors and transport failures are terminal too.

**The `.mp3.capture` folders are private:** manifests now retain exact URLs, redirect
Locations, duplicate response headers, outgoing urllib request headers, UTC times,
body byte counts/hashes, pause duration and capped error/redirect bodies. Keep both
folders and the original log outside Git; share only privately. Audio bodies are
bounded at 256 MiB, error/redirect evidence at 64 KiB (one additional byte is read
to detect truncation), and optional overlap at 128 KiB. Redirect responses are closed
after that bounded read rather than draining their remaining bodies.
There is one HEAD resolution, two audio requests and one optional overlap request per
run, plus bounded normal redirects. The original GET is intentionally cut halfway;
its unread remainder is not captured. A valid 206 creates an explicitly reconstructed
file; ignored Range (200), shorter totals and errors keep their existing distinct
handling described below. Overlap is a separate later request and may be variant C;
its comparisons do not prove what the actual resumed response contained before the join.

Python still differs from Android: HTTP/TLS implementation, connection reuse, proxy
settings and automatically added headers (including urllib's Connection: close) are
not identical. Host and urllib-prepared headers are recorded, not a wire capture.
There is no cookie jar or credential replay. Historical ads, app cache and expired
sessions cannot be restored. These files can expose a changed representation or be
decoded/listened to for a skip; successful capture alone is not a reproduction verdict.

Local fixture checks: `py -3 scripts/testDownloadEpisode.py`.


## Compare existing captures offline

No redownload is needed to analyze earlier captures. This command writes a new private
JSON comparison, including feed metadata when captured:

```bat
py -3 scripts/analyzeEpisodeCapture.py "%USERPROFILE%\Downloads\wifi-control-feed.mp3.capture" "%USERPROFILE%\Downloads\wifi-cell-feed.mp3.capture" --decode --output "%USERPROFILE%\Downloads\episode-comparison.json"
```

Python alone reports HTTP evidence, saved sizes/hashes, feed duration and raw/parsed
ID3v2.3/v2.4 TLEN and seek-header checks. Install/use `ffprobe` and `ffmpeg` on your local PATH for audio
measurements; no packages are installed automatically. Missing tools produce explicit
"not measured" fields. `--decode` can take minutes and processes only local files.
The analyzer forces the MP3 demuxer and file/pipe protocols, never fetching URLs.
For old captures, `--feed-snapshot "C:\path\feed.xml"` accepts an existing local RSS
snapshot; it matches the enclosure hash and never fetches the feed. An enclosure URL
can itself change over time; a failed exact match is not silently replaced by a title guess.

The comparison separates:

- RSS XML `itunes:duration` raw text and parsed seconds; this is **not an HTTP header**
  and is not the player's displayed duration.
- TLEN raw frame bytes, text and milliseconds, including encoding-only empty values.
  Unsupported ID3 versions/flags are reported rather than treated as absent TLEN.
- Xing/Info/VBRI signatures at expected offsets in the first plausible MPEG layer III
  frame; this is bounded header inspection, not validation of an entire seek table.
- `ffprobe` duration estimate, codec/sample rate/channels/bitrate, and summed demuxed
  packet durations/count/bytes. Packet durations have decimal rounding and are not a
  proof that every original MPEG frame survived. The bitrate is not a full CBR audit.
- With `--decode`, actual emitted PCM sample duration after mono 48 kHz conversion,
  decoder exit status/error excerpt, and differences from feed duration and TLEN.
  Decoder errors or a truncated input can still produce some samples; that duration
  must not be mistaken for a complete original program.
- Declared HTTP length/ranges, saved bytes and MB (1 MB = 1,048,576 bytes), recomputed
  SHA-256 hashes, UA, route/session URL hashes, validators and capture timing. Exact
  routes and all response headers remain in the private capture manifest.
- Byte equality between candidate files and the existing separate-overlap comparisons.
  Equal hashes prove those saved bytes match, not that earlier/later provider states match.

Program cross-correlation/alignment, missing words/end-content completeness, and actual
phone playback behavior remain **not measured**. Reconstructed files retain splice
provenance; a duration change alone does not prove a skip. Keep reports, RSS, logs and
audio private and outside Git. Existing report files are never overwritten.

Header inspection references the offsets used in
[FFmpeg's MP3 demuxer](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mp3dec.c).
Run synthetic checks with `py -3 scripts/testAnalyzeEpisodeCapture.py`.

## Legacy public RSS capture

Requires Python 3.8 or newer; no extra packages. In your existing Windows clone:

```powershell
git pull --ff-only
py -3 scripts/downloadEpisode.py
```

The default reads `https://audioboom.com/channels/5173020.rss`, selects exactly
**“What the hell is going on in Europe?” dated October 1, 2026**, and downloads its
single audio enclosure. Missing or ambiguous matches stop without downloading audio.
There is no fallback to the website download link used by the earlier script.

Output is `antennapod-europe-rss.mp3` and its `.json` sidecar in your Downloads
folder, outside Git. Your earlier `antennapod-europe.mp3` website capture is preserved.
Existing files are never overwritten; choose another path using `--output`.
An optional first argument supplies a direct public audio URL instead of RSS selection;
its metadata is explicitly labeled `direct_url`. On macOS/Linux use `python3`.

The ordinary one-shot mode fetches the feed and downloads audio once, following normal redirects.
There are no HEAD/range probes, retries, identity changes or uploads. HTTP 403/auth
errors stop the operation; incomplete files are removed. Metadata records
`source_kind`, title/date, response headers, byte count and redacted redirect routes.
Routes retain origins, a download-path category, and SHA-256 hashes of paths/full URLs,
not raw paths, query values or signed tokens. URL hashes identify requested URLs, not
content identity. Keep audio and metadata local; do not commit them.

This collects input for investigation; it does **not** reproduce AntennaPod playback.
Local HTTP fixtures validate RSS selection, mismatches/ambiguity, redirects, errors,
incomplete downloads and overwrite protection. Live Audioboom RSS access remains
unverified because cloud access was blocked. The legacy modes retain the Python User-Agent.

## Staged capture (opt-in)

```powershell
py -3 scripts/downloadEpisode.py --staged --delay 60 --overlap --output "$HOME/Downloads/antennapod-europe-staged-2.mp3"
```

This saves a private `Downloads\antennapod-europe-staged-2.mp3.capture` directory.
It resolves the RSS enclosure with HEAD (preserving HEAD through redirects), starts
one full GET at that HEAD-resolved URL, saves exactly 1 MiB, closes it, waits, and
requests `bytes=1048576-(initial GET length - 1)` from the same pinned URL.
The GET's length, not HEAD's, sets the retry bound. The script does not refresh
expired URLs or re-resolve RSS during the wait. `--prefix-bytes` changes the boundary;
`--delay` accepts 0–86400 seconds. Longer waits may matter, but no delay guarantees
ad rotation. Request identity stays constant; no proxy or identity changes are made.

The budget is one feed fetch, one HEAD resolution, two audio GETs, and, only with
`--overlap`, one diagnostic range spanning at most 128 KiB around the join, plus
normal redirects. Each body is capped at 256 MiB; the diagnostic body has its own
128 KiB cap. Like AntennaPod, a HEAD HTTP error response still supplies the final
redirected URL for the initial GET; its status and redirects remain in the manifest.
A GET HTTP error remains terminal. Network/transport failures and unresolved redirect
errors stop this tool; it does not model AntennaPod's IOException fallback to the
original URL. An initial GET without a usable length also stops the run.
The effective urllib User-Agent is recorded for each staged request and is unchanged.
AntennaPod uses `AntennaPod/<installed version>` instead; this is a client identity
difference, not evidence explaining a rejection. The new output name preserves
previous captures; an existing directory still stops the run before requests.

`prefix.bin`, `suffix.bin`, response hashes/headers and `manifest.json` are retained,
including partial evidence on failure. A coherent 206 permits `reconstructed.mp3`,
explicitly labeled as concatenated responses. Changed total length/validators are
recorded; a shorter response is never padded, and a longer variant is not silently
claimed complete. A 416 or mismatched range fails without reconstruction.
A 200 response to Range is saved separately as `restart-response.mp3`, never appended:
**server ignored Range; standalone response preserved, no app-equivalence inference**.
Media3 itself can skip the requested offset in such a 200 body and continue.

The optional overlap is a later diagnostic request and may encounter a third variant.
It is compared with the saved prefix/suffix, never spliced into the reconstruction.
Equal headers or matching overlap bytes do not prove identical audio everywhere.
Neither changed headers nor a constructed file is by itself a verdict of an audible skip.

This models a new load-error retry: Media3 1.10 resets its local ETag on each `load()`
invocation, retaining the consumed offset. It can send If-Range on reopenings within
one invocation, which this mode does not model. Python networking and an exact byte
cut are approximations, not an actual AntennaPod run. In legacy staged mode, signed URLs are held in memory
only; persisted routes are redacted/hashes. Keep the whole capture directory private.
Run local staged fixtures with `py -3 scripts/testDownloadEpisode.py`.


## Overnight route watch without downloading audio

In Windows Command Prompt:

```bat
git pull --ff-only
py -3 scripts/watchEpisodeRoute.py --trace-log "%USERPROFILE%\Downloads\full-logs (1).txt" --output "%USERPROFILE%\Downloads\route-watch-1"
```

Or replace `--trace-log ...` with `--prompt-url` for hidden entry of the **episode
audio URL**, not the subscription RSS. Keep the PC awake and connected; this script
does not change power or network settings. Ctrl+C stops it safely. Use a new output
directory for another run; existing evidence is never overwritten.

Defaults: eight hours, 15 minutes between completed checks, at most 32 checks.
`--hours` accepts greater than 0 through 24; `--interval` is seconds, 60 through 3600.
Each check starts at the same original episode URL and follows at most five redirects
(six HEAD requests), preserving HEAD throughout. It never sends GET, performs a range
probe, fetches RSS, reads an audio body, or falls back when HEAD is unsupported. It
uses the same fixed diagnostic User-Agent and HEAD headers as the capture tool, with
no cache-busting or identity rotation. This repeated resolution experiment differs
from the player's pinned URL during an existing load.

The private, append-only `events.jsonl` contains exact URLs, all observed redirect
headers/Locations, final status/URL, declared length, ETag, Last-Modified, outgoing
headers, UTC timestamps and elapsed time. Each completed event is flushed to disk.
Console output shows only status and names of changed fields, never secret URLs.
Authentication errors, unsupported HEAD, rate limits and other HTTP errors stop the
run; 429 Retry-After remains recorded, with no automatic retry. Transport errors are
recorded at the normal interval; three consecutive failures stop it. The socket
operation timeout is 20 seconds and redirects are bounded; an in-flight check can
finish after the requested duration. No scheduler/service installation is needed.

Traffic is limited to HEAD exchanges, headers and connection/TLS overhead, rather
than the MP3 stream. Data use depends on redirects, headers, proxy/TLS behavior and
server compliance; there is no promised byte total. HEAD responses may differ from
GET. New signed URLs/session tokens do not prove changed audio; stable routes or
validators do not prove the same content either. This monitor cannot detect an audible
skip without a later content capture. Keep the journal private and out of Git.

Local checks: `py -3 scripts/testWatchEpisodeRoute.py`.


For the next evidence-driven test of pinned versus freshly resolved URLs, see
[server characterization](serverCharacterization.md). It samples bounded, identical
byte windows and keeps candidate server behaviors separate from the synthetic skip test.

## Locate sparse B samples in an existing A (offline)

Use the full A already downloaded and the characterization directory containing
`events.jsonl` and its sample files. Nothing is uploaded or fetched:

```bat
py -3 scripts/alignEpisodeSamples.py "C:\path\existing-A.mp3" "C:\path\server-characterization-1" --decode --output "C:\path\sparse-alignment.json"
```

The helper first checks all three saved baseline A windows and their declared total
against the supplied file. A mismatch stops alignment. It then searches each interior
B sample for three unique 4 KB encoded blocks at a consistent displacement. Matching
blocks are byte evidence; the report also counts complete consecutive MP3 frames in
the first block. Where A has a complete supported MP3 frame index, the report gives
A's frame-clock time at the match and at the original requested byte offset, plus
their difference. This uses actual frame sizes and sample rates, not file-size ratios.
The frame clock precedes encoder-delay/padding adjustment and is not exact audible time.
The first sample is excluded because it may contain metadata rather than audio.

Without `--decode`, only Python 3.9 or newer is required. With it, local FFmpeg is
optional and explicitly reported if missing. The helper decodes mono audio locally,
excludes half a second at each fragment edge, and searches A's 100 ms log-RMS envelope.
A candidate needs correlation of at least 0.90 and a margin of at least 0.10 over a
nonoverlapping alternative. These are heuristic scores, not confidence probabilities.
Listen to candidate passages to confirm them, especially if fragment decoding reports
errors. Repeated music, silence, short excerpts or changed encoding can make the result
ambiguous. Decoder lead-in and the 100 ms grid limit timing precision. Full-A decoder
errors prevent decoded alignment rather than silently shifting its reference timeline.

A whole-file decode can take minutes. A is held in memory (about 121 MB for the
observed longer file); MB here means 1,048,576 bytes. Reports contain hashes and sample
numbers, not source URLs or local paths. Keep the inputs and report outside Git.
Existing reports are never overwritten. No full B capture mode is added.

Local correspondence does not establish B's absolute timeline, ad layout, intervening
content or ending. Nonmatch is not proof of a gap. The existing duration analyzer and
this sparse alignment report answer different questions. Run the synthetic checks with
`py -3 scripts/testAlignEpisodeSamples.py`; the real MP3 check is skipped explicitly
when FFmpeg is unavailable.

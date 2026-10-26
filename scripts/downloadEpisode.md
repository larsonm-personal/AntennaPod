# Capture the reported RSS episode locally

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
unverified because cloud access was blocked. The script does not impersonate AntennaPod.

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
cut are approximations, not an actual AntennaPod run. Signed URLs are held in memory
only; persisted routes are redacted/hashes. Keep the whole capture directory private.
Run local staged fixtures with `py -3 scripts/testDownloadEpisode.py`.

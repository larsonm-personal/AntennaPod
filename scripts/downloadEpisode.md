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

One invocation fetches the feed and downloads audio once, following normal redirects.
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

# Capture the reported episode locally

Requires Python 3.8 or newer; no extra packages. From the cloned repository on Windows:

```powershell
py -3 scripts/downloadEpisode.py
```

This downloads the public **“What the hell is going on in Europe?” (October 1, 2026)**
episode through Audioboom's public download link. It saves `antennapod-europe.mp3`
and `antennapod-europe.mp3.json` in your Downloads directory, outside this checkout.
Existing files are never overwritten. Choose another path with
`py -3 scripts/downloadEpisode.py --output "C:\Users\YourName\Downloads\europe-2.mp3"`.
An optional first argument accepts another direct public MP3/enclosure URL, not an RSS URL.
On macOS/Linux, substitute `python3` for `py -3`.

Each invocation makes one download, following normal redirects, with no HEAD probes,
range probes, retries, identity changes, or uploads. HTTP 403/authentication errors
stop the download. Incomplete files are removed. The JSON records response status,
byte count, range support, validators and redirect origins; it omits URL paths and
queries so signed/session URLs are not retained. Keep captures local and do not commit
audio or metadata. Use the default output location to keep them outside Git.

This collects input for investigation; it does **not** reproduce AntennaPod playback.
Local fixture tests cover redirects, successful capture, errors and overwrite protection.
Live Audioboom access from the cloud environment was blocked, so live capture is unverified.

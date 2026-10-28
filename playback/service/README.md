# :playback:service

The main service doing media playback.

`Media3PlaybackService` is the active implementation, built on AndroidX Media3/ExoPlayer.
`PlaybackService` is legacy and will throw an exception if started — it exists only during the transition period.

External callers should interact with the service through `PlaybackController`, which provides a
`bindToMedia3Service()` helper that connects a `MediaController` and runs a callback on it.
The `MediaController` exposes the standard Media3 `Player` interface: `seekTo(positionMs)`,
`play()`, `pause()`, `getCurrentPosition()`, `getPlaybackParameters()`, etc.
Each call to `bindToMedia3Service()` creates a short-lived connection that is released after the
callback returns.

Streaming cache entries retain the GET-selected URL. Network joins check the known
resource length and URL before accepting bytes. A detected change, or an unavailable
cached URL that successfully resolves elsewhere, permits one service recovery for the
current episode. Recovery prepares a new source at the current approximate timestamp;
it does not translate positions between different ad layouts.

Each source captures a cache generation. Invalidation persists a new token before
preparation, leaving late writes in the old namespace for the existing 100 MB LRU to
evict. Ordinary restarts reuse the token. The `stream-cache-generations` preferences
store SHA-256 enclosure identifiers and tokens, not URL history. Only invalidated enclosures create token entries. These are retained until app data is
cleared, independently of cache eviction; there is no automatic pruning. Same-URL,
same-length changes remain unsupported; this candidate compares neither ETags nor audio overlap.

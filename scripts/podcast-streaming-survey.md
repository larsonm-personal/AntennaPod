# Podcast streaming and changing ad variants

Inspected 2026-10-28. This survey accompanies the
[server characterization and reproduction notes](serverCharacterization.md).
It records source and issue findings, not a claim that every approach has been
verified on a device or shipped to users.

The problem of interest is missing or repeated content after requests receive
different audio versions of an episode. A clean switch to a new version, with
an approximate time resume, is acceptable for our proposed fix. The important
requirement is that the new version remains seekable: rewinding must not keep
returning a mixture with sections missing. Recovering the exact sentence across
changed ad layouts is a separate problem.

## Pocket Casts Android

Inspected main at
[`25660d9dc5f1ac545bec7c0bdb8a47dc168a7435`](https://github.com/Automattic/pocket-casts-android/tree/25660d9dc5f1ac545bec7c0bdb8a47dc168a7435).
[PR #6213](https://github.com/Automattic/pocket-casts-android/pull/6213), merged
October 27, explicitly addresses streamed episodes ending early because of
changing dynamically inserted ads. Its 8.25 milestone is not evidence that the
change has shipped.

The implementation compares the served file size with the cached size. It uses
the total from Content-Range, or Content-Length for a full request. On mismatch,
it closes upstream, removes the episode's cache, records the new length, and
throws MediaBuildChangedException, a CacheException. The cache factory's
FLAG_IGNORE_CACHE_ON_ERROR permits later cache bypass. Cache entries use the
episode UUID and a persistent LRU cache; a worker writes the cache, while the
player's HTTP streaming path does not write it.

Primary code:

- [CachedLengthValidatingDataSource.kt](https://github.com/Automattic/pocket-casts-android/blob/25660d9dc5f1ac545bec7c0bdb8a47dc168a7435/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/CachedLengthValidatingDataSource.kt)
- [ExoPlayerDataSourceFactory.kt](https://github.com/Automattic/pocket-casts-android/blob/25660d9dc5f1ac545bec7c0bdb8a47dc168a7435/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ExoPlayerDataSourceFactory.kt)
- The corresponding validator tests are under the repository module's `src/test`;
  the PR also bumps the cache version.

The limits matter. Equal-size variants are explicitly outside this check. The
PR author could not trigger a real host's size change on-device and used unit
tests. There is no explicit fresh-source/extractor reset in this mechanism.
The inspected [Media3 1.11.1 progressive retry path](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java#L1093-L1099)
retains the old byte position and extractor. Thus cache invalidation is useful
precedent, but it does not prove that all of the replacement version becomes
correctly seekable.

The earlier [PR #5869](https://github.com/Automattic/pocket-casts-android/pull/5869)
was open and unmerged at inspection. It checks premature HTTP/2 EOF, a different
failure from individually valid responses containing incompatible ad variants.

## Pocket Casts iOS

[PR #1468](https://github.com/Automattic/pocket-casts-ios/pull/1468), merged
2024-02-26, gives dynamic ad insertion as an explicit motivation.
[PR #2247](https://github.com/Automattic/pocket-casts-ios/pull/2247), merged
2024-10-17, implements playback from a growing local file populated by a whole
GET. A seek beyond the downloaded portion waits for that portion to arrive;
a failed incomplete file is deleted.

The current source was inspected at
[`1fba7a266e5ae9c570299c799abbe2831e161d26`](https://github.com/Automattic/pocket-casts-ios/tree/1fba7a266e5ae9c570299c799abbe2831e161d26),
particularly `MediaExporterResourceLoaderDelegate.swift` lines 197–250 and
304–335, and `DownloadManager.swift` lines 329–447. The flag defaults on, but the
path excludes HLS, video, user episodes, low-disk situations and some platforms.

This is a substantially larger architecture than changing an Android cache key.
Its single-download design reduces opportunities to assemble an episode from
separately selected variants. That is a design inference, not a claim of blanket
immunity across every transport failure or playback path.

## Shuttle AudioPlaybackKit

The repository README identifies AudioPlaybackKit as the engine for Shuttle
Podcasts and Shuttle 2. Adoption in particular consumer releases was not
verified.

[Commit 6650c899](https://github.com/timusus/AudioPlaybackKit/commit/6650c89972620c89b15b1ec78caef676be606268),
implemented October 11 and closing issue #81, checks overlapping bytes at every
join, including both seams of a hole. It compares up to 64 KB. On mismatch, it
discards the whole partial file and restarts at the reader's position. A strong
ETag with an If-Range request returning 200 is treated as a changed file; length
is an additional check.

The [overlap design record](https://github.com/timusus/AudioPlaybackKit/blob/main/docs/decisions/0016-overlap-check-at-every-join.md)
and `GrowingFileDownloadOverlapTests.swift` explain and exercise this policy.
The design record corrects the claim about equal-length real-world variants to
an unmeasured possibility. Matching seams are evidence of local agreement, not
proof that the entire file is identical. This approach buys stronger local
checks at the cost of extra requests, bytes and implementation complexity.

## Podverse

[Issue #1350](https://github.com/podverse/podverse-rn/issues/1350) reports jumps
and early endings while streaming, with downloads working. A maintainer
[suggested changing ad variants and recommended downloading](https://github.com/podverse/podverse-rn/issues/1350#issuecomment-1419824677);
the issue was closed as won't fix. That explanation is a maintainer hypothesis,
not a measured reconstruction of the reported server behavior.

At inspected application commit
[`84155643c2a881584874357411d1262ac688fc67`](https://github.com/podverse/podverse-rn/tree/84155643c2a881584874357411d1262ac688fc67),
`playerAudio.ts` lines 464–521 choose a local file or the enclosure, and Android
player setup enables caching. The pinned
[KotlinAudio dependency](https://github.com/doublesymmetry/KotlinAudio/tree/d521c5f68643a2f99e065f136ef9fa38c5b4819c),
`BaseAudioPlayer.kt` lines 527–608, puts CacheDataSource outside redirect-following
HTTP with the default cache key and no representation identity check in that
path. The cache-size comment and implementation disagree on units, so this
survey does not assign it a claimed 1 GB size.

## Anytime and Tsacdop

Anytime's [2024-08-27 change](https://github.com/amugofjava/anytime_podcast_player/commit/e46873ffad23eec330fd82bb23cc7eeb6e9f5b24)
explicitly addresses DAI duration: it uses the loaded player's duration rather
than the RSS duration. That is a duration correction, not a representation
consistency mechanism.

At inspected commit
[`23b2978e4692cebbfddce211d479970d575d479b`](https://github.com/amugofjava/anytime_podcast_player/tree/23b2978e4692cebbfddce211d479970d575d479b),
[`default_audio_player_service.dart` lines 975–1002](https://github.com/amugofjava/anytime_podcast_player/blob/23b2978e4692cebbfddce211d479970d575d479b/lib/services/audio/default_audio_player_service.dart#L975-L1002)
use plain AudioSource.uri or a local file, not LockCachingAudioSource.

Tsacdop's inspected commit
[`b2dfde6f201e9217cb3eaa41eef1c9f89c34b159`](https://github.com/stonega/tsacdop/tree/b2dfde6f201e9217cb3eaa41eef1c9f89c34b159)
also uses a plain just_audio AudioSource.uri path. No specific DAI fix was
verified. Absence of an application disk-cache wrapper does not establish
immunity to changed content across network requests.

## What this means for AntennaPod

These examples support keeping incompatible cached bytes apart. They do not
supply a general way to reconstruct listening position across changed ad
layouts. Length checks miss equal-size changes; overlap checks cover sampled
seams; a growing-file downloader is a broader architectural choice.

Our proposed direction is two separately reviewable changes, keeping the
existing 100 MB LRU cache:

1. **Separate cache identity by the selected resolved URL.** In ExoPlayerUtils,
   resolve before cache lookup and retain one selected URL for each media
   source. Use a new cache-key namespace so old enclosure-keyed entries cannot
   contaminate the new path; those entries can age out through ordinary LRU
   eviction.
2. **Treat a changed known total resource length at the same URL as invalidation.**
   For partial responses, compare the total from Content-Range, not the response
   body's Content-Length. A short range body does not mean the resource shrank.
   An unknown total is not evidence of a change. On a detected change, stop using
   the old source and rebuild playback at approximately the saved timestamp.
   Do not retry the old byte offset with retained buffers and the old seek map.

These are **proposed directions, not implemented or validated fixes** as of this
survey. The length comparison is small; safe invalidation and source replacement
still require careful testing. Neither change establishes that HEAD and GET
select the same version. Same-URL, same-length mutation remains a limitation.
No overlap hashing, durable pin database or eager downloading is proposed for
the initial fix.

A one-time approximate resume into coherent B is acceptable; persistent gaps
that cannot be recovered by seeking are not. An expired URL may also require a
newly resolved source, but that recovery must not turn every transient timeout
into a version switch or an unbounded retry loop.

Validation should preserve the frozen reproduction and controls unchanged, then
add separate changed-length and backward/forward seeking cases. After switching
to B, check consumed B audio across the old cache boundary, including previously
missing content and B's ending. Cover both Content-Range totals and full-response
lengths, unknown lengths, unchanged lengths and cache eviction. A green playback
test alone is insufficient: the new version's content must remain accessible by
seeking. None of the surveyed fixes removes the need to demonstrate those
properties in AntennaPod.

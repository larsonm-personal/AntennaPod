# Service playback hazards

Work plan; cases are not qualified until their recorded runs complete.

Tests live in `de.test.antennapod.playback.hazards`. Each hazard has a small test
class, with related healthy controls beside it. `PlaybackCase` owns the existing
service session and consumed-audio observation. `ResolvedEpisodeServer` owns
explicit request behavior and test-signalled state changes. No general scenario
language, cache-key assumptions, or production fix code is introduced.

| Class | Scenario and provenance | Observable contract |
| --- | --- | --- |
| ResolutionChangeTest | Measured stable A/B endpoints and fresh resolution change; synthetic ad placement | Fixed program passages remain reachable after service restart |
| SameUrlChangeTest | Anticipated different-length and equal-length mutations | Coherent sampled passages remain reachable; errors are not recovery |
| HeadGetDisagreementTest | Anticipated differing HEAD/GET selection | Assert received program content, not the chosen request route |
| ReconnectTest | Synthetic interrupted response | Stable program remains playable through reconnect |
| CacheEvictionTest | Real production LRU pressure from other episodes | Stable or replacement content remains reachable after pressure |
| ExpiryTest | Synthetic expired endpoint and fresh alternative | Record whether expiry was encountered or safely avoided; check passage access |
| RangeFallbackTest | Synthetic server ignores Range with full 200 response | Stable and changed content remain reachable |
| PlaybackLifecycleTest | Pause/resume, saved-position restoration, completed download | Successful playback and named passages; separate persisted-position assertion |
| CacheReuseTest | Stable restart | Successful audio with bounded measured response bytes |

Keep the original fixed passage plan (75, 105, 90), two mapping candidates and
fixed replay. Accept coherent original reuse or replacement, approximate resume,
and valid output reuse. Never search for a clean observation window. Retain all
observer and server self-checks. Same-length tagged editions identify content;
they do not establish an ability to detect arbitrary equal-length server changes.

Not included: process death, actual Wi-Fi/cellular handoff, partial eviction, or
one episode larger than the cache. The measured evidence does not establish
same-URL mutation, expiry, disconnection, or exact ad placement.

Qualification starts with the reorganized unchanged PR5 cases, then adds server
behaviors and small groups of service cases. Record all failures, including
healthy-control failures. Fixed CI repetitions run as separate jobs, retain full
logs and cap inline diagnostics at 200 lines. The production fix stays separate;
no test is changed to accommodate it.

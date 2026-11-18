# Spec section 8, point by point

Status values: **done** (implemented and unit tested where it is pure logic), **measured**
(reads a real source at runtime), **gated** (code complete, refuses until a prerequisite
exists), **not possible** (cannot be done from an app; the reason is given), **by policy**
(deliberately not done).

## Gaming compatibility

| requirement | status | how |
|---|---|---|
| ARM64 and ARM32 where supported | done | `AbiResolver` mirrors the platform's preference rule; pinning via staged session install |
| Unity and Unreal | done | `GameEngineDetector` from library and asset markers, with evidence kept |
| IL2CPP and native libraries | done | `libil2cpp.so` / `global-metadata.dat` detection; `NativeLoadProbe` checks ELF class and machine per ABI directory |
| Vulkan and OpenGL ES | measured | declared features vs device feature versions; never claims which API a game will choose at runtime |
| Large OBB and Android/data | measured | `ObbNameParser` lifts the version code out of each file name; `StoragePaths` gives per-user trees. Reading another package's `Android/obb` needs root from Android 11 on, and says so when it cannot |
| Asset and resource loading | measured | stale-expansion-file detection, which is the usual cause of a clone failing after an update |
| Play Services and game login | done, by design | the clone keeps the original package name and signature; no interception layer, so there is nothing for login to trip over |
| Game updates without breaking clones | done, by design | users share one APK install, so an update applies to every instance |

## Performance optimisation

Nothing here claims to make a game faster. The engine measures and avoids adding overhead; the
one real optimisation available to a cloner is not interposing on the game in the first place.

| requirement | status | how |
|---|---|---|
| Minimise RAM | done, by design | no container process, no duplicated runtime; the only resident cost is the monitor |
| Reduce background CPU | measured | sampling interval is configurable and sampling stops when not watching |
| Clone and game launch time | done, by design | stock install, stock launch path; no unpacking or rewriting at start |
| Prevent unnecessary services | done | one foreground service, only while sampling |
| Memory pressure and background processes | measured | available memory against the platform's kill threshold, plus the platform's low-memory flag |
| Avoid CPU throttling and overheating | measured | thermal zones plus `PowerManager` thermal status; throttling is labelled as the cause when it is |
| Storage I/O and asset loading | measured | iowait is excluded from busy CPU, so a storage stall is not read as load |
| Stable frame pacing | measured | p50/p95/p99 frame times and jank counted against the refresh period, from real timestamps |
| No artificial FPS unlocks or GPU modifications | by policy | `IntegrityPolicy.NO_FRAME_RATE_UNLOCK`, `NO_GPU_DRIVER_OVERRIDE` |

## Stability and crash prevention

| requirement | status | how |
|---|---|---|
| Prevent clone-related crashes | done, by design | no injected code in the game, so the cloner adds no crash surface |
| Native library load failures | done | ELF class and machine checked against the ABI directory |
| Insufficient memory conditions | measured | platform threshold, low-memory flag, and `REASON_LOW_MEMORY` exits |
| Vulkan/GLES compatibility errors | measured | declared requirement vs device capability, as a blocker when declared required |
| Crash logs and diagnostics | measured | platform exit history with the native trace, plus root-gated logcat and tombstones |
| Android version and ABI incompatibilities | done | minSdk vs device API; releases above the validated ceiling are reported as unvalidated rather than assumed equivalent |
| Stable operation over long sessions | measured | rolling capped history; the monitor is a foreground service so the series has no holes |

## Root gaming integration

| requirement | status | how |
|---|---|---|
| KernelSU, Magisk, APatch | done | `RootProvider` verifies uid 0 by running a command, then classifies the flavour from `su -v`; an unfamiliar string stays `UNKNOWN` and root still works |
| Per-clone root permission control | not possible from here | no root manager exposes a stable, documented interface for per-uid policy. The backend returns the exact manual step and the uid to look for, rather than running an undocumented sqlite write |
| Optional root per cloned app | gated | carried in the isolation config for the companion module to honour |
| Namespace and mount isolation | gated | see ARCHITECTURE.md: requires a module in the app spawn path |
| Never interfere with the original | done, by design | separate Android users; the backend refuses to touch user 0, and the isolation config leaves clone index 0 alone |
| No anti-cheat or integrity bypass | by policy | `IntegrityPolicy.NO_INTEGRITY_INTERFERENCE`; nothing in this project hooks, patches or spoofs |

## Gaming dashboard

| field | without root | with root |
|---|---|---|
| RAM | system-wide, measured | plus per-process RSS |
| CPU | system and per-core, measured | plus per-process share of a core |
| Temperature | platform thermal status; sysfs zones where readable | all zones |
| Game process info | unavailable, stated | pid, threads, user id |
| FPS | unavailable, stated | measured from SurfaceFlinger |
| Per-clone consumption | unavailable, stated | per Android user |
| Crash reports | exit history for the app's own package | any package |

A field that cannot be read shows why it cannot, and never a number.

## Testing

See TEST_MATRIX.md. The spec's own requirement applies to this project too: no claim of zero lag
or zero crashes appears anywhere in the UI or the docs, because the matrix has not been run.

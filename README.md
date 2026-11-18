# App Cloner - gaming compatibility and performance engine

Multi-instance Android app cloning aimed at demanding games, with a performance and diagnostics
engine that measures rather than estimates.

This is a fresh implementation of spec section 8. It does not reuse the earlier repository.

## The short version

Clones are separate Android users or a managed profile, not a container. The game keeps its own
package name, signature and runtime, so login and updates work and frame pacing is the device's
own. Nothing hooks, patches or spoofs the game.

Every figure on the dashboard is a real measurement with its source named, or an explicit
"unavailable" with the reason. There is no synthetic FPS and no estimated memory. On a stock
unrooted device a good number of fields will say they cannot be read - that is the truthful
outcome, and the UI says which ones and why.

Read `docs/ARCHITECTURE.md` for why, and `docs/GAMING_COMPAT.md` for the spec mapped point by
point to its real status.

## Build

Not built in this environment: there is no Android SDK here. CI builds it, which also means you
can build from a phone - push, then download the APK from the run's artifacts.

```
# in CI, or locally with the Android SDK installed
gradle :engine:testDebugUnitTest      # pure parsers and evaluators, no device needed
gradle :app:assembleDebug             # app/build/outputs/apk/debug/
```

Gradle is pinned by `.github/workflows/build.yml` rather than by a committed wrapper jar. If you
want `./gradlew` locally, run `gradle wrapper` once on a machine with network access.

Toolchain: AGP 8.7.2, Kotlin 2.0.21, JDK 17, compileSdk 35, minSdk 31 (Android 12), Compose BOM
2024.10.01. All versions are in `gradle/libs.versions.toml`.

## Install and first run

The debug APK installs alongside nothing else; it needs no root to start.

1. **Clones tab** - pick a goal, press Rescan. Each backend lists its real capabilities and, if
   unusable here, why. For a clone that runs at the same time as the original, create the work
   profile when prompted.
2. **Compat tab** - Inspect on any app gives the ABI the platform would pick, the engine family
   with the evidence for it, expansion-file state, and a findings list with next steps.
3. **Dashboard tab** - pick a target and start sampling. Root unlocks per-process and frame data.

## Root

Root is detected by running a privileged command, not by looking for an su binary. KernelSU,
Magisk and APatch are recognised; an unfamiliar `su -v` string leaves the flavour unknown and
everything still works, because features are gated on capabilities rather than on the name.

Per-uid root policy is owned by your root manager. The app tells you the exact entry to change
and the uid to look for instead of writing to an undocumented policy database.

## What is deliberately not here

- No anti-cheat, integrity or attestation interference.
- No frame-rate unlocking and no GPU driver or vendor blob modification. A cap raised past what
  the display pipeline sustains gives worse pacing, not better.
- No performance claims. The test matrix in `docs/TEST_MATRIX.md` has not been run, so there is
  no "zero lag" or "zero crashes" anywhere in this project.
- No per-clone mount-namespace isolation yet: it needs a Zygisk/KernelSU module in the app spawn
  path, which an app cannot do for itself. The config interface for such a module is complete
  and the backend refuses with the build recipe until the module is installed.

## Layout

```
engine/  compat, clone backends, root, perf, stability, policy
app/     Compose UI, foreground sampling service, dependency graph
tools/   test-matrix.sh, frame-report.py
docs/    ARCHITECTURE.md, GAMING_COMPAT.md, TEST_MATRIX.md
```

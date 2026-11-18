# Architecture

## The decision that shapes everything else

The spec asks for things that are, taken together, incompatible with a container:

- Google Play Services and game login must keep working
- game updates must not break clones
- frame pacing must stay stable, with no clone-induced lag
- anti-cheat and integrity checks must not be interfered with

A VirtualApp-style container - running the guest APK inside the cloner's own process and
intercepting Binder, the package manager and the file system - fails all four. The guest sees a
package name and a uid that are not its own, which is what breaks login and Play Services; every
interception layer sits between the game and the platform on the hot path; each Android release
moves the hooks; and the container is itself a modification of the runtime the game is checking.

So isolation here uses the platform's own per-user separation. An Android user, or a managed
profile, is a complete separation of data, external storage and accounts, implemented by the OS.
The package name is unchanged. The signature is unchanged. The runtime is stock, so frame pacing
is the device's own. The APK is shared between users, so a game update applies to every instance
with nothing to recreate.

The cost is honest and is stated in the UI rather than hidden: a secondary user is not
foreground-concurrent, and a managed profile is limited to one per device. Those are the real
trade-offs of the mechanisms, not defects of this implementation.

## Modules

```
engine/   platform logic, no UI. Pure parsers and evaluators are separated from the
          Android-dependent collectors so the arithmetic is unit tested on the JVM.
app/      Compose UI, a foreground service for sampling, and the manual dependency graph.
tools/    adb-driven test matrix runner and a frame-pacing report.
```

### engine/compat

Reads what is actually installed. `ApkInspector` pairs the package manager's record with the
APK's own central directory: ABI directories, native libraries, engine markers, declared
features, expansion files. `GameEngineDetector` classifies the engine family from file names and
keeps the entries it concluded from, so a verdict can be traced. `CompatibilityEvaluator` turns
that plus a `DeviceProfile` into findings, each with a next step.

The verdict enum is called `StaticVerdict` and its good case is `NO_BLOCKERS_FOUND`, not
`COMPATIBLE`. Static inspection rules things out; it cannot rule them in.

### engine/clone

Three backends behind one interface, each publishing its real capabilities:

| backend | concurrent | root | clones/package | notes |
|---|---|---|---|---|
| `ManagedProfileBackend` | yes | no | 1 | work profile; needs one-time provisioning |
| `SecondaryUserBackend` | no | yes | device limit | full isolation; switching users; can pin ABI |
| `RootDataIsolationBackend` | yes | yes | unlimited | **gated**, see below |

`BackendSelector` ranks them for a stated goal and returns the whole picture, including why an
unusable backend is unusable, so the UI never silently substitutes a mechanism with different
properties.

### engine/clone - the gated backend

`RootDataIsolationBackend` would give several concurrent instances of one package inside one
Android user, by bind-mounting a per-clone data directory. It is not implemented as working
code, because it cannot be: an app's mount namespace is established by zygote at specialisation
time, and nothing the app runs afterwards lands inside the game's namespace. It needs a Zygisk
or KernelSU companion module in the spawn path.

The config writer is complete and is the interface such a module reads
(`/data/adb/appcloner/clones.json`, schema in the class docs). Until the module's marker file
exists, `availability()` reports `NeedsSetup` and every operation refuses with the build recipe.
That module is a separate native deliverable and is not in this project.

### engine/perf

Every reading is a `Metric<T>`: either a `Value` with its source named, or `Unavailable` with a
reason and a remedy. There is no code path that can put a number where a measurement failed.

What this means on a stock, unrooted device: system memory, CPU load, thermal status and the
platform's memory-pressure figures all work. Per-process memory and CPU do not, because /proc is
mounted with hidepid from Android 9, and frame data does not, because `dumpsys` is privileged.
Those cards say so.

Frame data comes from `dumpsys SurfaceFlinger --latency` for the game's own layer, because a
game renders into its own surface and HWUI never sees its frames; `gfxinfo framestats` is the
second choice, for View-based apps. `FramePacing` computes nearest-rank percentiles and counts a
frame as jank when it exceeds twice the display refresh period.

### engine/stability

`NativeLoadProbe` reads the ELF header of each shipped library and checks it against the ABI
directory it sits in - the cheapest real check for a packaging fault that otherwise surfaces as a
bare `dlopen failed`. `CrashCollector` uses the platform's own process-exit history, which
distinguishes a native crash from an ANR from a low-memory kill and records the process size at
death. `MemoryPressureWatcher` compares available memory against the platform's own kill
threshold rather than a chosen percentage.

### engine/policy

`IntegrityPolicy` holds the lines in code rather than in a README: no interference with
anti-cheat or integrity checks, no frame-rate unlocking, no GPU driver override, no synthetic
metrics.

## What is assumed, and how to check it

| assumption | where | how to verify |
|---|---|---|
| USER_HZ is 100 | per-process CPU maths | `getconf CLK_TCK`; resolved at runtime, and flagged in the snapshot when it falls back |
| `pm install-create --abi` is honoured | 32-bit pinning | the command output is kept verbatim in the operation log |
| thermal zone scale | `TemperatureConverter` | raw value is kept next to the converted one, with the band named |
| `--latency` exists on this release | frame data | the failure text is surfaced; `--timestats -enable` is the documented alternative |
| store package names | `GameProfileRegistry` | every id is resolved through `PackageManager` before use |

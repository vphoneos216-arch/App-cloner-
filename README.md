# RootClone - root-based Android app cloning engine

Root-based multi-instance app cloning for Android 12 to 17, aimed at demanding games, with a
performance and diagnostics engine that measures rather than estimates.

No work profile, no device owner, no Shelter/Island architecture. Cloning runs on AOSP's own
clone profile user type, created with real root.

## The short version

Clones are AOSP **clone profiles** (`android.os.usertype.profile.CLONE`), created from a root
shell. The clone keeps the original package name and signing certificate, gets its own UID and
its own `/data/user/<id>/<pkg>`, and runs at the same time as the original. The APK is shared,
so a game update applies to every instance.

Root is the mechanism, not a badge: `UserManagerService` accepts `Process.ROOT_UID` for
MANAGE_USERS, which is why `su -c pm create-user` works where an app's own `UserManager` call is
refused. The Root tab shows the actual `id` output and its exit code, and the Logs tab shows
every command with both streams.

Stock Android allows **one** clone profile, which holds one copy of each app. Cloning several
different apps works; a second instance of the *same* app needs a second profile, which stock
Android refuses — the engine reports that limit and its remedy instead of pretending.

Two concurrent instances of one package inside one user is impossible regardless of root:
ActivityManager keys processes by (process name, uid). `docs/CLONE_PROFILE.md` has the full
reasoning, the exact commands, and the sources.

## Build

Not built in this environment: there is no Android SDK here. CI builds it, which also means you
can build from a phone — push, then grab the APK from the newest release:
`https://github.com/vphoneos216-arch/App-cloner-/releases/latest`

```
gradle :engine:testDebugUnitTest      # pure parsers and evaluators, no device needed
gradle :app:assembleDebug
```

Toolchain: AGP 8.7.2, Kotlin 2.0.21, JDK 17, compileSdk 35, minSdk 31 (Android 12), Compose BOM
2024.10.01, CMake + NDK for the native bridge (arm64-v8a and armeabi-v7a). Versions live in
`gradle/libs.versions.toml`.

## Modules

```
engine/clone        clone profile backend, secondary-user backend, gated mount-namespace backend,
                    backup and restore, pm/dumpsys output parsers
engine/root         RootManager: su execution with timeouts, process cleanup, command log
engine/process      ProcessManager: launch, force-stop and process observation, per user
engine/compat       ApkInspector: ABI, native libs, engine family, graphics, expansion files
engine/perf         measurement only: Metric<T> is either a value with its source or Unavailable
engine/stability    ELF checks, crash and exit-reason collection, diagnostics report
engine/nativebridge NDK/JNI: SELinux labels, ownership, ELF identity, process ABI and uid
app/                Compose UI: Clones, Root, Monitor, Compat, Logs
tools/              adb test matrix runner, frame pacing report, diagnostics collector
```

## First run

1. **Root tab** — press Re-check root. It runs `id` as root and shows the result, the manager it
   detected, the raw `su -v` string, the native bridge state, and every Android user on the
   device with its type. No green badge appears without uid 0 coming back.
2. **Clones tab** — Rescan, then Clone on an app. The operation log shows each command.
3. **Monitor tab** — pick a clone and start sampling.
4. **Logs tab** — every command, both streams, exit codes.

## What is deliberately not here

- No work profile, no device owner, no Shelter/Island architecture.
- No anti-cheat, integrity or attestation interference of any kind.
- No frame-rate unlocking and no GPU driver modification.
- No fake root: the verdict comes from `id` returning uid 0, and root-gated features stay gated.
- No performance or compatibility claims. The matrix in `docs/TEST_MATRIX.md` has not been run,
  so nothing here says any game works.
- No per-clone mount-namespace isolation: it needs a Zygisk/KernelSU module in the app spawn
  path. That backend is present, refuses to run, and reports the build recipe. It would give
  data switching, not concurrent instances, and is labelled accordingly.

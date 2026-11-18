# Test matrix

The spec requires every supported game tested independently across original, clone, 32-bit,
64-bit and several Android versions. That is a real matrix and it has not been run: no claim
about any title's behaviour appears in this project.

## Legs per title

| # | leg | command |
|---|---|---|
| 1 | original, user 0, platform ABI | `tools/test-matrix.sh --package PKG --user 0` |
| 2 | clone, secondary user, platform ABI | `tools/test-matrix.sh --package PKG --user 11` |
| 3 | clone, secondary user, 32-bit pinned | `tools/test-matrix.sh --package PKG --user 11 --abi armeabi-v7a` |
| 4 | clone, managed profile, concurrent with original | create from the app, then sample with `--no-launch` |

Leg 3 is not applicable to a title that ships no 32-bit libraries. Record it as
not-applicable; the compatibility screen reports that case explicitly rather than leaving it
looking untested.

## Per leg, measured automatically

- resolved ABI, from `pm dump`, not from what was requested
- available memory, thermal status, process RSS, utime/stime, thread count
- frame timestamps for the game's own SurfaceFlinger layer
- the platform's process-exit history, the process logcat, tombstone names

Run `tools/frame-report.py results/<leg>/raw/latency-*.txt` for the pacing figures.

## Per leg, recorded by hand

The script cannot do these, and they are the ones that decide whether a leg passed:

- [ ] launches to the main menu
- [ ] signs in (the account path is the first thing a container breaks)
- [ ] finishes any asset download
- [ ] completes a full match
- [ ] survives a game update without recreating the clone
- [ ] original instance still works afterwards, with its own data intact

## Android versions

The product targets Android 12 to 17. `DeviceProfile.HIGHEST_VALIDATED_SDK` is the ceiling this
engine has been reasoned about, currently API 35. A device above it still runs, and the
compatibility screen reports it as newer-than-validated. Raise that constant only after running
this matrix on the release, because what changes between releases is exactly the
clone-creation and storage-access behaviour this project depends on.

## Recording results

One directory per leg under `results/`, which the script creates. Keep `leg.properties`,
`samples.csv` and `raw/` together: the raw dumps are what make a number re-checkable a month
later, and `resolved-abi.txt` is what proves which leg you actually ran.

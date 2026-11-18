# The cloning mechanism

## What this uses

`android.os.usertype.profile.CLONE` — AOSP's own user type for running a second instance of an
app that only supports one account. It is **not** a managed profile and no DevicePolicyManager,
profile owner or device owner is involved.

The type was added to AOSP as a hidden user type in commit `5a4fc52` ("Add app clone profile and
APIs") and became public SDK API as `UserManager.USER_TYPE_PROFILE_CLONE` in API 35. Android 14
added the user-facing "Cloned Apps" page in Settings. The type exists from Android 12 onward,
which is this project's floor.

## Why this and not the alternatives

| requirement | clone profile | secondary user | repackage + resign | container VM |
|---|---|---|---|---|
| same package name | yes | yes | **no** | faked |
| same signing certificate | yes | yes | **no** | faked |
| own UID and data dir | yes | yes | yes | emulated |
| runs at the same time as the original | **yes** | no, user switch | yes | yes |
| game update applies to the clone | yes | yes | no, re-clone | yes |
| survives Android releases | yes | yes | yes | **no**, breaks each release |
| root needed | yes | yes | no | no |

A clone profile is the only column with no "no" in the rows that matter. Repackaging changes the
app's identity, which is what breaks certificate-bound features. A container fakes identity,
which is what makes it fragile and detectable.

## Why root is genuinely required

`UserManagerService.hasManageUsersPermission` returns true for
`isSameApp(callingUid, SYSTEM_UID) || callingUid == Process.ROOT_UID || MANAGE_USERS granted`.
An ordinary app holds none of those. uid 0 does. That is why `su -c pm create-user` succeeds
where the same `UserManager` call from app code is rejected — root is the mechanism here, not
decoration.

## The exact sequence

```sh
# 1. create the profile (parse the user id out of the output; never hardcode it)
pm create-user --profileOf 0 --user-type android.os.usertype.profile.CLONE "AppCloneX"
#    -> Success: created user id 11

# 2. make sure it is running
am start-user 11

# 3. install the already-installed package into it, reusing the same APK and signature
pm install-existing --user 11 com.example.game
#    -> Package com.example.game installed for user: 11

# 4. verify from the platform, not from the command's own output
pm list packages --user 11 com.example.game
pm list packages -U --user 11 com.example.game     # uid = 11*100000 + appId
test -d /data/user/11/com.example.game
test -d /data/user_de/11/com.example.game
```

Lifecycle, all scoped by `--user` so user 0 is never in range:

```sh
cmd package resolve-activity --brief --user 11 com.example.game
am start --user 11 -n com.example.game/.MainActivity
am force-stop --user 11 com.example.game
pm uninstall --user 11 com.example.game
pm remove-user 11                                   # only when the profile is empty
```

## The limit, stated plainly

`UserTypeFactory.getDefaultTypeProfileClone()` sets `maxAllowedPerParent(1)`. So stock Android
allows **one** clone profile per main user.

One profile holds one copy of any given package. Therefore:

- cloning several **different** apps into the one profile: works;
- a **second** instance of the **same** app: needs a second clone profile, which stock Android
  refuses with `Cannot add more profiles of type android.os.usertype.profile.CLONE for user 0`.

The engine detects that case and reports it with its remedy. Raising the limit means changing
`max-allowed-per-parent` in the framework resource `config_user_types.xml`, which takes an RRO
resource overlay or a system_server hook — neither of which an app can do to itself, so neither
is pretended here.

## What is not possible, and why root does not change it

Two concurrently running instances of the same package, each with its own data, **inside one
Android user**.

ActivityManager keeps process records in a map keyed by **(process name, uid)**, and within one
user a package has exactly one app id and therefore one uid. A second launch of the same
component resolves to the existing process record. PackageManager likewise holds one package
setting per package name, with per-user state hanging off it; there is no `pm` verb that creates
a second record, and hand-editing `packages.xml` gets you a map collision rather than a clone.

This is a data-model constraint, not a permission check, so uid 0 does not lift it. Something
must differ: the user (this project's choice), the package identity (repackaging), or the
identity the app observes (a container).

A Zygisk or KernelSU module that bind-mounts a different `/data/data/<pkg>` into the app's
mount namespace is sometimes described as cloning. It is not: it swaps which data set the single
instance sees. Useful as account switching, and it is labelled that way here, never as a clone.

## OEM dual-app profiles

OnePlus "Parallel Apps" has been observed as hidden user 999, Samsung "Dual Messenger" as user
95 named `DUAL_APP`. Xiaomi's own FAQ says its Dual apps feature is based on a managed profile.
These are not necessarily the AOSP clone type, so the engine reads each user's type from
`dumpsys user` and treats an untyped dual-app-looking user as a **reuse candidate** rather than
as a clone profile. If the OEM has already consumed the single clone slot, the engine installs
into the existing profile instead of failing.

## Game expectations

Because the package name and certificate are unchanged, a cloned game behaves like a fresh
install on a new user: expect a fresh login, and expect assets to download again, since
`Android/obb` and `Android/data` live under `/storage/emulated/<cloneId>/`. Asset sharing with
the main user should not be assumed.

No compatibility claim is made for any specific title. No publisher documents cloned
configurations, so every game in this app's list is shown as untested until the matrix in
TEST_MATRIX.md has actually been run on the device in hand.

## Sources

AOSP `UserTypeFactory`, `UserManagerService`, `PackageManagerShellCommand` and `ProcessList`;
`developer.android.com` reference for `UserManager`; AOSP commit `5a4fc52`; AOSP "Implement
custom user types" documentation. Community sources were used only for the OEM user IDs and are
marked as such above.

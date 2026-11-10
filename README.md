# Game Cloner

An app cloner for Android 12 to 17 (API 31 to 37) that runs a second copy of your games.

## How it works

Game Cloner creates one **clone space**: an Android work profile that the app itself owns.
A cloned game is installed into that profile by Android, so it is a normal installed app:

- no repackaging and no re-signing, so signature checks, Google sign-in and Play Integrity see the original app;
- no hooking and no in-app container, so there is nothing for a game to break on or to object to;
- separate data, separate accounts, same performance as the original.

That is why this design was chosen for "games must run well on Android 12 to 17": it uses only
public, stable Android APIs (`DevicePolicyManager`, `CrossProfileApps`, `PackageInstaller`).
A VirtualApp/BlackBox-style container was deliberately not used; see "Limits".

## Build

Every push to `main` builds the APK on GitHub Actions (`.github/workflows/build.yml`).

1. Open the repo on GitHub, then **Actions** and the latest "Build APK" run.
2. Download the APK from **Releases** (direct `.apk` link), or the `GameCloner-apk` artifact of the run.

Local build (Android SDK with platform 35 and JDK 17 required): `./gradlew :app:assembleRelease`.
The APK is written to `app/build/outputs/apk/release/app-release.apk`.

Toolchain: Android Gradle Plugin 8.7.3, Gradle 8.10.2 (wrapper), compileSdk 35, targetSdk 35, minSdk 31, Java 11 source level, no third-party dependencies.

## First run

1. Install the APK and open **Game Cloner**.
2. Tap **Create clone space** and accept Android's work-profile screens.
3. The app then opens the **clone manager**, which runs inside the clone space.
   A second, briefcase-badged Game Cloner icon appears in the launcher's Work tab; both icons lead to the manager.

## Cloning a game

In the manager tap **Clone an installed app**, pick the game, then choose:

| Method | Use it for | Notes |
| --- | --- | --- |
| **Play Store** | games installed from Google Play | Opens the game's Play page inside the clone space. Add a Google account there once. Keeps Play licensing, updates and in-game downloads working. |
| **Direct copy** | games installed from an APK file, or when you want no account | Copies base APK and all splits into the clone space; Android asks you to confirm. Android then records Game Cloner as the installer of that app for both copies, so Play may stop auto-updating it. |

Preinstalled (system) apps are simply switched on in the clone space, without a copy.

Tap a row to start a clone, **Remove** to uninstall it from the clone space only.
Menu, **Delete clone space** removes the profile with every clone and its data.

## Limits

- **One extra copy per game.** Android allows one work profile per user. More copies of the same game need a different engine (an in-app container), which this project is not.
- **Game data is not copied.** A clone starts fresh: its own login, its own downloads (OBB/resources are fetched again by the game).
- **One work profile only.** If the phone already has a work profile (employer, Shelter, Island) or an OEM dual-app space that occupies the slot, Android refuses to create another.
- **Unknown-app installs must be possible** for Direct copy. Android's Advanced Protection mode blocks them; the Play Store method still works.
- Containers were not used because games with integrity or anti-tamper checks reject them and every Android release breaks them again; "all games, Android 12 to 17" cannot honestly be promised for that design.

## Signing key

A profile-owner app cannot be updated with a different signature without deleting the clone space,
so every build should be signed with the same key. The key is **never committed** (`signing/` is git-ignored):

- **GitHub Actions:** store the keystore as the repository secret `SIGNING_KEYSTORE_B64`
  (the output of `base64 -w0 release.p12`). The workflow decodes it to `signing/release.p12` before building.
- **Local build:** put the keystore at `signing/release.p12`.

Keystore type is PKCS12; password and alias default to `gamecloner` and can be overridden with the
`SIGNING_PASSWORD` and `SIGNING_KEY_ALIAS` secrets or environment variables.
Create a key with:
`keytool -genkeypair -keystore release.p12 -storetype PKCS12 -alias gamecloner -keyalg RSA -keysize 2048 -validity 10950`

Without the secret the build still succeeds, but each run signs the APK with a throw-away key,
and such an APK does not install over a build signed with a different key.

## Troubleshooting

- **"Create clone space" is refused:** remove the existing work profile in Settings (Accounts page), or turn off the OEM dual-app / second-space feature, then retry.
- **Setup finishes but no clone space appears:** restart the phone once and open Game Cloner again. If it is still missing, remove the half-created work profile in Settings and create it again.
- **"Personal apps not reachable":** open Game Cloner from the personal (non-badged) icon once so the cross-profile link is registered again; the Play Store button works without it.
- **Clone space paused:** when the work profile is paused, Android asks to turn work apps on before anything in it starts.
- **Uninstalling:** delete the clone space first (menu in the manager), then uninstall Game Cloner.

## Source layout

| File | Role |
| --- | --- |
| `MainActivity` | Launcher entry; creates the clone space, or hands over to the manager |
| `ManagerActivity` | Clone manager inside the clone space: list, start, remove, add |
| `PickerActivity` | Personal-profile list of installed apps, reached through Android's cross-profile forwarder |
| `ApkCloner`, `InstallResultReceiver` | Direct copy through a `PackageInstaller` session |
| `ProfileSetup`, `AdminReceiver`, `ProvisionedActivity` | Profile-owner setup after provisioning |
| `Bridge`, `AppEntry`, `AppListAdapter`, `Ui` | Shared constants, list model and view helpers |

## Verification status

The project compiles and packages on GitHub Actions against the real Android SDK (compileSdk 35).
It has not been run on a device by its author, so runtime behaviour is unconfirmed. Confirm on your own phones:
work-profile creation on your OEM skin, the cross-profile picker, one clone by each method, and a real game start
on the lowest and highest Android version you care about.

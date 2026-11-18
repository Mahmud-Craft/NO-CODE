# NO CODE — Termux fork with the real OpenCode web UI

**Package:** `com.nocode` · **Version:** 0.118.3 · **ABI:** arm64-v8a

NO CODE is a standalone Android app built from termux-app v0.118.3. Its main screen is the
**genuine OpenCode web interface** produced by OpenCode itself (`opencode web`), shown in a
WebView. No clone or re-implementation of the UI is used.

The app installs a private Ubuntu 24.04 rootfs and runs OpenCode inside it with a bundled
`proot` — it does **not** depend on Termux apt packages, which are built for the
`com.termux` application id and cannot be installed into a renamed fork.

## How it works

1. **Launch** → `NocodeSetupActivity` (the launcher). If setup was already completed, the app
   re-verifies the environment and opens the OpenCode UI.
2. **Configure Auto** — installs the pre-patched Termux bootstrap, then starts
   `NocodeSetupService` (a foreground service with a wake lock, so the setup survives
   backgrounding and the screen turning off):
   - downloads `ubuntu-base-24.04.5-base-arm64.tar.gz` (~30 MB, official size and SHA-256
     verified) with the Android/Java HTTP stack, trying `cdimage.ubuntu.com`, Tsinghua,
     Aliyun, USTC and Nanjing University in turn
   - extracts it to `$PREFIX/var/lib/nocode/ubuntu` **in Java** (`NocodeTarExtractor`), not
     with the device `tar` binary; setuid bits, symlinks and hard links are handled
   - then runs `apt-get` + the official OpenCode installer inside the rootfs with `proot`
   A progress bar, the notification and the live install log show every stage.
3. **Configure Manually** — installs the bootstrap and opens the normal terminal. Run
   `nocode-install` there to perform the exact same automatic setup; return to the app
   afterwards and it will detect the finished environment.
4. **After setup** the OpenCode web UI is the main screen (`NocodeMainActivity`). Every app
   open re-checks the environment, starts the OpenCode server if needed, then loads the UI.
5. **Settings → "Go to Terminal"** opens the terminal at any time. The **x** button in the
   terminal's top-right corner returns to the OpenCode UI (or to the progress screen before
   setup is done).

## Bundled runtime

`app/src/main/cpp/bootstrap-aarch64.zip` is an upstream Termux bootstrap whose hardcoded
`/data/data/com.termux` paths were byte-patched to `/data/data/com.nocode` (same length), and
which additionally contains:

| Path | Content |
|---|---|
| `bin/proot` | Termux proot 5.1.107 (aarch64) |
| `libexec/proot/loader` | proot loader |
| `lib/libtalloc.so.2.5.0` | libtalloc dependency |
| `lib/libandroid-shmem.so` | libandroid-shmem dependency |

`libtermux-bootstrap.so` embeds that zip and is prebuilt with the Termux clang toolchain
(`aarch64-linux-android24`), because the official NDK host tools are x86_64-only.

**Do not let Gradle re-download the bootstrap:** `downloadBootstraps` is intentionally
disconnected from the build in `app/build.gradle`; running it would overwrite the patched zip.

## Identity changes from upstream Termux

- `applicationId` / sharedUserId: `com.nocode` (installs alongside a normal Termux app)
- App name: **NO CODE**, with the NO CODE logo (launcher, adaptive icon, banner)
- Prefix: `/data/data/com.nocode/files/usr`
- Java packages stay `com.termux.*`; component names are therefore
  `com.nocode/com.termux.app.TermuxActivity` etc.
- `TermuxConstants.TERMUX_ACTIVITY_NAME`, `TERMUX_SERVICE_NAME`,
  `TERMUX_SETTINGS_ACTIVITY_NAME` and `RUN_COMMAND_SERVICE_NAME` are hardcoded to the
  `com.termux.app.*` class names (they cannot be derived from the package id).

## OpenCode server

- Runs inside the proot Ubuntu: `opencode web --hostname 127.0.0.1 --port 4096`
- HTTP basic auth: username `opencode`, password `nocode-local`
  (`OPENCODE_SERVER_USERNAME` / `OPENCODE_SERVER_PASSWORD`); the WebView answers the 401
  automatically.
- Change the credentials in `NocodeManager.OPENCODE_USERNAME` / `OPENCODE_PASSWORD` and in
  `SERVER_SCRIPT`.

## Installer diagnostics

The setup screen has a **Show log** button, and a failed setup opens a dialog with the exact
error plus the tail of the log. **Copy log** copies it to the clipboard; **Save log** writes it
to `Android/data/com.nocode/files/logs/nocode-install.log`.

The same log is always at `$PREFIX/tmp/nocode_install.log` and contains the download source,
HTTP errors, archive size/checksum results, extraction errors, `apt` and OpenCode output.

The bottom of the setup screen shows the build label (e.g. `build 7 · 2026-11-18`) so a
stale installed APK is immediately recognisable.

### Build 5: false "failure" after a successful install

The build-3 install log ended with `[100%] DONE|Setup complete!` and `Setup script exit code: 0`,
yet the app reported failure. Cause and fix:

- `TermuxTask` only sets `ExecutionState.SUCCESS` when **no** `TermuxTaskClient` callback is
  registered. The setup service registers one, so `result.isSuccessful()` stayed `false` even
  for exit code 0. The service now reads `resultData.exitCode`, treats `0` as success and sets
  `SUCCESS` itself.
- Progress polling called `publishProgress()` which rewrites the status file, erasing the
  `DONE|` prefix the script writes at the end. Polling now passes `persist = false`, so the
  script keeps ownership of the status file.
- A completed install is also detected from the filesystem (`hasCompletedEnvironment()`:
  done-marker + rootfs + `opencode` binary), so an app that was killed mid-setup reopens the
  OpenCode UI instead of trying to install again.
- `checkEnvironment()` had the same `isSuccessful()` problem, so the post-setup verification
  always reported a broken environment. It now uses the script output (`OK`) instead.
- A stale server handle (process died in the background) is now discarded before starting the
  server again.
- If `tmp/nocode_setup_done` was cleaned away but the rootfs and OpenCode binary are present,
  the marker is recreated instead of forcing a repair.

### Build 6: the black screen

The setup finished, the OpenCode server answered, the page loaded — and the screen stayed
black. Cause: `NocodeMainActivity` started the WebView with `setVisibility(INVISIBLE)` and the
success path only hid the loading overlay; nothing ever made the WebView visible again, so the
black window background was all that was left.

Fixes in this build:

- The WebView is revealed in `onPageFinished`; a failed load can no longer reveal an empty page.
- Every failure path now shows readable text with **Retry**, **Open in browser** and
  **Copy log** instead of a black screen (plus a 60 s watchdog when a page never finishes).
- WebView errors, HTTP errors (401/403/5xx), console errors and renderer crashes are written
  to the app log, so a failure is always explainable.
- The server command is chosen from the installed version: `opencode web` for 1.x,
  `opencode serve` for 2.x, and its output is appended to `$PREFIX/tmp/nocode_server.log`.
- The main screen keeps the display awake, so a long answer is not cut off by the screen
  turning off.

### Build 7: mobile tuning of the real web UI

The page itself stays the genuine OpenCode UI; only the WebView/Android side is adapted:

- `windowSoftInputMode="adjustResize"` on the main activity, so the on-screen keyboard
  resizes the page instead of covering the prompt input (the UI's viewport meta already
  carries `interactive-widget=resizes-content`).
- `loadWithOverviewMode=false`: the responsive UI is laid out at the real device width and is
  no longer zoomed out to fit a "desktop" page.
- `textZoom = 110`: the web UI's 13px base text is a little larger and easier to read on a
  phone; the layout still reflows, and pinch-zoom stays available.
- The status bar and navigation bar take the colour of the web UI background (light or dark,
  detected after the page loads) with matching icon brightness, so the app looks native in
  both themes.
- `<input type="file">` in the UI now works: `onShowFileChooser` opens the Android file
  picker (single or multiple files) and returns the selection to the page.

If the Java download fails, the setup script retries with `curl` + `tar` (same mirrors, size
and `gzip -t` verification) as a fallback, so the two paths are independent.

## Key files

| File | Purpose |
|---|---|
| `app/src/main/java/com/termux/app/nocode/NocodeManager.java` | State, setup scripts, server management |
| `app/src/main/java/com/termux/app/nocode/NocodeRootfs.java` | Ubuntu download (mirrors, size + SHA-256) and Java extraction |
| `app/src/main/java/com/termux/app/nocode/NocodeTarExtractor.java` | Pure-Java ustar/PAX tar extractor |
| `app/src/main/java/com/termux/app/nocode/NocodeSetupService.java` | Foreground service + wake lock running the setup |
| `app/src/main/java/com/termux/app/nocode/NocodeLog.java` | Shared install log |
| `app/src/main/java/com/termux/app/nocode/NocodeSetupActivity.java` | Setup screen (Auto/Manual + progress) |
| `app/src/main/java/com/termux/app/nocode/NocodeMainActivity.java` | OpenCode web UI (WebView) |
| `app/src/main/java/com/termux/app/TermuxInstaller.java` | Bootstrap installation |
| `app/src/main/res/xml/root_preferences.xml` | "Go to Terminal" entry in Settings |
| `termux-shared/.../TermuxConstants.java` | Package name / prefix constants |

## Building

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-arm64
export ANDROID_SDK_ROOT=/root/android-sdk   # platforms;android-30, build-tools;30.0.3, ndk;23.0.7599858
./gradlew clean assembleDebug
```

Output: `app/build/outputs/apk/debug/nocode-app_debug_universal.apk`

Note for this build host (aarch64 Termux proot): `aapt2` is overridden via
`android.aapt2FromMavenOverride=/root/tools/bin/aapt2` (qemu wrapper) and the NDK
`llvm-strip` is wrapped the same way. `./gradlew clean` deletes the bootstrap zips, so
restore `app/src/main/cpp/bootstrap-aarch64.zip` before rebuilding.

## Notes

- Setup log inside the app: `$PREFIX/tmp/nocode_install.log` (also viewable/copyable in the app)
- Ubuntu archive cache: `$PREFIX/tmp/nocode-ubuntu.tar.gz` (deleted after a successful install)
- Status marker: `$PREFIX/tmp/nocode_setup_done`
- Ubuntu rootfs: `$PREFIX/var/lib/nocode/ubuntu`
- The terminal is a real Termux terminal, but `pkg install` from the Termux repository is not
  supported by this fork (packages are hardcoded for `com.termux`). Everything NO CODE needs
  is bundled; use apt *inside the Ubuntu rootfs* for extra Linux tools.

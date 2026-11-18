package com.termux.app.nocode;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.shell.TermuxTask;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NO CODE app manager: tracks setup state, runs the auto-setup scripts
 * (Ubuntu rootfs -> OpenCode) and manages the OpenCode web UI server.
 */
public final class NocodeManager {

    public static final String LOG_TAG = "NocodeManager";

    /** Fixed local port the OpenCode web UI is served on inside the proot Ubuntu. */
    public static final int OPENCODE_PORT = 4096;
    /** Fixed password for the local OpenCode web UI server. */
    public static final String OPENCODE_PASSWORD = "nocode-local";
    /** Fixed username for the local OpenCode web UI server basic auth. */
    public static final String OPENCODE_USERNAME = "opencode";

    /** Ubuntu rootfs directory (relative to $PREFIX). */
    public static final String ROOTFS_RELATIVE_PATH = "var/lib/nocode/ubuntu";

    public static final String PREFS_NAME = "nocode_app_prefs";
    public static final String PREF_SETUP_STATE = "setup_state";
    public static final String PREF_SETUP_MODE = "setup_mode";
    public static final String PREF_LAST_ERROR = "last_error";

    /** Build identifier shown on the setup screen, so the running build is always identifiable. */
    public static final String BUILD_LABEL = "build 7 · 2026-11-18";

    /** Setup states. */
    public static final int STATE_NONE = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_DONE = 2;
    /** User chose manual setup; terminal open, waiting for them to finish. */
    public static final int STATE_MANUAL_PENDING = 3;

    public static final String MODE_AUTO = "auto";
    public static final String MODE_MANUAL = "manual";

    static final String STATUS_FILE_RELATIVE = "tmp/nocode_install_status.txt";
    static final String DONE_FILE_RELATIVE = "tmp/nocode_setup_done";

    /** Long running task holding the OpenCode web server inside proot Ubuntu. */
    private static TermuxTask sOpenCodeServerTask;
    /** Guards against starting the OpenCode server twice. */
    private static volatile boolean sServerStartRequested;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /** Listeners currently showing setup progress. */
    private static final CopyOnWriteArrayList<SetupCallback> sSetupCallbacks = new CopyOnWriteArrayList<>();
    private static volatile boolean sSetupRunning;
    private static volatile int sLastProgress = -1;
    private static volatile String sLastMessage = "";

    private NocodeManager() {}

    public static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getSetupState(Context context) {
        return getPreferences(context).getInt(PREF_SETUP_STATE, STATE_NONE);
    }

    public static void setSetupState(Context context, int state, String mode) {
        SharedPreferences.Editor editor = getPreferences(context).edit();
        editor.putInt(PREF_SETUP_STATE, state);
        if (mode != null) editor.putString(PREF_SETUP_MODE, mode);
        editor.apply();
    }

    public static String getSetupMode(Context context) {
        return getPreferences(context).getString(PREF_SETUP_MODE, MODE_AUTO);
    }

    public static void setLastError(Context context, String message) {
        SharedPreferences.Editor editor = getPreferences(context).edit();
        if (message == null) editor.remove(PREF_LAST_ERROR);
        else editor.putString(PREF_LAST_ERROR, message);
        editor.apply();
    }

    public static String getLastError(Context context) {
        return getPreferences(context).getString(PREF_LAST_ERROR, null);
    }

    /**
     * True when a previous run left a complete environment, even if the state was not saved
     * (e.g. the app was killed right after the setup script finished).
     */
    public static boolean hasCompletedEnvironment() {
        if (!NocodeRootfs.isInstalled()) return false;
        File marker = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, DONE_FILE_RELATIVE);
        if (marker.isFile()) return true;
        // The done marker lives in tmp/; if it was cleaned away fall back to the installed binary.
        File rootfs = NocodeRootfs.rootfsDir();
        return new File(rootfs, "root/.opencode/bin/opencode").isFile()
            || new File(rootfs, "root/.local/bin/opencode").isFile()
            || new File(rootfs, "usr/local/bin/opencode").exists();
    }

    /** Recreates the done marker when the environment is complete but the marker is missing. */
    static void repairDoneMarker() {
        File marker = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, DONE_FILE_RELATIVE);
        if (marker.isFile()) return;
        try {
            if (hasCompletedEnvironment()) {
                File parent = marker.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                marker.createNewFile();
            }
        } catch (Exception e) {
            NocodeLog.append("Could not restore the done marker: " + e);
        }
    }

    public static boolean isSetupDone(Context context) {
        return getSetupState(context) == STATE_DONE;
    }

    public static boolean isSetupRunning() {
        return sSetupRunning;
    }

    public static int getLastProgress() {
        return sLastProgress;
    }

    public static String getLastMessage() {
        return sLastMessage;
    }

    /** Open the app main screen (OpenCode UI) or the setup screen, depending on setup state. */
    public static void routeToMainScreen(Context context) {
        if (isSetupDone(context)) {
            Intent intent = new Intent(context, NocodeMainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } else {
            Intent intent = new Intent(context, NocodeSetupActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        }
    }

    /**
     * Progress/status listener for the auto setup.
     */
    public interface SetupCallback {
        /** Called on the UI thread with a progress percentage (0-100) and a status message. */
        void onProgress(int percentage, String message);
        /** Called on the UI thread when the setup finishes. */
        void onFinish(boolean success, String message);
    }

    public static void addSetupCallback(@NonNull SetupCallback callback) {
        if (sSetupCallbacks.contains(callback)) return;
        sSetupCallbacks.add(callback);
        final int progress = sLastProgress;
        final String message = sLastMessage;
        if (progress >= 0) {
            sMainHandler.post(() -> callback.onProgress(progress, message));
        }
    }

    public static void removeSetupCallback(@NonNull SetupCallback callback) {
        sSetupCallbacks.remove(callback);
    }

    /** Updates the persisted status file, the setup screen and the notification. */
    static void publishProgress(int percentage, String message) {
        publishProgress(percentage, message, true);
    }

    /**
     * @param persist when false the status file written by the shell script is left alone
     *                (the script owns it during its own steps and writes "DONE|..." at the end)
     */
    static void publishProgress(int percentage, String message, boolean persist) {
        sLastProgress = percentage;
        sLastMessage = message;
        if (persist) writeStatusFile(percentage, message);
        sMainHandler.post(() -> {
            for (SetupCallback callback : sSetupCallbacks) {
                callback.onProgress(percentage, message);
            }
        });
        NocodeSetupService.updateNotification(percentage, message);
    }

    static void publishFinish(boolean success, String message) {
        sSetupRunning = false;
        sLastProgress = success ? 100 : 0;
        sLastMessage = message;
        sMainHandler.post(() -> {
            for (SetupCallback callback : sSetupCallbacks) {
                callback.onFinish(success, message);
            }
        });
    }

    /**
     * Starts the full automatic setup. The actual work happens in
     * {@link NocodeSetupService} (foreground service + wake lock), so the setup keeps
     * running while the app is in the background or the screen is off.
     */
    public static void runAutoSetup(@NonNull Context context, @NonNull SetupCallback callback) {
        addSetupCallback(callback);
        if (sSetupRunning) {
            return;
        }
        sSetupRunning = true;
        sLastProgress = 0;
        sLastMessage = "Starting setup…";

        setSetupState(context, STATE_RUNNING, MODE_AUTO);
        NocodeLog.clear();
        NocodeLog.append("NO CODE auto setup started (" + BUILD_LABEL + ")");
        writeInstallHelper(context);
        writeStatusFile(2, "Starting setup…");
        NocodeSetupService.start(context);
    }

    /** Creates (and chmods) one of the app's shell scripts and returns the file. */
    static File writeScriptFile(@NonNull Context context, @NonNull String name,
                                @NonNull String contents) {
        File scriptsDir = new File(context.getFilesDir(), "nocode_scripts");
        if (!scriptsDir.exists()) scriptsDir.mkdirs();
        // The shell scripts run with $FILES/home as their working directory. The
        // bootstrap does not create it, so make sure it exists before starting a script.
        File home = new File(TermuxConstants.TERMUX_FILES_DIR_PATH);
        if (home.isDirectory()) {
            File homeDir = new File(home, "home");
            if (!homeDir.isDirectory()) homeDir.mkdirs();
        }
        File scriptFile = new File(scriptsDir, name + ".sh");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(scriptFile)) {
            fos.write(contents.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write script " + name, e);
            return null;
        }
        scriptFile.setExecutable(true, false);
        return scriptFile;
    }

    /**
     * Runs one of the app's scripts asynchronously. If client is null, no exit callback
     * is delivered (fire and forget).
     */
    static TermuxTask startScript(@NonNull Context context, @NonNull String name,
                                  @NonNull String script, TermuxTask.TermuxTaskClient client) {
        File scriptFile = writeScriptFile(context, name, script);
        if (scriptFile == null) return null;

        ExecutionCommand executionCommand = new ExecutionCommand(nextExecutionId(),
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
            new String[]{scriptFile.getAbsolutePath()}, null,
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/home", true, false);
        executionCommand.commandLabel = "NO CODE " + name;
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL;

        TermuxTask termuxTask = TermuxTask.execute(context, executionCommand, client,
            new TermuxShellEnvironmentClient(), false);
        if (termuxTask == null) {
            Logger.logError(LOG_TAG, "Failed to start NO CODE script " + name
                + " (is the bootstrap installed?)");
        }
        return termuxTask;
    }

    /**
     * Writes the setup script to $PREFIX/bin/nocode-install so the same automatic
     * setup can also be started by the user from the terminal (manual mode).
     */
    public static void writeInstallHelper(Context context) {
        File binDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        if (!binDir.isDirectory()) return;
        File helper = new File(binDir, "nocode-install");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(helper)) {
            fos.write(AUTO_SETUP_SCRIPT.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write nocode-install helper", e);
            return;
        }
        helper.setExecutable(true, false);
    }

    public static void markSetupDone(Context context, boolean done, String mode) {
        setSetupState(context, done ? STATE_DONE : STATE_NONE, mode);
        try {
            File marker = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, DONE_FILE_RELATIVE);
            if (done) {
                marker.getParentFile().mkdirs();
                if (!marker.exists()) marker.createNewFile();
            } else if (marker.exists()) {
                marker.delete();
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update done marker", e);
        }
    }

    /** Returns a monotonically increasing execution id. */
    private static int sExecutionId = 1000;
    private static int nextExecutionId() {
        return ++sExecutionId;
    }

    static void writeStatusFile(int percentage, String message) {
        try {
            File status = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, STATUS_FILE_RELATIVE);
            File parent = status.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            String line = percentage + "|" + message.replace('\n', ' ') + "\n";
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(status, false)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    /** Reads the last "pct|msg" line of the status file; returns [pct, msg] or null. */
    public static String[] readLastStatus(Context context) {
        try {
            File status = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, STATUS_FILE_RELATIVE);
            if (!status.exists()) return null;
            String text = new String(readAll(status), StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.contains("|")) {
                    String[] parts = line.split("\\|", 2);
                    try {
                        Integer.parseInt(parts[0].trim());
                        return new String[]{parts[0].trim(), parts.length > 1 ? parts[1] : ""};
                    } catch (NumberFormatException ignored) {}
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(File file) throws Exception {
        InputStream is = new java.io.FileInputStream(file);
        byte[] buffer = new byte[(int) file.length()];
        int read = is.read(buffer);
        is.close();
        if (read != buffer.length) {
            Scanner scanner = new Scanner(file, "UTF-8").useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next().getBytes(StandardCharsets.UTF_8) : new byte[0];
        }
        return buffer;
    }

    /** Runs only the fast environment verification script; returns true if everything is present. */
    public interface CheckCallback {
        void onResult(boolean ok, String message);
    }

    public static void checkEnvironment(@NonNull Context context, @NonNull CheckCallback callback) {
        repairDoneMarker();
        startScript(context, "nocode_check", CHECK_SCRIPT, new TermuxTask.TermuxTaskClient() {
            @Override
            public void onTermuxTaskExited(TermuxTask termuxTask) {
                ExecutionCommand ec = termuxTask.getExecutionCommand();
                // TermuxTask does not set ExecutionState.SUCCESS when a client callback is
                // registered, so the script output is the source of truth here.
                boolean ok = ec != null && ec.resultData != null
                    && ec.resultData.stdout != null
                    && ec.resultData.stdout.toString().trim().endsWith("OK");
                String message = ok ? "Environment ready"
                    : (ec != null && ec.resultData != null && ec.resultData.stderr != null
                        ? ec.resultData.stderr.toString().trim() : "Environment not ready");
                sMainHandler.post(() -> callback.onResult(ok, message));
            }
        });
    }

    /**
     * Ensures the OpenCode web UI server is running on 127.0.0.1:PORT, then calls back.
     * The server runs inside the proot Ubuntu as a long lived background task.
     */
    public interface ServerCallback {
        void onServerReady(boolean ready, String message);
    }

    public static void ensureServerRunning(@NonNull Context context, @NonNull ServerCallback callback) {
        new Thread(() -> {
            if (isServerUp()) {
                sServerStartRequested = false;
                sMainHandler.post(() -> callback.onServerReady(true, null));
                return;
            }
            synchronized (NocodeManager.class) {
                if (sOpenCodeServerTask != null && !isServerTaskAlive()) {
                    // The server process died (e.g. the app was killed in the background).
                    sOpenCodeServerTask = null;
                }
                if (sOpenCodeServerTask == null && !sServerStartRequested) {
                    sServerStartRequested = true;
                    sOpenCodeServerTask = startScript(context, "nocode_opencode_server", SERVER_SCRIPT, null);
                }
            }
            // Wait for the server to start responding (first start can be slow on a phone)
            for (int i = 0; i < 120; i++) {
                if (isServerUp()) {
                    sServerStartRequested = false;
                    sMainHandler.post(() -> callback.onServerReady(true, null));
                    return;
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
            sServerStartRequested = false;
            NocodeLog.append("OpenCode server did not answer on 127.0.0.1:" + OPENCODE_PORT
                + " within 60s");
            File serverLog = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, SERVER_LOG_RELATIVE);
            if (serverLog.isFile()) {
                String tail = NocodeLog.tailFile(serverLog, 25);
                if (!tail.isEmpty()) NocodeLog.append("Server log:\n" + tail);
            }
            sMainHandler.post(() -> callback.onServerReady(false, "Could not start the OpenCode web server"));
        }, "NocodeServerWait").start();
    }

    /** Returns true if the OpenCode server answers on 127.0.0.1:PORT. */
    public static boolean isServerUp() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://127.0.0.1:" + OPENCODE_PORT + "/");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(800);
            connection.setReadTimeout(800);
            int code = connection.getResponseCode();
            return code > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isServerTaskAlive() {
        try {
            sOpenCodeServerTask.getProcess().exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void stopServer() {
        synchronized (NocodeManager.class) {
            if (sOpenCodeServerTask != null) {
                try { sOpenCodeServerTask.getProcess().destroy(); } catch (Exception ignored) {}
                sOpenCodeServerTask = null;
            }
        }
    }

    /*
     * Scripts.
     *
     * The setup is self-contained: the prebuilt bootstrap already contains
     * `proot` and its libraries, and Ubuntu is downloaded straight from
     * cdimage.ubuntu.com (or one of its mirrors). Termux apt packages are
     * intentionally NOT used, because they are built for the `com.termux`
     * application id.
     *
     * The app downloads and unpacks the rootfs in Java (see NocodeRootfs). The
     * shell part below still has a download/extract fallback so that the same
     * script also works when the user runs `nocode-install` manually in the
     * terminal.
     */

    /** The full auto setup script. Writes progress as "pct|msg" lines to the status file. */
    public static final String AUTO_SETUP_SCRIPT = "" +
        "#!/data/data/com.nocode/files/usr/bin/bash\n" +
        "# NO CODE automatic setup: Ubuntu 24.04 rootfs + OpenCode (no Termux apt packages needed).\n" +
        "PREFIX=/data/data/com.nocode/files/usr\n" +
        "ROOTFS=$PREFIX/var/lib/nocode/ubuntu\n" +
        "STATUS=$PREFIX/tmp/nocode_install_status.txt\n" +
        "LOGF=$PREFIX/tmp/nocode_install.log\n" +
        "URL1=\"https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz\"\n" +
        "URL2=\"https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz\"\n" +
        "URL3=\"https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz\"\n" +
        "SIZE=29936675\n" +
        "mkdir -p \"$PREFIX/tmp\" \"$PREFIX/var/lib/nocode\"\n" +
        "progress() { echo \"$1|$2\" > \"$STATUS\"; echo \"[$1%] $2\" >> \"$LOGF\"; }\n" +
        "fail() { echo \"100|$1\" > \"$STATUS\"; echo \"ERROR: $1\" >> \"$LOGF\"; exit 1; }\n" +
        "\n" +
        "proot_run() {\n" +
        "  \"$PREFIX/bin/proot\" --kill-on-exit --link2symlink -0 -r \"$ROOTFS\" \\\n" +
        "    -b /dev -b /proc -b /sys -b /sdcard:/sdcard -b \"$PREFIX/tmp:/tmp\" \\\n" +
        "    -w /root /usr/bin/env -i \\\n" +
        "      HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
        "      TERM=xterm-256color LANG=C.UTF-8 TMPDIR=/tmp \\\n" +
        "      /bin/bash -c \"$1\"\n" +
        "}\n" +
        "\n" +
        "rootfs_ok() {\n" +
        "  [ -f \"$ROOTFS/etc/passwd\" ] && [ -x \"$ROOTFS/usr/bin/apt-get\" ] && [ -x \"$ROOTFS/usr/bin/bash\" ]\n" +
        "}\n" +
        "\n" +
        "progress 6 \"Checking environment...\"\n" +
        "[ -x \"$PREFIX/bin/proot\" ] || fail \"proot is missing from the app bootstrap.\"\n" +
        "[ -x \"$PREFIX/bin/curl\" ] || fail \"curl is missing from the app bootstrap.\"\n" +
        "\n" +
        "if ! rootfs_ok; then\n" +
        "  TB=\"$PREFIX/tmp/nocode-ubuntu.tar.gz\"\n" +
        "  GOT=0\n" +
        "  for U in \"$URL1\" \"$URL2\" \"$URL3\"; do\n" +
        "    progress 10 \"Downloading Ubuntu...\"\n" +
        "    rm -f \"$TB\"\n" +
        "    if curl -fL --retry 4 --retry-delay 2 --connect-timeout 30 -o \"$TB\" \"$U\" >> \"$LOGF\" 2>&1; then\n" +
        "      N=$(wc -c < \"$TB\" 2>/dev/null || echo 0)\n" +
        "      echo \"Downloaded $N bytes from $U\" >> \"$LOGF\"\n" +
        "      if [ \"$N\" = \"$SIZE\" ] && gzip -t \"$TB\" >> \"$LOGF\" 2>&1; then GOT=1; break; fi\n" +
        "      echo \"Rejected download from $U (size $N, expected $SIZE)\" >> \"$LOGF\"\n" +
        "    fi\n" +
        "  done\n" +
        "  [ \"$GOT\" = \"1\" ] || fail \"Could not download Ubuntu. Check the internet connection and try again.\"\n" +
        "  progress 45 \"Extracting Ubuntu...\"\n" +
        "  rm -rf \"$ROOTFS\"\n" +
        "  mkdir -p \"$ROOTFS\"\n" +
        "  tar --no-same-owner --no-same-permissions --warning=no-unknown-keyword -xzf \"$TB\" -C \"$ROOTFS\" >> \"$LOGF\" 2>&1\n" +
        "  RC=$?\n" +
        "  rm -f \"$TB\"\n" +
        "  if ! rootfs_ok; then\n" +
        "    echo \"tar exit code: $RC\" >> \"$LOGF\"\n" +
        "    fail \"Failed to extract Ubuntu (tar exit $RC). See the install log.\"\n" +
        "  fi\n" +
        "  [ \"$RC\" -eq 0 ] || echo \"WARNING: tar reported errors (exit $RC) but the rootfs is usable\" >> \"$LOGF\"\n" +
        "fi\n" +
        "\n" +
        "progress 80 \"Configuring Ubuntu...\"\n" +
        "mkdir -p \"$ROOTFS/etc\" \"$ROOTFS/dev\" \"$ROOTFS/proc\" \"$ROOTFS/sys\" \"$ROOTFS/tmp\"\n" +
        "if [ -s \"$PREFIX/etc/resolv.conf\" ]; then\n" +
        "  cp \"$PREFIX/etc/resolv.conf\" \"$ROOTFS/etc/resolv.conf\"\n" +
        "else\n" +
        "  printf 'nameserver 8.8.8.8\\nnameserver 1.1.1.1\\n' > \"$ROOTFS/etc/resolv.conf\"\n" +
        "fi\n" +
        "echo nocode > \"$ROOTFS/etc/hostname\"\n" +
        "\n" +
        "progress 84 \"Updating Ubuntu package lists...\"\n" +
        "proot_run '\n" +
        "set -e\n" +
        "export DEBIAN_FRONTEND=noninteractive\n" +
        "for i in 1 2 3; do apt-get update -y && break; sleep 3; done\n" +
        "' >> \"$LOGF\" 2>&1 || fail \"Ubuntu package list update failed. Check the internet connection.\"\n" +
        "\n" +
        "progress 88 \"Installing tools inside Ubuntu...\"\n" +
        "proot_run '\n" +
        "set -e\n" +
        "export DEBIAN_FRONTEND=noninteractive\n" +
        "apt-get install -y --no-install-recommends curl ca-certificates unzip xz-utils tar\n" +
        "' >> \"$LOGF\" 2>&1 || fail \"Installing the Ubuntu tools failed.\"\n" +
        "\n" +
        "progress 92 \"Downloading OpenCode...\"\n" +
        "proot_run '\n" +
        "set -e\n" +
        "export HOME=/root\n" +
        "for i in 1 2 3; do\n" +
        "  if command -v opencode >/dev/null 2>&1; then break; fi\n" +
        "  curl -fsSL https://opencode.ai/install | bash || true\n" +
        "  command -v opencode >/dev/null 2>&1 && break\n" +
        "  sleep 3\n" +
        "done\n" +
        "' >> \"$LOGF\" 2>&1 || fail \"Downloading OpenCode failed.\"\n" +
        "\n" +
        "progress 95 \"Configuring OpenCode...\"\n" +
        "proot_run '\n" +
        "set -e\n" +
        "export HOME=/root\n" +
        "OC=$(command -v opencode || true)\n" +
        "if [ -z \"$OC\" ]; then OC=$(find /root -maxdepth 4 -type f -name opencode 2>/dev/null | head -n 1); fi\n" +
        "if [ -z \"$OC\" ]; then echo \"opencode binary not found\"; exit 2; fi\n" +
        "ln -sf \"$OC\" /usr/local/bin/opencode\n" +
        "printf \"#!/bin/sh\\nexit 0\\n\" > /usr/local/bin/xdg-open\n" +
        "chmod +x /usr/local/bin/xdg-open\n" +
        "opencode --version\n" +
        "' >> \"$LOGF\" 2>&1 || fail \"OpenCode could not be configured.\"\n" +
        "\n" +
        "progress 98 \"Verifying OpenCode...\"\n" +
        "\"$PREFIX/bin/proot\" --kill-on-exit --link2symlink -0 -r \"$ROOTFS\" -b /dev -b /proc -b /sys -w /root \\\n" +
        "  /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
        "  /bin/bash -c 'command -v opencode >/dev/null' >> \"$LOGF\" 2>&1 \\\n" +
        "  || fail \"OpenCode was installed but cannot be started.\"\n" +
        "\n" +
        "touch \"$PREFIX/tmp/nocode_setup_done\"\n" +
        "progress 100 \"DONE|Setup complete!\"\n" +
        "exit 0\n";

    /** Quick environment verification script. Prints OK if everything is installed. */
    public static final String CHECK_SCRIPT = "" +
        "#!/data/data/com.nocode/files/usr/bin/bash\n" +
        "PREFIX=/data/data/com.nocode/files/usr\n" +
        "ROOTFS=$PREFIX/var/lib/nocode/ubuntu\n" +
        "[ -x \"$PREFIX/bin/proot\" ] || { echo MISSING; exit 0; }\n" +
        "[ -x \"$ROOTFS/bin/bash\" ] || { echo MISSING; exit 0; }\n" +
        "[ -f \"$PREFIX/tmp/nocode_setup_done\" ] || { echo MISSING; exit 0; }\n" +
        "\"$PREFIX/bin/proot\" --kill-on-exit --link2symlink -0 -r \"$ROOTFS\" \\\n" +
        "  -b /dev -b /proc -b /sys -w /root \\\n" +
        "  /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
        "  /bin/bash -c 'command -v opencode >/dev/null' >/dev/null 2>&1 &\n" +
        "PID=$!\n" +
        "( sleep 45; kill -9 \"$PID\" 2>/dev/null ) &\n" +
        "WATCH=$!\n" +
        "wait \"$PID\" 2>/dev/null\n" +
        "RC=$?\n" +
        "kill \"$WATCH\" 2>/dev/null\n" +
        "[ \"$RC\" -eq 0 ] || { echo MISSING; exit 0; }\n" +
        "echo OK\n" +
        "exit 0\n";

    /** OpenCode server output, readable from the app for diagnostics. */
    public static final String SERVER_LOG_RELATIVE = "tmp/nocode_server.log";

    /** Starts the OpenCode web UI server inside Ubuntu (long running). */
    public static final String SERVER_SCRIPT = "" +
        "#!/data/data/com.nocode/files/usr/bin/bash\n" +
        "PREFIX=/data/data/com.nocode/files/usr\n" +
        "ROOTFS=$PREFIX/var/lib/nocode/ubuntu\n" +
        "LOG=$PREFIX/tmp/nocode_server.log\n" +
        "echo \"=== server start $(date) ===\" >> \"$LOG\"\n" +
        "exec \"$PREFIX/bin/proot\" --kill-on-exit --link2symlink -0 -r \"$ROOTFS\" \\\n" +
        "  -b /dev -b /proc -b /sys -b /sdcard:/sdcard -b \"$PREFIX/tmp:/tmp\" \\\n" +
        "  -w /root /usr/bin/env -i \\\n" +
        "    HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n" +
        "    TERM=xterm-256color LANG=C.UTF-8 TMPDIR=/tmp \\\n" +
        "    OPENCODE_SERVER_USERNAME=opencode OPENCODE_SERVER_PASSWORD=nocode-local \\\n" +
        "    /bin/bash -c '\n" +
        "      LOG=/tmp/nocode_server.log\n" +
        "      # opencode 1.x serves the web UI with `web`, 2.x with `serve`.\n" +
        "      V=$(opencode --version 2>/dev/null | head -n1)\n" +
        "      SUB=serve\n" +
        "      if opencode web --help >/dev/null 2>&1; then SUB=web; fi\n" +
        "      case \"$V\" in 1.*) SUB=web ;; esac\n" +
        "      echo \"opencode $V -> opencode $SUB\" >> \"$LOG\"\n" +
        "      exec opencode $SUB --hostname 127.0.0.1 --port 4096 >> \"$LOG\" 2>&1\n" +
        "    '\n";
}

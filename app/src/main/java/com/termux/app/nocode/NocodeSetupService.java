package com.termux.app.nocode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;
import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.shell.TermuxTask;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the NO CODE automatic setup in the background.
 *
 * <p>The heavy work (downloading and unpacking the Ubuntu rootfs, then installing
 * OpenCode) takes minutes on a phone. Doing it in a foreground service with a wake
 * lock means it survives the app being backgrounded and the screen turning off, and
 * the progress is visible in the notification as well as on the setup screen.</p>
 */
public class NocodeSetupService extends Service {

    private static final String LOG_TAG = "NocodeSetupService";

    private static final String CHANNEL_ID = "nocode_setup";
    private static final int NOTIFICATION_ID = 0x0C0DE;

    private static volatile NocodeSetupService sInstance;

    private PowerManager.WakeLock mWakeLock;
    private Thread mWorker;
    private NotificationManager mNotificationManager;

    static void start(Context context) {
        Intent intent = new Intent(context, NocodeSetupService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Called by NocodeManager whenever the progress changes. */
    static void updateNotification(int percentage, String message) {
        NocodeSetupService instance = sInstance;
        if (instance != null) {
            instance.showNotification(percentage, message, false);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(0, "Preparing setup…", false));

        if (mWorker != null) {
            return START_NOT_STICKY;
        }

        acquireWakeLock();

        mWorker = new Thread(this::runSetup, "NocodeSetup");
        mWorker.start();
        return START_NOT_STICKY;
    }

    private void runSetup() {
        boolean success = false;
        String failureMessage = null;
        try {
            NocodeManager.publishProgress(4, "Preparing Ubuntu…");

            // 1. Rootfs: downloaded and unpacked in Java, verified against the official
            //    size and SHA-256. If this fails, the shell script below retries the
            //    same step with curl + tar as a fallback.
            try {
                NocodeRootfs.ensure(NocodeManager::publishProgress);
            } catch (Exception e) {
                NocodeLog.append("Java rootfs installation failed: " + e.getMessage());
                NocodeManager.publishProgress(8,
                    "Retrying Ubuntu download with the terminal tools…");
            }

            // 2. Everything else (apt, tools, OpenCode) runs inside the Ubuntu proot.
            NocodeManager.publishProgress(80, "Installing OpenCode…");
            boolean scriptOk = runSetupScript();
            NocodeLog.append("Setup script finished: " + (scriptOk ? "success" : "failure"));

            success = scriptOk && NocodeRootfs.isInstalled()
                && new File(com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                    NocodeManager.DONE_FILE_RELATIVE).isFile();

            if (!success) {
                String[] status = NocodeManager.readLastStatus(this);
                if (status != null && status[1] != null && status[1].startsWith("DONE")) {
                    success = true;
                }
                if (!success) {
                    failureMessage = status != null && status[1] != null && !status[1].isEmpty()
                        ? status[1] : "Setup did not complete. See the install log.";
                }
            }
        } catch (Exception e) {
            NocodeLog.append("Setup crashed: " + e);
            failureMessage = e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (success) {
                NocodeManager.setLastError(this, null);
                NocodeManager.markSetupDone(this, true, NocodeManager.MODE_AUTO);
                NocodeManager.publishProgress(100, "Setup complete!");
                NocodeManager.publishFinish(true, "Setup complete!");
            } else {
                String message = failureMessage != null ? failureMessage : "Setup failed.";
                NocodeManager.setLastError(this, message);
                NocodeManager.markSetupDone(this, false, NocodeManager.MODE_AUTO);
                NocodeManager.publishFinish(false, message);
            }

            showNotification(success ? 100 : 0,
                success ? "Setup complete" : "Setup failed", true);
            releaseWakeLock();
            stopForegroundQuietly();
            stopSelf();
        }
    }

    /** Runs the setup shell script and shows the progress it writes to the status file. */
    private boolean runSetupScript() throws InterruptedException {
        File scriptFile = NocodeManager.writeScriptFile(this, "nocode_auto_setup",
            NocodeManager.AUTO_SETUP_SCRIPT);
        if (scriptFile == null) {
            return false;
        }

        ExecutionCommand command = new ExecutionCommand(2001,
            com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
            new String[]{scriptFile.getAbsolutePath()}, null,
            com.termux.shared.termux.TermuxConstants.TERMUX_FILES_DIR_PATH + "/home",
            true, false);
        command.commandLabel = "NO CODE auto setup";
        command.backgroundCustomLogLevel = com.termux.shared.logger.Logger.LOG_LEVEL_NORMAL;

        final CountDownLatch finished = new CountDownLatch(1);
        final TermuxTask[] taskHolder = new TermuxTask[1];
        final int[] exitCode = {-1};
        taskHolder[0] = TermuxTask.execute(this, command, termuxTask -> {
            // TermuxTask does not set ExecutionState.SUCCESS itself when a client callback is
            // registered; without this isSuccessful() is false even for exit code 0.
            ExecutionCommand result = termuxTask.getExecutionCommand();
            if (result != null && result.resultData != null && result.resultData.exitCode != null) {
                exitCode[0] = result.resultData.exitCode;
                if (exitCode[0] == 0 && !result.isStateFailed()) {
                    result.setState(ExecutionCommand.ExecutionState.SUCCESS);
                }
            }
            finished.countDown();
        }, new TermuxShellEnvironmentClient(), false);

        if (taskHolder[0] == null) {
            NocodeLog.append("Could not start the setup script (is the bootstrap installed?)");
            return false;
        }

        while (!finished.await(700, TimeUnit.MILLISECONDS)) {
            String[] status = NocodeManager.readLastStatus(this);
            if (status != null) {
                try {
                    int percent = Integer.parseInt(status[0]);
                    String message = status[1];
                    if (message.startsWith("DONE|")) message = message.substring(5);
                    if (percent >= 5) {
                        NocodeManager.publishProgress(percent, message, false);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        boolean successful = exitCode[0] == 0;
        NocodeLog.append("Setup script exit code: " + exitCode[0]);
        return successful;
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) return;
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NOCODE:setup");
            mWakeLock.setReferenceCounted(false);
            // Safety timeout: never keep the CPU awake for more than 45 minutes.
            mWakeLock.acquire(45 * 60 * 1000L);
            NocodeLog.append("Wake lock acquired");
        } catch (Exception e) {
            NocodeLog.append("Could not acquire wake lock: " + e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        } catch (Exception ignored) {
        }
        mWakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || mNotificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            "NO CODE setup", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Installation progress for the NO CODE environment");
        channel.setShowBadge(false);
        mNotificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(int percentage, String message, boolean finished) {
        Intent intent = new Intent(this, NocodeSetupActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("NO CODE")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(!finished)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true);

        if (!finished) {
            builder.setProgress(100, Math.max(0, percentage), percentage <= 0);
        }
        return builder.build();
    }

    private void showNotification(int percentage, String message, boolean finished) {
        if (mNotificationManager == null) return;
        try {
            if (finished) {
                mNotificationManager.cancel(NOTIFICATION_ID);
            } else {
                mNotificationManager.notify(NOTIFICATION_ID,
                    buildNotification(percentage, message, false));
            }
        } catch (Exception ignored) {
        }
    }

    private void stopForegroundQuietly() {
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        releaseWakeLock();
        super.onDestroy();
    }
}

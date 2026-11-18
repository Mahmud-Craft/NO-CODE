package com.termux.app.nocode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The NO CODE app launcher screen.
 * Shows the setup UI with "Configure Auto" and "Configure Manually" buttons and a
 * progress bar. After setup is complete, the app opens the OpenCode web UI directly.
 */
public class NocodeSetupActivity extends Activity {

    private static final String LOG_TAG = "NocodeSetupActivity";

    private TextView mStatusText;
    private TextView mPercentText;
    private TextView mLogText;
    private TextView mBuildLabel;
    private ProgressBar mProgressBar;
    private Button mAutoButton;
    private Button mManualButton;
    private Button mLogButton;
    private View mButtonsLayout;
    private ScrollView mLogScroll;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mChecking = false;
    private boolean mLogVisible = false;
    private boolean mListening = false;

    private final Runnable mLogRefresher = new Runnable() {
        @Override
        public void run() {
            if (!mLogVisible) return;
            mLogText.setText(NocodeLog.tail(120));
            mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
            if (NocodeManager.isSetupRunning()) {
                mHandler.postDelayed(this, 1000);
            }
        }
    };

    private final NocodeManager.SetupCallback mSetupCallback = new NocodeManager.SetupCallback() {
        @Override
        public void onProgress(int percentage, String message) {
            showProgress(percentage, message);
        }

        @Override
        public void onFinish(boolean success, String message) {
            mChecking = false;
            if (success) {
                mProgressBar.setIndeterminate(false);
                mProgressBar.setProgress(100);
                mPercentText.setText("100%");
                mStatusText.setText(R.string.nocode_status_complete);
                enterOpenCodeUi();
            } else {
                showFailure(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_nocode_setup);

        ImageView logo = findViewById(R.id.nocode_logo);
        logo.setImageResource(R.drawable.nocode_logo);

        mStatusText = findViewById(R.id.nocode_status_text);
        mPercentText = findViewById(R.id.nocode_percent_text);
        mLogText = findViewById(R.id.nocode_log_text);
        mBuildLabel = findViewById(R.id.nocode_build_label);
        mProgressBar = findViewById(R.id.nocode_progress_bar);
        mAutoButton = findViewById(R.id.nocode_configure_auto);
        mManualButton = findViewById(R.id.nocode_configure_manual);
        mLogButton = findViewById(R.id.nocode_log_button);
        mButtonsLayout = findViewById(R.id.nocode_buttons_layout);
        mLogScroll = findViewById(R.id.nocode_log_scroll);

        mBuildLabel.setText(NocodeManager.BUILD_LABEL);

        mAutoButton.setOnClickListener(v -> startAutoSetup());
        mManualButton.setOnClickListener(v -> startManualSetup());
        mLogButton.setOnClickListener(v -> toggleLog());

        if (NocodeManager.isSetupDone(this) || NocodeManager.hasCompletedEnvironment()) {
            // App opened again after setup: verify the environment quickly, then go to the OpenCode UI
            verifyAndEnterApp();
        } else if (NocodeManager.isSetupRunning()) {
            showRunningState();
            showProgress(NocodeManager.getLastProgress(), NocodeManager.getLastMessage());
        } else {
            if (NocodeManager.getSetupState(this) == NocodeManager.STATE_RUNNING) {
                // The app process was killed while the setup was running.
                NocodeManager.markSetupDone(this, false, NocodeManager.getSetupMode(this));
                showIdleState();
                mStatusText.setText(R.string.nocode_status_interrupted);
            } else {
                showIdleState();
                String lastError = NocodeManager.getLastError(this);
                if (lastError != null && !lastError.isEmpty()) {
                    mStatusText.setText(lastError);
                }
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mListening && !NocodeManager.isSetupDone(this)) {
            NocodeManager.addSetupCallback(mSetupCallback);
            mListening = true;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mListening) {
            NocodeManager.removeSetupCallback(mSetupCallback);
            mListening = false;
        }
        mHandler.removeCallbacks(mLogRefresher);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the manual terminal: check whether the user finished the setup
        if (!NocodeManager.isSetupRunning()
            && NocodeManager.getSetupState(this) == NocodeManager.STATE_MANUAL_PENDING) {
            checkManualProgress();
        }
    }

    private void showIdleState() {
        mButtonsLayout.setVisibility(View.VISIBLE);
        mProgressBar.setVisibility(View.INVISIBLE);
        mPercentText.setVisibility(View.GONE);
        mAutoButton.setEnabled(true);
        mManualButton.setEnabled(true);
        mStatusText.setText(R.string.nocode_status_idle);
        setLogVisible(false);
    }

    private void showRunningState() {
        mButtonsLayout.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.VISIBLE);
        mPercentText.setVisibility(View.VISIBLE);
        setLogVisible(true);
        mLogButton.setText(R.string.nocode_hide_log);
    }

    private void showProgress(int percentage, String message) {
        if (percentage < 0) return;
        if (mButtonsLayout.getVisibility() == View.VISIBLE) {
            showRunningState();
        }
        mProgressBar.setIndeterminate(percentage <= 0);
        if (percentage > 0) mProgressBar.setProgress(percentage);
        mPercentText.setText(percentage + "%");
        if (message != null && !message.isEmpty()) mStatusText.setText(message);
        if (mLogVisible) {
            mHandler.removeCallbacks(mLogRefresher);
            mHandler.post(mLogRefresher);
        }
    }

    private void showFailure(String message) {
        mProgressBar.setIndeterminate(false);
        mPercentText.setVisibility(View.GONE);
        mButtonsLayout.setVisibility(View.VISIBLE);
        setLogVisible(true);
        mLogButton.setText(R.string.nocode_hide_log);
        mStatusText.setText(message);
        showErrorDialog(message);
    }

    /** Shows why the setup failed, with the log tail and buttons to copy/save it. */
    private void showErrorDialog(String message) {
        String log = NocodeLog.tail(40);
        String detail = message;
        if (log != null && !log.isEmpty()) {
            detail += "\n\n" + log;
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.nocode_setup_failed_title)
            .setMessage(detail)
            .setPositiveButton(R.string.nocode_copy_log, (dialog, which) -> copyLog())
            .setNeutralButton(R.string.nocode_save_log, (dialog, which) -> saveLog())
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    private void toggleLog() {
        setLogVisible(!mLogVisible);
    }

    private void setLogVisible(boolean visible) {
        mLogVisible = visible;
        mLogScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
        mLogButton.setText(visible ? R.string.nocode_hide_log : R.string.nocode_show_log);
        mHandler.removeCallbacks(mLogRefresher);
        if (visible) {
            mLogText.setText(NocodeLog.tail(120));
            mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
            if (NocodeManager.isSetupRunning()) {
                mHandler.postDelayed(mLogRefresher, 1000);
            }
        }
    }

    private void copyLog() {
        try {
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("NO CODE log", NocodeLog.tail(400)));
                Toast.makeText(this, R.string.nocode_log_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }

    private void saveLog() {
        String text = NocodeLog.tail(1000);
        File target = null;
        try {
            File external = getExternalFilesDir(null);
            if (external != null) {
                File dir = new File(external, "logs");
                if (dir.isDirectory() || dir.mkdirs()) {
                    target = new File(dir, "nocode-install.log");
                }
            }
            if (target == null) {
                target = new File(getFilesDir(), "nocode-install.log");
            }
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                out.write(text.getBytes("UTF-8"));
            }
            Toast.makeText(this, getString(R.string.nocode_log_saved, target.getAbsolutePath()),
                Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.nocode_log_save_failed, String.valueOf(e.getMessage())),
                Toast.LENGTH_LONG).show();
        }
    }

    /** Fast environment check after setup done; enters the OpenCode UI if everything is fine. */
    private void verifyAndEnterApp() {
        if (mChecking) return;
        mChecking = true;
        mButtonsLayout.setVisibility(View.GONE);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.VISIBLE);
        mPercentText.setVisibility(View.GONE);
        mStatusText.setText(R.string.nocode_status_verifying);

        NocodeManager.checkEnvironment(this, (ok, message) -> {
            mChecking = false;
            if (ok) {
                NocodeManager.markSetupDone(this, true, NocodeManager.getSetupMode(this));
                enterOpenCodeUi();
            } else {
                // Environment broken: let the user repair it (auto re-setup)
                NocodeManager.markSetupDone(this, false, NocodeManager.getSetupMode(this));
                showIdleState();
                mStatusText.setText(R.string.nocode_status_broken);
                setLogVisible(true);
            }
        });
    }

    private void checkManualProgress() {
        if (mChecking) return;
        mChecking = true;
        mButtonsLayout.setVisibility(View.GONE);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.VISIBLE);
        mStatusText.setText(R.string.nocode_status_checking_manual);

        NocodeManager.checkEnvironment(this, (ok, message) -> {
            mChecking = false;
            if (ok) {
                NocodeManager.setSetupState(this, NocodeManager.STATE_DONE, NocodeManager.MODE_MANUAL);
                enterOpenCodeUi();
            } else {
                showIdleState();
                mStatusText.setText(R.string.nocode_status_manual_incomplete);
            }
        });
    }

    private void startAutoSetup() {
        showRunningState();
        mProgressBar.setIndeterminate(true);
        mProgressBar.setProgress(0);
        mPercentText.setText("0%");
        mStatusText.setText(R.string.nocode_status_preparing);
        mAutoButton.setEnabled(false);
        NocodeManager.setLastError(this, null);

        // First make sure the Termux bootstrap is installed, then run the auto setup.
        TermuxInstaller.setupBootstrapIfNeeded(this, () ->
            NocodeManager.runAutoSetup(this, mSetupCallback)
        );
    }

    private void startManualSetup() {
        NocodeManager.setSetupState(this, NocodeManager.STATE_MANUAL_PENDING, NocodeManager.MODE_MANUAL);
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            // Make the same automatic setup available as a terminal command.
            NocodeManager.writeInstallHelper(this);
            mHandler.post(() -> {
                Toast.makeText(this, R.string.nocode_manual_hint, Toast.LENGTH_LONG).show();
                Intent intent = new Intent(this, TermuxActivity.class);
                startActivity(intent);
            });
        });
    }

    private void enterOpenCodeUi() {
        Intent intent = new Intent(this, NocodeMainActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Do not leave the app while configuring; just move to background
        moveTaskToBack(true);
    }
}

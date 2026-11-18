package com.termux.app.nocode;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * The NO CODE install log.
 *
 * <p>Both the Java installer code and the setup shell script append to the same file
 * ({@code $PREFIX/tmp/nocode_install.log}) so that the setup screen can always show what
 * the installer is doing — and, more importantly, why it failed.</p>
 */
final class NocodeLog {

    static final String FILE_RELATIVE_PATH = "tmp/nocode_install.log";

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private NocodeLog() {
    }

    static File logFile() {
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, FILE_RELATIVE_PATH);
    }

    static synchronized void append(String line) {
        String stamped = "[" + TIME.format(new Date()) + "] " + line;
        try {
            File file = logFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((stamped + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
            // Logging must never break the setup itself.
        }
    }

    static synchronized void clear() {
        try {
            File file = logFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                // truncate the file
            }
        } catch (Exception ignored) {
        }
    }

    static synchronized String tail(int maxLines) {
        return tailFile(logFile(), maxLines);
    }

    /** Last {@code maxLines} lines of any log file (used for the OpenCode server output). */
    static synchronized String tailFile(File file, int maxLines) {
        if (file == null || !file.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), "UTF-8"))) {
            ArrayDeque<String> lines = new ArrayDeque<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                if (lines.size() > maxLines) lines.removeFirst();
            }
            StringBuilder builder = new StringBuilder();
            for (String l : lines) builder.append(l).append('\n');
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

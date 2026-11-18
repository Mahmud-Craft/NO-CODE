package com.termux.app.nocode;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Downloads and unpacks the Ubuntu rootfs used by NO CODE.
 *
 * <p>The unpacking is done in Java (see {@link NocodeTarExtractor}) instead of calling
 * the shell {@code tar} binary. On real devices the shell path failed for reasons that
 * were impossible to see from the app, so the app now owns the whole download and
 * extraction step: every mirror is tried, the archive is verified against its official
 * size and SHA-256, and real error messages (HTTP status, disk space, corrupt archive,
 * ...) are shown to the user and written to the install log.</p>
 */
final class NocodeRootfs {

    /** Official size of ubuntu-base-24.04.5-base-arm64.tar.gz. */
    static final long ARCHIVE_SIZE = 29936675L;
    /** Official SHA-256 of ubuntu-base-24.04.5-base-arm64.tar.gz (from SHA256SUMS). */
    static final String ARCHIVE_SHA256 =
        "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2";

    private static final String[][] MIRRORS = {
        {"Ubuntu CDN", "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"},
        {"Tsinghua", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"},
        {"Aliyun", "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"},
        {"USTC", "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"},
        {"Nanjing University", "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"},
    };

    /** Space needed for the rootfs, the downloaded archive, apt caches and OpenCode. */
    private static final long REQUIRED_FREE_SPACE = 900L * 1024 * 1024;

    interface Listener {
        void onProgress(int percentage, String message);
    }

    private NocodeRootfs() {
    }

    static File rootfsDir() {
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, NocodeManager.ROOTFS_RELATIVE_PATH);
    }

    static File archiveFile() {
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "tmp/nocode-ubuntu.tar.gz");
    }

    /** True when a usable Ubuntu rootfs is already present. */
    static boolean isInstalled() {
        File rootfs = rootfsDir();
        return new File(rootfs, "etc/passwd").isFile()
            && new File(rootfs, "usr/bin/apt-get").exists()
            && new File(rootfs, "usr/bin/bash").exists()
            && new File(rootfs, "usr/bin/env").exists();
    }

    /** Makes sure the Ubuntu rootfs exists, downloading and extracting it when needed. */
    static void ensure(Listener listener) throws Exception {
        File rootfs = rootfsDir();
        if (isInstalled()) {
            NocodeLog.append("Ubuntu rootfs already present at " + rootfs);
            listener.onProgress(45, "Ubuntu is already installed");
            return;
        }

        listener.onProgress(6, "Checking available storage…");
        long free = availableBytes(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH));
        String freeText = (free / (1024 * 1024)) + " MB";
        NocodeLog.append("Free storage: " + freeText);
        if (free > 0 && free < REQUIRED_FREE_SPACE) {
            throw new IOException("Not enough free storage: " + freeText
                + " available, about " + (REQUIRED_FREE_SPACE / (1024 * 1024))
                + " MB is needed. Free up some space and try again.");
        }

        File tmpDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "tmp");
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) {
            throw new IOException("Could not create " + tmpDir);
        }
        File archive = archiveFile();

        if (!(archive.isFile() && archive.length() == ARCHIVE_SIZE && ARCHIVE_SHA256.equals(sha256(archive)))) {
            downloadArchive(archive, listener);
        } else {
            NocodeLog.append("Reusing verified Ubuntu archive already on disk");
        }

        listener.onProgress(45, "Extracting Ubuntu…");
        extractArchive(archive, rootfs, listener);
        //noinspection ResultOfMethodCallIgnored
        archive.delete();

        listener.onProgress(79, "Checking Ubuntu installation…");
        if (!isInstalled()) {
            File bash = new File(rootfs, "usr/bin/bash");
            throw new IOException("Ubuntu was extracted but /usr/bin/bash is missing ("
                + (bash.exists() ? "present" : "absent") + "). The download may be incomplete.");
        }
        NocodeLog.append("Ubuntu rootfs installed successfully at " + rootfs);
    }

    private static long availableBytes(File path) {
        try {
            android.os.StatFs stat = new android.os.StatFs(path.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void downloadArchive(File archive, Listener listener) throws IOException {
        File parent = archive.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }

        StringBuilder errors = new StringBuilder();
        for (String[] mirror : MIRRORS) {
            String name = mirror[0];
            String url = mirror[1];
            NocodeLog.append("Downloading Ubuntu from " + name + ": " + url);
            listener.onProgress(10, "Downloading Ubuntu (" + name + ")…");
            try {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
                download(url, archive, listener);
                long length = archive.length();
                if (length != ARCHIVE_SIZE) {
                    throw new IOException("wrong size: got " + length + " bytes, expected " + ARCHIVE_SIZE);
                }
                String actual = sha256(archive);
                if (!ARCHIVE_SHA256.equals(actual)) {
                    throw new IOException("checksum mismatch (archive is corrupt or was modified)");
                }
                NocodeLog.append("Ubuntu download verified (" + length + " bytes, sha256 ok)");
                return;
            } catch (Exception e) {
                String message = name + ": " + e.getMessage();
                NocodeLog.append("Download failed — " + message);
                errors.append(errors.length() == 0 ? "" : "; ").append(message);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        archive.delete();
        throw new IOException("Could not download Ubuntu. " + errors);
    }

    private static void download(String urlText, File target, Listener listener) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "NO-CODE-Android/1.0");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            long declared = connection.getContentLengthLong();
            if (declared > 0 && declared != ARCHIVE_SIZE) {
                throw new IOException("server reports " + declared + " bytes, expected " + ARCHIVE_SIZE);
            }

            long total = declared > 0 ? declared : ARCHIVE_SIZE;
            byte[] buffer = new byte[128 * 1024];
            long downloaded = 0;
            long lastReport = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 128 * 1024);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 128 * 1024)) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 400) {
                        lastReport = now;
                        int percent = 10 + (int) (downloaded * 33 / total);
                        listener.onProgress(Math.min(percent, 43),
                            "Downloading Ubuntu… " + (downloaded / (1024 * 1024)) + " MB / "
                                + (ARCHIVE_SIZE / (1024 * 1024)) + " MB");
                    }
                }
            }
            if (downloaded != ARCHIVE_SIZE) {
                throw new IOException("transfer stopped after " + downloaded + " of " + ARCHIVE_SIZE + " bytes");
            }
            listener.onProgress(44, "Ubuntu download complete");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void extractArchive(File archive, File rootfs, Listener listener) throws IOException {
        deleteRecursively(rootfs);
        if (!rootfs.mkdirs()) {
            throw new IOException("Could not create " + rootfs);
        }

        try (InputStream fileIn = new FileInputStream(archive);
             InputStream gzip = new GZIPInputStream(new BufferedInputStream(fileIn, 128 * 1024), 128 * 1024)) {
            final long lastReport[] = {0};
            NocodeTarExtractor.extract(gzip, rootfs, new AndroidFs(), compressed -> {
                long now = System.currentTimeMillis();
                if (now - lastReport[0] > 400) {
                    lastReport[0] = now;
                    int percent = 45 + (int) (compressed * 33 / ARCHIVE_SIZE);
                    listener.onProgress(Math.min(percent, 78),
                        "Extracting Ubuntu… " + (compressed / (1024 * 1024)) + " MB / "
                            + (ARCHIVE_SIZE / (1024 * 1024)) + " MB");
                }
            });
        } catch (IOException e) {
            throw new IOException("Extracting Ubuntu failed: " + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null) return;
        if (isDirectory(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static boolean isDirectory(File file) {
        try {
            if (Os.lstat(file.getAbsolutePath()) == null) return false;
            boolean isLink = (Os.lstat(file.getAbsolutePath()).st_mode & OsConstants.S_IFMT) == OsConstants.S_IFLNK;
            return !isLink && file.isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 is not available", e);
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 128 * 1024)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        StringBuilder builder = new StringBuilder(64);
        for (byte b : digest.digest()) builder.append(String.format(Locale.US, "%02x", b));
        return builder.toString();
    }

    /** Filesystem operations for Android: symlinks, hard links and permissions. */
    private static final class AndroidFs implements NocodeTarExtractor.Fs {
        @Override
        public void symlink(File linkPath, String target) throws IOException {
            try {
                Os.symlink(target, linkPath.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public void hardlink(File linkPath, File existingPath) throws IOException {
            try {
                Os.link(existingPath.getAbsolutePath(), linkPath.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public void chmod(File path, int mode) {
            try {
                // Do not try to restore setuid/setgid bits: Android apps cannot set them.
                Os.chmod(path.getAbsolutePath(), mode & 0777);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void setLastModified(File path, long epochSeconds) {
            if (epochSeconds > 0) {
                //noinspection ResultOfMethodCallIgnored
                path.setLastModified(epochSeconds * 1000L);
            }
        }
    }
}

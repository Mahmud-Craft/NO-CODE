package com.termux.app.nocode;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal tar extractor for the Ubuntu base rootfs.
 *
 * <p>The app cannot rely on the shell {@code tar} binary on every device, so the
 * Ubuntu rootfs is unpacked in Java instead. This parser supports the formats the
 * Ubuntu base tarballs actually use: ustar, PAX extended headers ({@code x}/{@code g}),
 * GNU long names ({@code L}/{@code K}) and the usual entry types (file, directory,
 * symlink, hard link). Sparse files and device nodes are not needed and are skipped.</p>
 *
 * <p>All filesystem operations that plain Java cannot do (symlinks, hard links,
 * chmod) are delegated to {@link Fs} so the extractor itself stays pure Java and
 * can be unit tested on a normal JVM.</p>
 */
final class NocodeTarExtractor {

    private static final int BLOCK_SIZE = 512;

    interface Fs {
        void symlink(File linkPath, String target) throws IOException;

        void hardlink(File linkPath, File existingPath) throws IOException;

        void chmod(File path, int mode);

        void setLastModified(File path, long epochSeconds);
    }

    interface Progress {
        /** @param compressedBytesRead bytes read from the (compressed) archive so far */
        void onProgress(long compressedBytesRead);
    }

    private NocodeTarExtractor() {
    }

    static void extract(InputStream archive, File destination, Fs fs, Progress progress)
        throws IOException {
        CountingInputStream counting = new CountingInputStream(archive);
        InputStream in = new BufferedInputStream(counting, 128 * 1024);

        byte[] header = new byte[BLOCK_SIZE];
        List<String> paxPath = new ArrayList<>();
        List<String> gnuLongName = new ArrayList<>();
        String paxLinkPath = null;
        String gnuLongLink = null;
        long paxSize = -1;
        long paxMtime = Long.MIN_VALUE;

        while (true) {
            int read = readBlock(in, header);
            if (read == -1) break; // end of archive
            if (read != BLOCK_SIZE) throw new IOException("Truncated tar header");

            if (isZeroBlock(header)) break;

            verifyChecksum(header);

            String name = readString(header, 0, 100);
            int mode = (int) readNumber(header, 100, 8);
            long size = readNumber(header, 124, 12);
            long mtime = readNumber(header, 136, 12);
            char type = (char) (header[156] == 0 ? '0' : (header[156] & 0xff));
            String linkName = readString(header, 157, 100);
            String magic = readString(header, 257, 8);
            String prefix = readString(header, 345, 155);

            if (prefix != null && !prefix.isEmpty()) {
                // ustar/old GNU tar store long paths as prefix + "/" + name
                name = prefix + "/" + name;
            }

            if (type == 'x' || type == 'g') {
                byte[] data = readData(in, size);
                String pathOverride = null;
                for (String record : parsePax(data)) {
                    int eq = record.indexOf('=');
                    if (eq <= 0) continue;
                    String key = record.substring(0, eq);
                    String value = record.substring(eq + 1);
                    if ("path".equals(key)) {
                        pathOverride = value;
                    } else if ("linkpath".equals(key)) {
                        paxLinkPath = value;
                    } else if ("size".equals(key)) {
                        try {
                            paxSize = Long.parseLong(value.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    } else if ("mtime".equals(key)) {
                        try {
                            paxMtime = (long) Double.parseDouble(value.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (type == 'x') {
                    if (pathOverride != null) paxPath.add(pathOverride);
                }
                skipPadding(in, size);
                if (progress != null) progress.onProgress(counting.count);
                continue;
            }

            if (type == 'L' || type == 'K') {
                byte[] data = readData(in, size);
                String value = new String(data, StandardCharsets.UTF_8);
                int nul = value.indexOf('\0');
                if (nul >= 0) value = value.substring(0, nul);
                if (type == 'L') {
                    gnuLongName.add(value);
                } else {
                    gnuLongLink = value;
                }
                skipPadding(in, size);
                if (progress != null) progress.onProgress(counting.count);
                continue;
            }

            if (paxSize >= 0) {
                size = paxSize;
                paxSize = -1;
            }
            if (!paxPath.isEmpty()) {
                name = paxPath.remove(0);
            }
            if (!gnuLongName.isEmpty()) {
                name = gnuLongName.remove(0);
            }
            if (paxLinkPath != null) {
                linkName = paxLinkPath;
                paxLinkPath = null;
            }
            if (gnuLongLink != null) {
                linkName = gnuLongLink;
                gnuLongLink = null;
            }

            String normalized = normalize(name);
            if (normalized == null) {
                skipData(in, size);
                if (progress != null) progress.onProgress(counting.count);
                continue;
            }

            if (paxMtime != Long.MIN_VALUE) {
                mtime = paxMtime;
                paxMtime = Long.MIN_VALUE;
            }

            File target = new File(destination, normalized);
            if (!isInside(destination, target)) {
                throw new IOException("Archive entry escapes the target directory: " + name);
            }

            switch (type) {
                case '0':
                case '\0':
                case '7': {
                    ensureParent(target);
                    writeFile(in, target, size);
                    fs.chmod(target, mode);
                    fs.setLastModified(target, mtime);
                    break;
                }
                case '5': {
                    if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                        throw new IOException("Could not create directory: " + normalized);
                    }
                    fs.chmod(target, mode);
                    fs.setLastModified(target, mtime);
                    break;
                }
                case '2': {
                    ensureParent(target);
                    deleteQuietly(target);
                    try {
                        fs.symlink(target, linkName);
                    } catch (IOException e) {
                        throw new IOException("Could not create symlink " + normalized
                            + " -> " + linkName + ": " + e.getMessage(), e);
                    }
                    break;
                }
                case '1': {
                    ensureParent(target);
                    deleteQuietly(target);
                    File existing = new File(destination, normalize(linkName));
                    if (!isInside(destination, existing)) {
                        throw new IOException("Hard link target escapes the target directory: " + linkName);
                    }
                    try {
                        fs.hardlink(target, existing);
                    } catch (IOException e) {
                        // Some filesystems do not allow hard links: fall back to a copy.
                        copyFile(existing, target);
                    }
                    fs.chmod(target, mode);
                    fs.setLastModified(target, mtime);
                    break;
                }
                default:
                    // Device nodes, fifos and sockets are not present in the rootfs
                    // and cannot be created by a normal Android app anyway.
                    skipData(in, size);
                    break;
            }

            skipPadding(in, size);
            if (progress != null) progress.onProgress(counting.count);
        }
    }

    private static void writeFile(InputStream in, File target, long size) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = size;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, toRead);
                if (read < 0) throw new IOException("Unexpected end of archive while writing " + target.getName());
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > 4L * 1024 * 1024) throw new IOException("Unexpectedly large tar metadata entry: " + size);
        byte[] data = new byte[(int) size];
        int offset = 0;
        while (offset < data.length) {
            int read = in.read(data, offset, data.length - offset);
            if (read < 0) throw new IOException("Unexpected end of archive");
            offset += read;
        }
        return data;
    }

    private static void skipData(InputStream in, long size) throws IOException {
        long remaining = size;
        byte[] buffer = new byte[16 * 1024];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Unexpected end of archive");
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE;
        skipData(in, padding);
    }

    private static int readBlock(InputStream in, byte[] block) throws IOException {
        int offset = 0;
        while (offset < block.length) {
            int read = in.read(block, offset, block.length - offset);
            if (read < 0) return offset == 0 ? -1 : offset;
            offset += read;
        }
        return offset;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static void verifyChecksum(byte[] header) throws IOException {
        long stored = readNumber(header, 148, 8);
        long unsignedSum = 0;
        long signedSum = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            int value = i >= 148 && i < 156 ? ' ' : (header[i] & 0xff);
            unsignedSum += value;
            signedSum += (i >= 148 && i < 156) ? ' ' : header[i];
        }
        if (stored != unsignedSum && stored != signedSum) {
            throw new IOException("Corrupt tar archive (bad header checksum)");
        }
    }

    private static String readString(byte[] buffer, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && buffer[end] != 0) end++;
        return new String(buffer, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long readNumber(byte[] buffer, int offset, int length) {
        // Base-256 encoding (used for very large values)
        if ((buffer[offset] & 0x80) != 0) {
            long value = buffer[offset] & 0x3f;
            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (buffer[i] & 0xff);
            }
            return value;
        }
        long value = 0;
        boolean seen = false;
        for (int i = offset; i < offset + length; i++) {
            int c = buffer[i] & 0xff;
            if (c == ' ' || c == 0) {
                if (seen) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            value = (value << 3) + (c - '0');
            seen = true;
        }
        return value;
    }

    /** Parses PAX extended header records: "%d %s=%s\n". */
    private static List<String> parsePax(byte[] data) {
        List<String> records = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            int space = offset;
            while (space < data.length && data[space] != ' ') space++;
            if (space >= data.length) break;
            int length;
            try {
                length = Integer.parseInt(new String(data, offset, space - offset, StandardCharsets.US_ASCII).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || offset + length > data.length) break;
            int recordStart = space + 1;
            int recordEnd = offset + length;
            while (recordEnd > recordStart && (data[recordEnd - 1] == '\n' || data[recordEnd - 1] == 0)) recordEnd--;
            if (recordEnd > recordStart) {
                records.add(new String(data, recordStart, recordEnd - recordStart, StandardCharsets.UTF_8));
            }
            offset += length;
        }
        return records;
    }

    private static String normalize(String name) {
        if (name == null) return null;
        String value = name.replace('\\', '/');
        while (value.startsWith("./")) value = value.substring(2);
        while (value.startsWith("/")) value = value.substring(1);
        if (value.isEmpty() || ".".equals(value)) return null;
        String[] parts = value.split("/");
        for (String part : parts) {
            if ("..".equals(part)) return null;
        }
        return value;
    }

    private static boolean isInside(File root, File target) throws IOException {
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
    }

    private static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create directory: " + parent.getAbsolutePath());
        }
    }

    private static void deleteQuietly(File file) {
        // File.delete() also removes dangling symlinks, which exists() reports as absent.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream in = new java.io.FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
    }

    static String describe(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) count++;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) count += read;
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) count += skipped;
            return skipped;
        }
    }
}

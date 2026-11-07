package dev.servers.app.runtime;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Installs a SHA-256-verified Debian ARM64 rootfs tar.gz into app-private home.
 * The archive is treated as untrusted: traversal, symlink escapes, device nodes
 * and writes through symlinked parents are rejected. File modes and links are
 * retained so the Linux guest works under PRoot.
 */
public final class RootfsInstaller {
    private static final int TAR_BLOCK = 512;
    private static final long MAX_METADATA_BYTES = 1024L * 1024L;
    private static final long MAX_ARCHIVE_BYTES = 3L * 1024L * 1024L * 1024L;
    private static final long MAX_SINGLE_FILE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAX_TOTAL_TAR_PAYLOAD_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final long MAX_TAR_ENTRIES = 750_000L;

    private RootfsInstaller() {}

    public static InstallResult install(Context context, InputStream archive,
                                        String expectedSha256) throws IOException {
        if (context == null || archive == null) throw new IllegalArgumentException("Context/archive required");
        String expected = normalizeSha256(expectedSha256);
        AppPrivateHome.Layout home = AppPrivateHome.ensure(context);

        File archiveFile = new File(home.cache, "rootfs-download.tar.gz.part");
        File staging = new File(home.cache, "rootfs-staging");
        File previous = new File(home.home, "rootfs.previous");
        File liveRootfs = new File(home.home, "rootfs");
        File lockFile = new File(home.runtime, "rootfs-install.lock");

        // One lock serializes installers; a second, exclusive runtime lock
        // prevents replacing the guest while the foreground service owns it.
        try (java.io.RandomAccessFile lockRaf = new java.io.RandomAccessFile(lockFile, "rw");
             FileChannel channel = lockRaf.getChannel();
             FileLock ignored = channel.lock()) {
            try (RootfsRuntimeLock.Lease runtimeLock = RootfsRuntimeLock.acquireExclusive(home.runtime)) {
                // Recover a previously interrupted swap before touching staging.
                // If the live tree is absent, the backup may be the only valid OS.
                if (!liveRootfs.exists() && previous.exists()) {
                    if (!previous.renameTo(liveRootfs)) {
                        throw new IOException("Could not restore rootfs backup after an interrupted install");
                    }
                } else if (liveRootfs.exists() && previous.exists()) {
                    deleteTree(previous.toPath());
                }

                deleteTree(staging.toPath());
                copyAndVerify(archive, archiveFile, expected);
                Files.createDirectory(staging.toPath());
                List<DirectoryMode> directoryModes = extractTarGz(archiveFile, staging.toPath());
                applyDirectoryModes(directoryModes);
                validateRootfs(staging.toPath());

                // Both paths are inside app-private home, so rename is same-filesystem.
                if (liveRootfs.exists() && !liveRootfs.renameTo(previous)) {
                    throw new IOException("Could not move the previous rootfs aside");
                }
                if (!staging.renameTo(liveRootfs)) {
                    if (previous.exists() && !previous.renameTo(liveRootfs)) {
                        throw new IOException("New rootfs activation failed and backup rollback also failed");
                    }
                    throw new IOException("Could not activate the new rootfs; previous rootfs was restored");
                }
                // Failure to delete the backup must not report an unsuccessful
                // activation; it can be cleaned during the next installation.
                try { deleteTree(previous.toPath()); } catch (IOException ignoredBackupCleanup) {}
                Files.deleteIfExists(archiveFile.toPath());
                return new InstallResult(expected, liveRootfs.getAbsolutePath());
            }
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("Another rootfs installer is already active", busy);
        } catch (ErrnoException error) {
            throw new IOException("Could not apply Linux file permissions: " + error.getMessage(), error);
        } finally {
            // On failure keep the old live rootfs intact; staging/download can be
            // discarded because they are not used by running server processes.
            try { Files.deleteIfExists(archiveFile.toPath()); } catch (IOException ignored) {}
            try { deleteTree(staging.toPath()); } catch (IOException ignored) {}
        }
    }

    private static String normalizeSha256(String value) throws IOException {
        if (value == null) throw new IOException("Expected SHA-256 is required");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256 value");
        return normalized;
    }

    private static void copyAndVerify(InputStream source, File destination,
                                      String expectedSha256) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable", impossible); }
        long copied = 0;
        try (InputStream input = new BufferedInputStream(source);
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                copied += count;
                if (copied > MAX_ARCHIVE_BYTES) {
                    throw new IOException("Rootfs archive exceeds the 3 GiB safety limit");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        } catch (IOException failure) {
            Files.deleteIfExists(destination.toPath());
            throw failure;
        }
        String actual = toHex(digest.digest());
        if (!actual.equals(expectedSha256)) {
            Files.deleteIfExists(destination.toPath());
            throw new IOException("Rootfs checksum mismatch; download rejected");
        }
    }

    private static List<DirectoryMode> extractTarGz(File archive, Path root) throws IOException, ErrnoException {
        List<DirectoryMode> modes = new ArrayList<>();
        List<HardLink> hardLinks = new ArrayList<>();
        Map<String, String> globalPax = new HashMap<>();
        Map<String, String> localPax = new HashMap<>();
        String longName = null;
        String longLink = null;
        long entryCount = 0;
        long totalTarPayloadBytes = 0;

        try (InputStream input = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            byte[] header = new byte[TAR_BLOCK];
            while (readBlock(input, header)) {
                if (allZero(header)) {
                    // Drain the decompressor to EOF so GZIPInputStream verifies
                    // the gzip CRC/ISIZE trailer. Only tar padding (zero bytes)
                    // is accepted after the end marker.
                    byte[] tail = new byte[8192];
                    int count;
                    while ((count = input.read(tail)) != -1) {
                        for (int i = 0; i < count; i++) {
                            if (tail[i] != 0) throw new IOException("Non-zero data found after tar end marker");
                        }
                    }
                    break;
                }

                entryCount++;
                if (entryCount > MAX_TAR_ENTRIES) throw new IOException("Rootfs tar has too many entries");
                verifyTarHeaderChecksum(header);
                String name = readString(header, 0, 100);
                String prefix = readString(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                String linkName = readString(header, 157, 100);
                long size = parseOctal(header, 124, 12);
                int mode = (int) parseOctal(header, 100, 8);
                int type = header[156] & 0xff;
                if (size < 0 || size > MAX_SINGLE_FILE_BYTES) {
                    throw new IOException("Tar entry exceeds the 2 GiB per-file safety limit");
                }

                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    if (totalTarPayloadBytes > MAX_TOTAL_TAR_PAYLOAD_BYTES - size) {
                        throw new IOException("Rootfs exceeds the 8 GiB total tar-payload safety limit");
                    }
                    totalTarPayloadBytes += size;
                    byte[] metadata = readPayload(input, size, MAX_METADATA_BYTES);
                    skipPadding(input, size);
                    if (type == 'L') longName = trimTarText(metadata);
                    else if (type == 'K') longLink = trimTarText(metadata);
                    else if (type == 'g') globalPax.putAll(parsePax(metadata));
                    else localPax.putAll(parsePax(metadata));
                    continue;
                }

                Map<String, String> pax = new HashMap<>(globalPax);
                pax.putAll(localPax);
                if (longName != null) name = longName;
                if (longLink != null) linkName = longLink;
                if (pax.containsKey("path")) name = pax.get("path");
                if (pax.containsKey("linkpath")) linkName = pax.get("linkpath");
                if (pax.containsKey("size")) size = parseDecimal(pax.get("size"));
                localPax.clear();
                longName = null;
                longLink = null;

                // PAX can override the size from the fixed tar header, so
                // re-validate it after applying all extended-header values.
                if (size < 0 || size > MAX_SINGLE_FILE_BYTES) {
                    throw new IOException("Tar entry exceeds the 2 GiB per-file safety limit");
                }
                if (totalTarPayloadBytes > MAX_TOTAL_TAR_PAYLOAD_BYTES - size) {
                    throw new IOException("Rootfs exceeds the 8 GiB total tar-payload safety limit");
                }
                totalTarPayloadBytes += size;

                Path relative = safeArchivePath(name);
                if (relative == null) {
                    skipFully(input, size);
                    skipPadding(input, size);
                    continue;
                }
                Path destination = root.resolve(relative).normalize();
                if (!destination.startsWith(root) || destination.equals(root)) {
                    throw new IOException("Archive entry escapes rootfs: " + name);
                }

                if (type == 0 || type == '0' || type == '7') {
                    ensureSafeParents(root, destination.getParent());
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Duplicate archive file entry: " + name);
                    }
                    try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                            destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                        copyExactly(input, output, size);
                    }
                    Os.chmod(destination.toString(), mode & 07777);
                } else if (type == '5') {
                    ensureSafeDirectory(root, destination);
                    modes.add(new DirectoryMode(destination, mode & 07777));
                    skipFully(input, size);
                } else if (type == '2') {
                    ensureSafeParents(root, destination.getParent());
                    validateSymlinkTarget(root, destination, linkName);
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Duplicate archive symlink entry: " + name);
                    }
                    Files.createSymbolicLink(destination, Paths.get(linkName));
                    skipFully(input, size);
                } else if (type == '1') {
                    Path targetRelative = safeArchivePath(linkName);
                    if (targetRelative == null) throw new IOException("Invalid hardlink target: " + linkName);
                    Path target = root.resolve(targetRelative).normalize();
                    if (!target.startsWith(root) || target.equals(root)) {
                        throw new IOException("Hardlink target escapes rootfs: " + linkName);
                    }
                    ensureSafeParents(root, destination.getParent());
                    hardLinks.add(new HardLink(destination, target, name));
                    skipFully(input, size);
                } else {
                    // Device nodes, FIFOs and sockets are intentionally not
                    // created in Android app storage; /dev is bound from Android.
                    skipFully(input, size);
                }
                skipPadding(input, size);
            }
        }

        for (HardLink link : hardLinks) {
            if (!Files.isRegularFile(link.target, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(link.target)) {
                throw new IOException("Hardlink target is missing or not a regular file: " + link.sourceName);
            }
            if (Files.exists(link.destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Duplicate hardlink destination: " + link.destination);
            }
            try {
                Files.createLink(link.destination, link.target);
            } catch (UnsupportedOperationException | IOException linkFailure) {
                // Some Android filesystems disallow hardlinks. Copying the file
                // preserves executable behavior, though not inode identity.
                Files.copy(link.target, link.destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        return modes;
    }

    private static void validateRootfs(Path root) throws IOException {
        Path shell = root.resolve("bin/sh");
        Path box64 = root.resolve("usr/bin/box64");
        Path supervisor = root.resolve("usr/local/sbin/serverbox64-supervisor");
        Path box64Libs = root.resolve("usr/lib/box64-x86_64-linux-gnu");
        if (!Files.exists(shell)) throw new IOException("Rootfs has no /bin/sh");
        if (!Files.isRegularFile(box64)) throw new IOException("Rootfs has no /usr/bin/box64");
        if (ServerCommandResolver.readElfMachine(box64.toFile()) != 183) {
            throw new IOException("Box64 runtime is not a Linux ARM64 ELF executable");
        }
        if (!Files.isExecutable(supervisor)) throw new IOException("Rootfs supervisor is missing or not executable");
        if (!Files.isDirectory(box64Libs)) throw new IOException("Box64 x86-64 compatibility libraries are missing");
    }

    private static void applyDirectoryModes(List<DirectoryMode> modes) throws ErrnoException {
        Collections.sort(modes, (a, b) -> Integer.compare(b.path.getNameCount(), a.path.getNameCount()));
        for (DirectoryMode mode : modes) Os.chmod(mode.path.toString(), mode.mode);
    }

    private static void validateSymlinkTarget(Path root, Path link, String targetText) throws IOException {
        if (targetText == null || targetText.isEmpty() || targetText.indexOf('\0') >= 0) {
            throw new IOException("Invalid symlink target");
        }
        Path target = Paths.get(targetText);
        Path resolved = target.isAbsolute()
                ? root.resolve(target.toString().replaceFirst("^/+", "")).normalize()
                : link.getParent().resolve(target).normalize();
        if (!resolved.startsWith(root)) throw new IOException("Symlink target escapes rootfs: " + targetText);
    }

    private static void ensureSafeParents(Path root, Path parent) throws IOException {
        Path relative = root.relativize(parent.normalize());
        Path cursor = root;
        for (Path segment : relative) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor) || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Archive attempts to write through a non-directory/symlink: " + cursor);
                }
            } else {
                Files.createDirectory(cursor);
            }
        }
    }

    private static void ensureSafeDirectory(Path root, Path directory) throws IOException {
        if (directory.equals(root)) return;
        ensureSafeParents(root, directory.getParent());
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Directory entry conflicts with an existing non-directory: " + directory);
            }
        } else {
            Files.createDirectory(directory);
        }
    }

    private static Path safeArchivePath(String raw) throws IOException {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0) {
            throw new IOException("Empty/invalid tar path");
        }
        String value = raw.replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IOException("Absolute archive path rejected: " + raw);
        }

        // Inspect raw components BEFORE any normalization. Path.normalize()
        // would erase "a/../b" and conceal an archive traversal attempt.
        List<String> safeParts = new ArrayList<>();
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IOException("Path traversal rejected: " + raw);
            safeParts.add(part);
        }
        if (safeParts.isEmpty()) return null;
        Path relative = Paths.get(safeParts.get(0), safeParts.subList(1, safeParts.size()).toArray(new String[0]));
        if (relative.isAbsolute()) throw new IOException("Absolute archive path rejected: " + raw);
        return relative;
    }

    private static void verifyTarHeaderChecksum(byte[] header) throws IOException {
        long stored = parseOctal(header, 148, 8);
        long unsignedSum = 0;
        for (int i = 0; i < header.length; i++) {
            unsignedSum += (i >= 148 && i < 156) ? 32 : (header[i] & 0xff);
        }
        // POSIX ustar uses the unsigned-byte checksum. Reject bad headers
        // instead of extracting data from a corrupted or tampered archive.
        if (stored != unsignedSum) throw new IOException("Tar header checksum mismatch");
    }

    private static boolean readBlock(InputStream input, byte[] block) throws IOException {
        int offset = 0;
        while (offset < block.length) {
            int count = input.read(block, offset, block.length - offset);
            if (count == -1) {
                if (offset == 0) return false;
                throw new IOException("Truncated tar header");
            }
            offset += count;
        }
        return true;
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static String readString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] data, int offset, int length) throws IOException {
        int start = offset;
        int end = offset + length;
        while (start < end && (data[start] == 0 || data[start] == ' ')) start++;
        long value = 0;
        boolean found = false;
        for (int i = start; i < end; i++) {
            int c = data[i] & 0xff;
            if (c == 0 || c == ' ') break;
            if (c < '0' || c > '7') throw new IOException("Invalid tar octal field");
            int digit = c - '0';
            if (value > (Long.MAX_VALUE - digit) / 8L) throw new IOException("Tar octal field overflows 64-bit size");
            value = (value << 3) + digit;
            found = true;
        }
        return found ? value : 0;
    }

    private static long parseDecimal(String text) throws IOException {
        try { long n = Long.parseLong(text); if (n < 0) throw new NumberFormatException(); return n; }
        catch (NumberFormatException e) { throw new IOException("Invalid PAX size", e); }
    }

    private static byte[] readPayload(InputStream input, long size, long maximum) throws IOException {
        if (size < 0 || size > maximum) throw new IOException("Tar metadata block is too large");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
        copyExactly(input, output, size);
        return output.toByteArray();
    }

    private static String trimTarText(byte[] value) {
        int end = value.length;
        while (end > 0 && (value[end - 1] == 0 || value[end - 1] == '\n')) end--;
        return new String(value, 0, end, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parsePax(byte[] data) throws IOException {
        Map<String, String> values = new HashMap<>();
        int offset = 0;
        while (offset < data.length) {
            int space = offset;
            while (space < data.length && data[space] != ' ') space++;
            if (space == data.length) throw new IOException("Malformed PAX record length");
            int length;
            try { length = Integer.parseInt(new String(data, offset, space - offset, StandardCharsets.US_ASCII)); }
            catch (NumberFormatException e) { throw new IOException("Malformed PAX record", e); }
            if (length <= 0 || offset + length > data.length) throw new IOException("PAX record exceeds metadata block");
            int valueStart = space + 1;
            int valueEnd = offset + length - 1;
            int equals = -1;
            for (int i = valueStart; i < valueEnd; i++) if (data[i] == '=') { equals = i; break; }
            if (equals > valueStart) {
                String key = new String(data, valueStart, equals - valueStart, StandardCharsets.UTF_8);
                String value = new String(data, equals + 1, valueEnd - equals - 1, StandardCharsets.UTF_8);
                values.put(key, value);
            }
            offset += length;
        }
        return values;
    }

    private static void copyExactly(InputStream input, OutputStream output, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int request = (int) Math.min(buffer.length, remaining);
            int count = input.read(buffer, 0, request);
            if (count < 0) throw new IOException("Truncated tar entry data");
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static void skipFully(InputStream input, long size) throws IOException {
        if (size < 0) throw new IOException("Negative tar entry size");
        long remaining = size;
        byte[] buffer = new byte[16 * 1024];
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException("Truncated tar entry");
                skipped = count;
            }
            remaining -= skipped;
        }
    }

    private static void skipPadding(InputStream input, long size) throws IOException {
        long padding = (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK;
        skipFully(input, padding);
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        File[] children = path.toFile().listFiles();
        if (children != null) for (File child : children) deleteTree(child.toPath());
        Files.deleteIfExists(path);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static final class DirectoryMode {
        final Path path; final int mode;
        DirectoryMode(Path path, int mode) { this.path = path; this.mode = mode; }
    }

    private static final class HardLink {
        final Path destination; final Path target; final String sourceName;
        HardLink(Path destination, Path target, String sourceName) {
            this.destination = destination; this.target = target; this.sourceName = sourceName;
        }
    }

    public static final class InstallResult {
        public final String sha256;
        public final String rootfsPath;
        InstallResult(String sha256, String rootfsPath) {
            this.sha256 = sha256; this.rootfsPath = rootfsPath;
        }
    }
}

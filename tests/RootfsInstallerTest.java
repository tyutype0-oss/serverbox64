package dev.servers.app.runtime;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.GZIPOutputStream;

/** Host-JDK regression tests for the Android rootfs installer (Android APIs are stubbed). */
public final class RootfsInstallerTest {
    private static final class TestContext extends Context {
        private final File data;
        TestContext(File data) { this.data = data; }
        @Override public File getDataDir() { return data; }
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("serverbox-rootfs-test");
        try {
            testHomeAndInterruptedSwapRecovery(temp);
            testGoodArchiveAndModes(temp);
            testChecksumAndRollback(temp);
            testTraversalAndTarChecksum(temp);
            testSymlinkParentTraversal(temp);
            testGzipTrailerValidation(temp);
            testRuntimeLeaseBlocksInstall(temp);
            System.out.println("PASS: interrupted rootfs activation recovers previous Debian tree");
            System.out.println("PASS: rootfs install, symlink, executable modes and ELF validation");
            System.out.println("PASS: checksum failure preserves the previous rootfs");
            System.out.println("PASS: tar traversal and bad header checksums are rejected");
            System.out.println("PASS: writes through archive-created symlink parents are rejected");
            System.out.println("PASS: gzip trailer CRC is verified through end-of-stream");
            System.out.println("PASS: active runtime lease prevents rootfs replacement");
        } finally {
            deleteTree(temp.toFile());
        }
    }

    private static void testHomeAndInterruptedSwapRecovery(Path temp) throws Exception {
        File appData = temp.resolve("app-data-recovery").toFile();
        check(appData.mkdir(), "cannot create recovery app data");
        AppPrivateHome.Layout initial = AppPrivateHome.ensure(new TestContext(appData));
        check(!initial.rootfs.exists(), "AppPrivateHome must not create an empty rootfs");

        File backup = new File(initial.home, "rootfs.previous");
        check(backup.mkdir(), "cannot create previous rootfs fixture");
        File marker = new File(backup, "recovery-marker");
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write("last-good-rootfs".getBytes(StandardCharsets.UTF_8));
        }

        AppPrivateHome.Layout stillPending = AppPrivateHome.ensure(new TestContext(appData));
        check(!stillPending.rootfs.exists(), "ensure() must not recover while called by an active service");
        AppPrivateHome.recoverInterruptedRootfs(stillPending);
        AppPrivateHome.Layout recovered = AppPrivateHome.ensure(new TestContext(appData));
        check(recovered.rootfs.isDirectory(), "previous rootfs was not restored");
        check(new File(recovered.rootfs, "recovery-marker").isFile(), "restored rootfs contents are missing");
        check(!backup.exists(), "rootfs backup should be moved, not duplicated");

        File staleBackup = new File(recovered.home, "rootfs.previous");
        check(staleBackup.mkdir(), "cannot create stale backup fixture");
        AppPrivateHome.Layout withStaleBackup = AppPrivateHome.ensure(new TestContext(appData));
        AppPrivateHome.recoverInterruptedRootfs(withStaleBackup);
        check(staleBackup.isDirectory(), "recovery must not delete a backup while live rootfs exists");
    }

    private static void testGoodArchiveAndModes(Path temp) throws Exception {
        File appData = temp.resolve("app-data").toFile();
        check(appData.mkdir(), "cannot create app data");
        AppPrivateHome.Layout layout = AppPrivateHome.ensure(new TestContext(appData));
        File root = createRootfsFixture(temp.resolve("fixture-good"));
        File archive = makeTarGz(root.toPath(), temp.resolve("good.tar.gz"));
        byte[] bytes = Files.readAllBytes(archive.toPath());
        RootfsInstaller.InstallResult result = RootfsInstaller.install(
                new TestContext(appData), new ByteArrayInputStream(bytes), sha256(bytes));
        check(result.rootfsPath.equals(new File(layout.home, "rootfs").getAbsolutePath()), "wrong rootfs path");
        Path installed = new File(layout.home, "rootfs").toPath();
        check(Files.isSymbolicLink(installed.resolve("bin")), "rootfs /bin symlink was not preserved");
        check(Files.exists(installed.resolve("bin/sh")), "guest /bin/sh is missing");
        check(Files.isExecutable(installed.resolve("usr/local/sbin/serverbox64-supervisor")),
                "supervisor executable mode was not preserved");
        check(ServerCommandResolver.readElfMachine(installed.resolve("usr/bin/box64").toFile()) == 183,
                "Box64 ELF architecture validation failed");
        check(Files.isSameFile(installed.resolve("usr/bin/box64"), installed.resolve("usr/bin/box64-hardlink")),
                "rootfs hardlink was not retained");
    }

    private static void testChecksumAndRollback(Path temp) throws Exception {
        File appData = temp.resolve("app-data").toFile();
        AppPrivateHome.Layout layout = AppPrivateHome.ensure(new TestContext(appData));
        Path liveRootfs = new File(layout.home, "rootfs").toPath();
        byte[] before = Files.readAllBytes(liveRootfs.resolve("usr/local/sbin/serverbox64-supervisor"));
        byte[] valid = Files.readAllBytes(temp.resolve("good.tar.gz"));
        boolean rejected = false;
        try {
            RootfsInstaller.install(new TestContext(appData), new ByteArrayInputStream(valid), repeat('0', 64));
        } catch (java.io.IOException expected) { rejected = expected.getMessage().contains("checksum mismatch"); }
        check(rejected, "bad archive checksum was not rejected");
        check(java.util.Arrays.equals(before,
                Files.readAllBytes(liveRootfs.resolve("usr/local/sbin/serverbox64-supervisor"))),
                "checksum failure damaged the active rootfs");
    }

    private static void testTraversalAndTarChecksum(Path temp) throws Exception {
        File appData = temp.resolve("app-data").toFile();
        byte[] traversal = minimalTarGz("safe/../escaped.txt", "nope", false);
        boolean traversalRejected = false;
        try {
            RootfsInstaller.install(new TestContext(appData), new ByteArrayInputStream(traversal), sha256(traversal));
        } catch (java.io.IOException expected) { traversalRejected = expected.getMessage().contains("Path traversal"); }
        check(traversalRejected, "tar path traversal was not rejected");
        check(!Files.exists(temp.resolve("escaped.txt")), "archive wrote outside staging root");

        byte[] corrupted = minimalTarGz("ordinary.txt", "nope", true);
        boolean checksumRejected = false;
        try {
            RootfsInstaller.install(new TestContext(appData), new ByteArrayInputStream(corrupted), sha256(corrupted));
        } catch (java.io.IOException expected) { checksumRejected = expected.getMessage().contains("header checksum"); }
        check(checksumRejected, "corrupt tar header checksum was not rejected");
    }

    private static void testSymlinkParentTraversal(Path temp) throws Exception {
        File appData = temp.resolve("app-data-symlink").toFile();
        check(appData.mkdir(), "cannot create symlink-test app data");
        byte[] archive = symlinkParentTarGz();
        boolean rejected = false;
        try {
            RootfsInstaller.install(new TestContext(appData), new ByteArrayInputStream(archive), sha256(archive));
        } catch (java.io.IOException expected) {
            rejected = expected.getMessage().contains("symlink");
        }
        check(rejected, "archive write through a symlink parent was not rejected");
        check(!new File(new File(appData, "home"), "rootfs").exists(),
                "unsafe symlink archive left a live rootfs");
    }

    private static byte[] symlinkParentTarGz() throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        writeTarEntry(tar, "real/", '5', "", new byte[0]);
        writeTarEntry(tar, "pivot", '2', "real", new byte[0]);
        writeTarEntry(tar, "pivot/escaped.txt", '0', "", "not-allowed".getBytes(StandardCharsets.UTF_8));
        tar.write(new byte[1024]);
        ByteArrayOutputStream gzipBytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBytes)) { gzip.write(tar.toByteArray()); }
        return gzipBytes.toByteArray();
    }

    private static void writeTarEntry(ByteArrayOutputStream tar, String name,
                                      char type, String linkName, byte[] payload) throws Exception {
        byte[] header = new byte[512];
        put(header, 0, 100, name);
        put(header, 100, 8, octal(type == '5' ? 0755 : 0644, 8));
        put(header, 108, 8, octal(0, 8));
        put(header, 116, 8, octal(0, 8));
        put(header, 124, 12, octal(payload.length, 12));
        put(header, 136, 12, octal(0, 12));
        for (int i = 148; i < 156; i++) header[i] = 32;
        header[156] = (byte) type;
        put(header, 157, 100, linkName);
        put(header, 257, 6, "ustar\0");
        put(header, 263, 2, "00");
        long checksum = 0;
        for (byte b : header) checksum += b & 0xff;
        put(header, 148, 8, String.format(java.util.Locale.ROOT, "%06o\0 ", checksum));
        tar.write(header);
        tar.write(payload);
        int padding = (512 - payload.length % 512) % 512;
        tar.write(new byte[padding]);
    }

    private static void testGzipTrailerValidation(Path temp) throws Exception {
        File appData = temp.resolve("app-data-gzip").toFile();
        check(appData.mkdir(), "cannot create gzip-test app data");
        byte[] valid = minimalTarGz("ordinary.txt", "valid", false);
        byte[] corrupt = valid.clone();
        // The final eight bytes of a gzip member are CRC32 and ISIZE.
        corrupt[corrupt.length - 8] ^= 0x20;
        boolean rejected = false;
        try {
            RootfsInstaller.install(new TestContext(appData), new ByteArrayInputStream(corrupt), sha256(corrupt));
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        check(rejected, "corrupt gzip CRC trailer was not rejected");
        check(!new File(new File(appData, "home"), "rootfs").exists(),
                "invalid gzip archive left an active rootfs behind");
    }

    private static void testRuntimeLeaseBlocksInstall(Path temp) throws Exception {
        File appData = temp.resolve("app-data").toFile();
        AppPrivateHome.Layout layout = AppPrivateHome.ensure(new TestContext(appData));
        byte[] valid = Files.readAllBytes(temp.resolve("good.tar.gz"));
        byte[] before = Files.readAllBytes(new File(layout.home, "rootfs/usr/local/sbin/serverbox64-supervisor").toPath());
        boolean blocked = false;
        try (RootfsRuntimeLock.Lease lease = RootfsRuntimeLock.acquireShared(layout.runtime)) {
            try {
                RootfsInstaller.install(new TestContext(appData), new ByteArrayInputStream(valid), sha256(valid));
            } catch (java.io.IOException expected) {
                blocked = expected.getMessage().toLowerCase().contains("active")
                        || expected.getMessage().toLowerCase().contains("installation");
            }
        }
        check(blocked, "rootfs replacement was allowed while a server lease was active");
        check(java.util.Arrays.equals(before,
                Files.readAllBytes(new File(layout.home, "rootfs/usr/local/sbin/serverbox64-supervisor").toPath())),
                "runtime lock changed active rootfs");
    }

    private static File createRootfsFixture(Path root) throws Exception {
        Files.createDirectories(root.resolve("usr/bin"));
        Files.createDirectories(root.resolve("usr/local/sbin"));
        Files.createDirectories(root.resolve("usr/lib/box64-x86_64-linux-gnu"));
        Files.write(root.resolve("usr/bin/sh"), "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        byte[] elf = new byte[64];
        elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F'; elf[4] = 2; elf[5] = 1;
        elf[18] = (byte) 183;
        Files.write(root.resolve("usr/bin/box64"), elf);
        Files.createLink(root.resolve("usr/bin/box64-hardlink"), root.resolve("usr/bin/box64"));
        Files.write(root.resolve("usr/local/sbin/serverbox64-supervisor"),
                "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("usr/lib/box64-x86_64-linux-gnu/lib-placeholder.so"), new byte[] {1, 2, 3});
        Files.setPosixFilePermissions(root.resolve("usr/bin/sh"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(root.resolve("usr/bin/box64"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(root.resolve("usr/local/sbin/serverbox64-supervisor"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createSymbolicLink(root.resolve("bin"), java.nio.file.Paths.get("usr/bin"));
        return root.toFile();
    }

    private static File makeTarGz(Path directory, Path archive) throws Exception {
        Process process = new ProcessBuilder("tar", "--format=ustar", "-czf", archive.toString(), "-C", directory.toString(), ".")
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int code = process.waitFor();
        if (code != 0) throw new AssertionError("tar fixture failed: " + new String(output, StandardCharsets.UTF_8));
        return archive.toFile();
    }

    private static byte[] minimalTarGz(String path, String content, boolean corruptChecksum) throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] header = new byte[512];
        put(header, 0, 100, path);
        put(header, 100, 8, octal(0755, 8));
        put(header, 108, 8, octal(0, 8));
        put(header, 116, 8, octal(0, 8));
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        put(header, 124, 12, octal(payload.length, 12));
        put(header, 136, 12, octal(0, 12));
        for (int i = 148; i < 156; i++) header[i] = 32;
        header[156] = '0';
        put(header, 257, 6, "ustar\0");
        put(header, 263, 2, "00");
        long checksum = 0;
        for (byte b : header) checksum += b & 0xff;
        put(header, 148, 8, String.format(java.util.Locale.ROOT, "%06o\0 ", checksum));
        if (corruptChecksum) header[10] ^= 1;
        tar.write(header);
        tar.write(payload);
        int padding = (512 - payload.length % 512) % 512;
        tar.write(new byte[padding]);
        tar.write(new byte[1024]);
        ByteArrayOutputStream gzipBytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBytes)) { gzip.write(tar.toByteArray()); }
        return gzipBytes.toByteArray();
    }

    private static void put(byte[] target, int offset, int width, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, Math.min(width, bytes.length));
    }

    private static String octal(long value, int width) {
        return String.format(java.util.Locale.ROOT, "%0" + (width - 1) + "o", value) + "\0";
    }

    private static String repeat(char c, int length) {
        char[] chars = new char[length]; java.util.Arrays.fill(chars, c); return new String(chars);
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(File file) throws Exception {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}

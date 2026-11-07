package dev.servers.app.runtime;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/**
 * Owns the app-private runtime layout.
 * With applicationId "dev.servers.app", home resolves to the same underlying
 * directory exposed as /data/data/dev.servers.app/home (Android may canonicalize
 * the alias to /data/user/0/dev.servers.app/home).
 */
public final class AppPrivateHome {
    private static final String HOME_NAME = "home";

    private AppPrivateHome() {}

    public static Layout ensure(Context context) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        File dataDir = context.getDataDir();
        if (dataDir == null || !dataDir.isDirectory()) {
            throw new IOException("Android did not provide a valid private data directory");
        }

        Path dataPath = dataDir.toPath().toRealPath();
        Path homePath = dataPath.resolve(HOME_NAME);
        ensureDirectory(homePath);

        Path runtime = homePath.resolve("runtime");
        Path rootfs = homePath.resolve("rootfs");
        Path rootfsBackup = homePath.resolve("rootfs.previous");
        Path servers = homePath.resolve("servers");
        Path logs = homePath.resolve("logs");
        Path cache = homePath.resolve("cache");
        Path config = homePath.resolve("config");
        ensureDirectory(runtime);
        // rootfs is managed transactionally by RootfsInstaller. Do not create
        // an empty rootfs directory here: after a process death between the
        // two activation renames, rootfs.previous may be the only valid guest.
        // Recovery is an explicit operation performed before taking the shared
        // server lease; ensure() itself must remain safe during stop requests.
        validateManagedRootfsPath(rootfs);
        validateManagedRootfsPath(rootfsBackup);
        ensureDirectory(servers);
        ensureDirectory(logs);
        ensureDirectory(cache);
        ensureDirectory(config);

        Path canonicalHome = homePath.toRealPath();
        return new Layout(canonicalHome.toFile(), runtime.toRealPath().toFile(),
                rootfs.toFile(), servers.toRealPath().toFile(),
                logs.toRealPath().toFile(), cache.toRealPath().toFile(),
                config.toRealPath().toFile());
    }

    /** Resolve a relative path without allowing absolute paths, traversal or symlinks. */
    public static File resolveUnderHome(Context context, String relativePath) throws IOException {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("A non-empty relative path is required");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new IOException("NUL is not valid in a path");
        }

        Layout layout = ensure(context);
        Path home = layout.home.toPath().toRealPath();
        String portable = relativePath.replace('\\', '/');
        Path relative = Paths.get(portable);
        if (relative.isAbsolute()) {
            throw new IOException("Absolute paths are not allowed");
        }
        for (Path segment : relative) {
            String part = segment.toString();
            if ("..".equals(part) || ".".equals(part) || part.isEmpty()) {
                throw new IOException("Dot segments are not allowed in a path");
            }
        }

        Path target = home.resolve(relative).normalize();
        if (!target.startsWith(home) || target.equals(home)) {
            throw new IOException("Path escapes the private home directory");
        }

        Path cursor = home;
        for (Path segment : home.relativize(target)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Symbolic links are not allowed inside private home");
            }
        }
        return target.toFile();
    }

    /**
     * Recover a previously installed rootfs after a process death interrupted
     * its atomic activation. Call before acquiring the shared server runtime
     * lease. This method intentionally is not part of ensure(), because stop
     * requests call ensure() while a shared lease is already held.
     */
    public static void recoverInterruptedRootfs(Layout home) throws IOException {
        if (home == null) throw new IllegalArgumentException("Home layout is required");
        Path live = new File(home.home, "rootfs").toPath();
        Path backup = new File(home.home, "rootfs.previous").toPath();
        if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)
                || !Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) return;

        File installLockFile = new File(home.runtime, "rootfs-install.lock");
        try (RandomAccessFile lockRaf = new RandomAccessFile(installLockFile, "rw");
             FileChannel channel = lockRaf.getChannel();
             FileLock ignored = channel.lock();
             RootfsRuntimeLock.Lease runtimeLease = RootfsRuntimeLock.acquireExclusive(home.runtime)) {
            boolean liveExists = Files.exists(live, LinkOption.NOFOLLOW_LINKS);
            boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
            if (!liveExists && backupExists) {
                if (Files.isSymbolicLink(backup) || !Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Interrupted rootfs backup is not a real directory: " + backup);
                }
                try {
                    Files.move(backup, live);
                } catch (IOException error) {
                    throw new IOException("Could not recover the previous Debian rootfs", error);
                }
            }
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("Rootfs recovery is already running", busy);
        }
    }

    private static void validateManagedRootfsPath(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed rootfs path must be absent or a real directory: " + path);
        }
    }

    private static void ensureDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            requireRealDirectory(path);
            return;
        }
        try {
            Files.createDirectory(path);
        } catch (FileAlreadyExistsException race) {
            // Another Activity/Service may have created it concurrently.
        }
        requireRealDirectory(path);
    }

    private static void requireRealDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a real directory, found another file type: " + path);
        }
    }

    public static final class Layout {
        public final File home;
        public final File runtime;
        public final File rootfs;
        public final File servers;
        public final File logs;
        public final File cache;
        public final File config;

        private Layout(File home, File runtime, File rootfs, File servers,
                       File logs, File cache, File config) {
            this.home = home;
            this.runtime = runtime;
            this.rootfs = rootfs;
            this.servers = servers;
            this.logs = logs;
            this.cache = cache;
            this.config = config;
        }
    }
}

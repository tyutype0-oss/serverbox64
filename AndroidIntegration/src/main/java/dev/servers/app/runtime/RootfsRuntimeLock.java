package dev.servers.app.runtime;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/**
 * Coordinates rootfs use across activities/services and installer workers.
 * The service holds one shared lease while any server is running. Installation
 * takes the exclusive lease, so a rootfs can never be swapped under a live PRoot.
 */
public final class RootfsRuntimeLock {
    private static final String LOCK_NAME = "rootfs-runtime.lock";

    private RootfsRuntimeLock() {}

    public static Lease acquireShared(File runtimeDirectory) throws IOException {
        return acquire(runtimeDirectory, true);
    }

    public static Lease acquireExclusive(File runtimeDirectory) throws IOException {
        return acquire(runtimeDirectory, false);
    }

    private static Lease acquire(File runtimeDirectory, boolean shared) throws IOException {
        if (runtimeDirectory == null) throw new IllegalArgumentException("Runtime directory is required");
        if (!runtimeDirectory.isDirectory() && !runtimeDirectory.mkdirs()) {
            throw new IOException("Cannot create runtime lock directory");
        }
        File file = new File(runtimeDirectory, LOCK_NAME);
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        FileChannel channel = raf.getChannel();
        try {
            FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, shared);
            if (lock == null) {
                throw new IOException(shared
                        ? "The Linux runtime is being installed; try starting again"
                        : "A server runtime is active; stop all servers before replacing Debian");
            }
            if (shared && !lock.isShared()) {
                lock.release();
                throw new IOException("Shared rootfs lease is not supported by this filesystem");
            }
            return new Lease(raf, channel, lock);
        } catch (OverlappingFileLockException overlap) {
            closeQuietly(channel, raf);
            throw new IOException(shared
                    ? "A rootfs installation is already running"
                    : "A server runtime is active; stop all servers before replacing Debian", overlap);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(channel, raf);
            throw failure;
        }
    }

    private static void closeQuietly(FileChannel channel, RandomAccessFile raf) {
        try { channel.close(); } catch (IOException ignored) {}
        try { raf.close(); } catch (IOException ignored) {}
    }

    public static final class Lease implements Closeable {
        private final RandomAccessFile raf;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Lease(RandomAccessFile raf, FileChannel channel, FileLock lock) {
            this.raf = raf;
            this.channel = channel;
            this.lock = lock;
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try { lock.release(); } catch (IOException error) { failure = error; }
            try { channel.close(); } catch (IOException error) { if (failure == null) failure = error; }
            try { raf.close(); } catch (IOException error) { if (failure == null) failure = error; }
            if (failure != null) throw failure;
        }
    }
}

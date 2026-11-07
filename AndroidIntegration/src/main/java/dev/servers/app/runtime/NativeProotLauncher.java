package dev.servers.app.runtime;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/**
 * Starts the Debian/Box64 guest using Android-native PRoot and its external
 * loader. This code must run from ServerRuntimeService (a foreground service).
 */
public final class NativeProotLauncher {
    private static final String GUEST_SERVER_DIR = "/srv/server";
    private static final String GUEST_SUPERVISOR = "/usr/local/sbin/serverbox64-supervisor";
    private static final String GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    private NativeProotLauncher() {}

    public static Process startServer(Context context,
                                      AppPrivateHome.Layout home,
                                      String serverId,
                                      File serverDirectory,
                                      String startCommand) throws IOException {
        return startServer(context, home, serverId, serverDirectory,
                startCommand, false, 0);
    }

    public static Process startServer(Context context,
                                      AppPrivateHome.Layout home,
                                      String serverId,
                                      File serverDirectory,
                                      String startCommand,
                                      boolean autoRestart,
                                      int maxRestarts) throws IOException {
        if (context == null || home == null) {
            throw new IllegalArgumentException("Context and home layout are required");
        }
        if (serverId == null || !serverId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IOException("Invalid server ID");
        }
        if (startCommand == null || startCommand.trim().isEmpty()) {
            throw new IOException("The server start command is empty");
        }
        if (maxRestarts < 0 || maxRestarts > 99999) {
            throw new IOException("maxRestarts must be from 0 to 99999");
        }

        File rootfs = home.rootfs.getCanonicalFile();
        File serversRoot = home.servers.getCanonicalFile();
        File expectedServerDir = new File(serversRoot, serverId).getCanonicalFile();
        if (serverDirectory == null || Files.isSymbolicLink(serverDirectory.toPath())) {
            throw new IOException("Server directory must not be a symbolic link");
        }
        File serverDir = serverDirectory.getCanonicalFile();
        if (!serverDir.equals(expectedServerDir) || !serverDir.isDirectory()) {
            throw new IOException("Server directory must be exactly home/servers/<server-id>");
        }
        if (!new File(rootfs, "bin/sh").exists()) {
            throw new IOException("Linux rootfs is not installed: " + rootfs);
        }
        if (!new File(rootfs, GUEST_SUPERVISOR.substring(1)).isFile()) {
            throw new IOException("Guest supervisor is missing; reinstall the Linux runtime");
        }
        if (!new File(rootfs, "usr/bin/box64").isFile()) {
            throw new IOException("Box64 is missing from the Linux rootfs");
        }

        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        File loader = new File(nativeDir, "libproot-loader.so");
        if (!proot.isFile() || !proot.canExecute() || !loader.isFile() || !loader.canExecute()) {
            throw new IOException("Android PRoot/loader missing or not executable in nativeLibraryDir");
        }

        File temp = new File(home.cache, "proot-tmp");
        File guestServerMount = new File(rootfs, "srv/server");
        File serverState = new File(serverDir, ".servers");
        ensureDirectory(temp);
        ensureDirectory(guestServerMount);
        ensureDirectory(serverState);
        // Clear only the previous stop request before the new process starts.
        File staleStopRequest = new File(serverState, "stop");
        if (staleStopRequest.exists() && !staleStopRequest.delete()) {
            throw new IOException("Could not clear previous server stop request");
        }

        // Android's DNS configuration is managed by ConnectivityService, not
        // by a conventional /etc/resolv.conf visible to glibc. Mirror the active
        // network DNS servers into app-private storage and bind them into Debian.
        File guestResolver = GuestNetworkConfig.writeResolvConf(context, home.cache);

        File log = new File(home.logs, serverId + ".log");
        if (Files.isSymbolicLink(log.toPath())) throw new IOException("Server log must not be a symbolic link");
        rotateLog(log, 5L * 1024L * 1024L);

        List<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("--kill-on-exit");
        args.add("--link2symlink");
        args.add("--sysvipc");
        args.add("--rootfs=" + rootfs.getAbsolutePath());
        args.add("--bind=/dev");
        args.add("--bind=/proc");
        args.add("--bind=/sys");
        args.add("--bind=" + guestResolver.getAbsolutePath() + ":/etc/resolv.conf");
        args.add("--bind=" + serverDir.getAbsolutePath() + ":" + GUEST_SERVER_DIR);
        args.add("--env=BOX64_LD_LIBRARY_PATH=/usr/lib/box64-x86_64-linux-gnu");
        args.add("-0");
        args.add("-w");
        args.add(GUEST_SERVER_DIR);
        args.add(GUEST_SUPERVISOR);
        args.add(GUEST_SERVER_DIR);
        args.add(startCommand);
        args.add(autoRestart ? "1" : "0");
        args.add(Integer.toString(maxRestarts));

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(home.home);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        Map<String, String> env = builder.environment();
        env.put("PROOT_LOADER", loader.getAbsolutePath());
        env.put("PROOT_TMP_DIR", temp.getAbsolutePath());
        env.put("HOME", "/root");
        env.put("PATH", GUEST_PATH);
        env.put("TMPDIR", "/tmp");
        env.put("LANG", "C.UTF-8");
        env.put("BOX64_LD_LIBRARY_PATH", "/usr/lib/box64-x86_64-linux-gnu");
        String oldLd = env.get("LD_LIBRARY_PATH");
        env.put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath()
                + (oldLd == null || oldLd.isEmpty() ? "" : File.pathSeparator + oldLd));

        return builder.start();
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(dir.toPath())
                    || !Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Expected a real directory, found another file type: " + dir);
            }
            return;
        }
        Files.createDirectories(dir.toPath());
        if (Files.isSymbolicLink(dir.toPath())
                || !Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Could not create a safe directory: " + dir);
        }
    }

    private static void rotateLog(File log, long limit) throws IOException {
        if (!log.exists() || log.length() <= limit) return;
        File previous = new File(log.getParentFile(), log.getName() + ".1");
        if (previous.exists() && !previous.delete()) {
            throw new IOException("Could not rotate old log: " + previous);
        }
        if (!log.renameTo(previous)) {
            throw new IOException("Could not rotate log: " + log);
        }
    }
}

package dev.servers.app.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Foreground owner for the Android-native PRoot process and its Linux guest
 * server supervisors. Integrate into the real app manifest and start only from
 * an explicit user action while the app is visible.
 */
public final class ServerRuntimeService extends Service {
    public static final String ACTION_START = "dev.servers.app.runtime.START_SERVER";
    public static final String ACTION_STOP = "dev.servers.app.runtime.STOP_SERVER";
    public static final String ACTION_STOP_ALL = "dev.servers.app.runtime.STOP_ALL";

    public static final String EXTRA_SERVER_ID = "server_id";
    public static final String EXTRA_START_COMMAND = "start_command";
    public static final String EXTRA_AUTO_RESTART = "auto_restart";
    public static final String EXTRA_MAX_RESTARTS = "max_restarts";

    private static final String TAG = "ServerRuntimeService";
    private static final String CHANNEL_ID = "server_runtime";
    private static final int NOTIFICATION_ID = 5104;
    private static final long GRACEFUL_STOP_SECONDS = 14;

    private final ConcurrentHashMap<String, Process> running = new ConcurrentHashMap<>();
    private final java.util.Set<String> starting = ConcurrentHashMap.newKeySet();
    private final ExecutorService waiters = Executors.newCachedThreadPool();
    private volatile boolean stopping;
    private volatile boolean reconcilingDesired;
    private PowerManager.WakeLock wakeLock;
    private RootfsRuntimeLock.Lease rootfsLease;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android requires promotion immediately; do not extract rootfs or start
        // network work before the persistent notification is visible.
        promoteToForeground();

        if (intent == null || intent.getAction() == null) {
            // START_STICKY may recreate this service with a null Intent after
            // Android kills the app process. Reconcile the saved desired state.
            restoreDesiredServers();
            updateNotification();
            finishIfIdle();
            return START_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            String id = intent.getStringExtra(EXTRA_SERVER_ID);
            String command = intent.getStringExtra(EXTRA_START_COMMAND);
            boolean autoRestart = intent.getBooleanExtra(EXTRA_AUTO_RESTART, false);
            int maxRestarts = intent.getIntExtra(EXTRA_MAX_RESTARTS, 0);
            try {
                DesiredServerStore.save(this, id, command, autoRestart, maxRestarts);
                startOne(id, command, autoRestart, maxRestarts);
            } catch (RuntimeException invalid) {
                Log.e(TAG, "Could not persist or start the desired server", invalid);
                finishIfIdle();
            }
        } else if (ACTION_STOP.equals(action)) {
            String id = intent.getStringExtra(EXTRA_SERVER_ID);
            removeDesiredSafely(id);
            stopOne(id);
        } else if (ACTION_STOP_ALL.equals(action)) {
            try { DesiredServerStore.clear(this); }
            catch (RuntimeException error) { Log.e(TAG, "Could not persist stop-all state", error); }
            stopAllAsync(startId);
        } else {
            Log.w(TAG, "Ignoring unknown service action: " + action);
        }

        updateNotification();
        finishIfIdle();
        return START_STICKY;
    }

    private void removeDesiredSafely(String serverId) {
        try { DesiredServerStore.remove(this, serverId); }
        catch (RuntimeException error) { Log.e(TAG, "Could not persist stopped state for " + serverId, error); }
    }

    private void restoreDesiredServers() {
        // Prevent a failed first entry from stopping the service while later
        // persisted servers are still being reconciled on this same callback.
        reconcilingDesired = true;
        try {
            for (DesiredServerStore.ServerSpec spec : DesiredServerStore.load(this)) {
                startOne(spec.id, spec.command, spec.autoRestart, spec.maxRestarts);
            }
        } finally {
            reconcilingDesired = false;
        }
        updateNotification();
    }

    private void startOne(String serverId, String command,
                          boolean autoRestart, int maxRestarts) {
        if (stopping) {
            Log.w(TAG, "Service is stopping; refusing new server start");
            removeDesiredSafely(serverId);
            return;
        }
        if (!isValidServerId(serverId)) {
            Log.e(TAG, "Invalid server ID");
            return;
        }
        if (maxRestarts < 0 || maxRestarts > 99999) {
            Log.e(TAG, "Invalid maximum restart count");
            finishIfIdle();
            return;
        }
        if (running.containsKey(serverId) || !starting.add(serverId)) {
            Log.i(TAG, "Server already running or starting: " + serverId);
            return;
        }

        try {
            AppPrivateHome.Layout home = AppPrivateHome.ensure(this);
            // Recovery must happen before we take the shared PRoot runtime lease.
            AppPrivateHome.recoverInterruptedRootfs(home);
            File serverDir = new File(home.servers, serverId);
            String effectiveCommand = (command == null || command.trim().isEmpty())
                    ? ServerCommandResolver.resolve(serverDir) : command;
            acquireRootfsLease(home.runtime);
            Process process = NativeProotLauncher.startServer(
                    this, home, serverId, serverDir, effectiveCommand, autoRestart, maxRestarts);
            Process previous = running.putIfAbsent(serverId, process);
            if (previous != null) {
                // Do not touch the shared stop marker: it belongs to the
                // already-running server and would stop the valid instance.
                terminate(process);
                Log.w(TAG, "Concurrent duplicate start discarded: " + serverId);
                return;
            }
            acquireCpuWakeLock();
            waiters.execute(() -> waitForExit(serverId, process));
            updateNotification();
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Could not start server " + serverId, error);
            removeDesiredSafely(serverId);
            finishIfIdle();
        } finally {
            starting.remove(serverId);
            finishIfIdle();
        }
    }

    private void waitForExit(String serverId, Process process) {
        int exitCode = -1;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            boolean wasRunning = running.remove(serverId, process);
            if (wasRunning && !stopping) removeDesiredSafely(serverId);
            Log.i(TAG, "Server supervisor exited: " + serverId + ", code=" + exitCode);
            updateNotification();
            finishIfIdle();
        }
    }

    private void stopOne(String serverId) {
        if (!isValidServerId(serverId)) return;
        Process process = running.get(serverId);
        if (process == null) {
            finishIfIdle();
            return;
        }
        // Do not block Android's main service thread while the guest performs
        // its graceful shutdown. The guest monitor watches .servers/stop and
        // terminates the server child before the PRoot process is killed.
        waiters.execute(() -> stopOneInternal(serverId, process));
    }

    private void stopOneInternal(String serverId, Process process) {
        requestStopFileQuietly(serverId);
        waitThenTerminate(process);
        running.remove(serverId, process);
        updateNotification();
        finishIfIdle();
    }

    private void stopAllAsync(int startId) {
        stopping = true;
        waiters.execute(() -> {
            for (Map.Entry<String, Process> entry : running.entrySet()) {
                if (running.remove(entry.getKey(), entry.getValue())) {
                    requestStopFileQuietly(entry.getKey());
                    waitThenTerminate(entry.getValue());
                }
            }
            releaseCpuWakeLock();
            releaseRootfsLease();
            updateNotification();
            stopForegroundCompat();
            stopSelf(startId);
        });
    }

    private void requestStopFileQuietly(String serverId) {
        try {
            AppPrivateHome.Layout home = AppPrivateHome.ensure(this);
            requestStopFile(home, serverId);
        } catch (IOException error) {
            Log.w(TAG, "Could not write stop request for " + serverId, error);
        }
    }

    private static void requestStopFile(AppPrivateHome.Layout home, String serverId) throws IOException {
        if (!isValidServerId(serverId)) throw new IOException("Invalid server ID");
        File serversRoot = home.servers.getCanonicalFile();
        File serverDir = new File(serversRoot, serverId).getCanonicalFile();
        String prefix = serversRoot.getPath() + File.separator;
        if (!serverDir.getPath().startsWith(prefix) || !serverDir.isDirectory()) {
            throw new IOException("Server directory is outside home/servers");
        }
        File stateDir = new File(serverDir, ".servers");
        if (!stateDir.exists() && !stateDir.mkdirs() && !stateDir.isDirectory()) {
            throw new IOException("Cannot create server state directory");
        }
        File stopFile = new File(stateDir, "stop");
        if (!stopFile.exists() && !stopFile.createNewFile()) {
            throw new IOException("Cannot create stop request");
        }
    }

    private static boolean isValidServerId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,64}");
    }

    private static void waitThenTerminate(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            if (!process.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                terminate(process);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminate(process);
        }
    }

    private static void terminate(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void acquireCpuWakeLock() {
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager == null) return;
        if (wakeLock == null) {
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":server-runtime");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseCpuWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private synchronized void acquireRootfsLease(File runtimeDirectory) throws IOException {
        if (rootfsLease == null) rootfsLease = RootfsRuntimeLock.acquireShared(runtimeDirectory);
    }

    private synchronized void releaseRootfsLease() {
        if (rootfsLease == null) return;
        try { rootfsLease.close(); }
        catch (IOException error) { Log.w(TAG, "Could not release rootfs lease", error); }
        rootfsLease = null;
    }

    private void finishIfIdle() {
        if (!running.isEmpty() || !starting.isEmpty() || stopping || reconcilingDesired) return;
        releaseCpuWakeLock();
        releaseRootfsLease();
        stopForegroundCompat();
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Local game servers", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps user-started local game servers running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void promoteToForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, ServerRuntimeService.class)
                .setAction(ACTION_STOP_ALL);
        PendingIntent stopPending = PendingIntent.getService(
                this, 5105, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("ServerBox64")
                .setContentText(running.size() + " server process(es) active")
                .setOngoing(!running.isEmpty())
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        "Stop all", stopPending).build())
                .build();
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        for (Map.Entry<String, Process> entry : running.entrySet()) {
            if (running.remove(entry.getKey(), entry.getValue())) {
                requestStopFileQuietly(entry.getKey());
                terminate(entry.getValue());
            }
        }
        releaseCpuWakeLock();
        releaseRootfsLease();
        waiters.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

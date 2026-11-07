package dev.servers.app.runtime;

import android.content.Context;
import android.content.Intent;

/** Public entry point for Activity/UI code to control the standalone runtime. */
public final class ServerRuntimeController {
    private ServerRuntimeController() {}

    public static void start(Context context, String serverId, String command,
                             boolean autoRestart, int maxRestarts) {
        requireContext(context);
        if (serverId == null || !serverId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid server ID");
        }
        if (command != null && command.length() > 16384) {
            throw new IllegalArgumentException("Start command exceeds 16 KiB");
        }
        if (maxRestarts < 0 || maxRestarts > 99999) {
            throw new IllegalArgumentException("Invalid maximum restart count");
        }

        Intent intent = new Intent(context, ServerRuntimeService.class)
                .setAction(ServerRuntimeService.ACTION_START)
                .putExtra(ServerRuntimeService.EXTRA_SERVER_ID, serverId)
                .putExtra(ServerRuntimeService.EXTRA_START_COMMAND, command)
                .putExtra(ServerRuntimeService.EXTRA_AUTO_RESTART, autoRestart)
                .putExtra(ServerRuntimeService.EXTRA_MAX_RESTARTS, maxRestarts);
        context.startForegroundService(intent);
    }

    public static void stop(Context context, String serverId) {
        requireContext(context);
        if (serverId == null || !serverId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid server ID");
        }
        Intent intent = new Intent(context, ServerRuntimeService.class)
                .setAction(ServerRuntimeService.ACTION_STOP)
                .putExtra(ServerRuntimeService.EXTRA_SERVER_ID, serverId);
        context.startForegroundService(intent);
    }

    public static void stopAll(Context context) {
        requireContext(context);
        Intent intent = new Intent(context, ServerRuntimeService.class)
                .setAction(ServerRuntimeService.ACTION_STOP_ALL);
        context.startForegroundService(intent);
    }

    private static void requireContext(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
    }
}

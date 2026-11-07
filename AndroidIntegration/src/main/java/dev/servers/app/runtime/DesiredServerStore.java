package dev.servers.app.runtime;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persists user-requested active servers so a sticky service can reconcile after process death. */
public final class DesiredServerStore {
    private static final String PREFS = "server_runtime_state";
    private static final String IDS = "server_ids";
    private static final Object LOCK = new Object();

    private DesiredServerStore() {}

    public static void save(Context context, String id, String command,
                            boolean autoRestart, int maxRestarts) {
        validate(id, maxRestarts);
        if (command != null && command.length() > 16384) {
            throw new IllegalArgumentException("Start command exceeds 16 KiB");
        }
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(context);
            Set<String> ids = copyIds(prefs.getStringSet(IDS, Collections.emptySet()));
            ids.add(id);
            SharedPreferences.Editor editor = prefs.edit()
                    .putStringSet(IDS, ids)
                    .putString("command:" + id, command == null ? "" : command)
                    .putBoolean("auto:" + id, autoRestart)
                    .putInt("max:" + id, maxRestarts);
            if (!editor.commit()) throw new IllegalStateException("Could not persist desired server state");
        }
    }

    public static void remove(Context context, String id) {
        if (context == null || !isValidId(id)) return;
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(context);
            Set<String> ids = copyIds(prefs.getStringSet(IDS, Collections.emptySet()));
            ids.remove(id);
            SharedPreferences.Editor editor = prefs.edit()
                    .putStringSet(IDS, ids)
                    .remove("command:" + id)
                    .remove("auto:" + id)
                    .remove("max:" + id);
            if (!editor.commit()) throw new IllegalStateException("Could not persist stopped server state");
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (!preferences(context).edit().clear().commit()) {
                throw new IllegalStateException("Could not persist stop-all state");
            }
        }
    }

    public static List<ServerSpec> load(Context context) {
        if (context == null) return Collections.emptyList();
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(context);
            Set<String> ids = copyIds(prefs.getStringSet(IDS, Collections.emptySet()));
            List<ServerSpec> result = new ArrayList<>();
            for (String id : ids) {
                if (!isValidId(id)) continue;
                String command = prefs.getString("command:" + id, "");
                boolean auto = prefs.getBoolean("auto:" + id, false);
                int maximum = prefs.getInt("max:" + id, 0);
                if (maximum < 0 || maximum > 99999) continue;
                result.add(new ServerSpec(id, command == null || command.trim().isEmpty() ? null : command,
                        auto, maximum));
            }
            Collections.sort(result, (a, b) -> a.id.compareTo(b.id));
            return result;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> copyIds(Set<String> ids) {
        return ids == null ? new HashSet<>() : new HashSet<>(ids);
    }

    private static void validate(String id, int maxRestarts) {
        if (!isValidId(id)) throw new IllegalArgumentException("Invalid server ID");
        if (maxRestarts < 0 || maxRestarts > 99999) {
            throw new IllegalArgumentException("Invalid maximum restart count");
        }
    }

    private static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,64}");
    }

    public static final class ServerSpec {
        public final String id;
        public final String command;
        public final boolean autoRestart;
        public final int maxRestarts;

        private ServerSpec(String id, String command, boolean autoRestart, int maxRestarts) {
            this.id = id;
            this.command = command;
            this.autoRestart = autoRestart;
            this.maxRestarts = maxRestarts;
        }
    }
}

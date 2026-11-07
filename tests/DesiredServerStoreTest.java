package dev.servers.app.runtime;

import android.content.Context;
import android.content.InMemorySharedPreferences;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DesiredServerStoreTest {
    private static final class TestContext extends Context {
        private final Map<String, SharedPreferences> store = new HashMap<>();
        @Override public File getDataDir() { return new File(System.getProperty("java.io.tmpdir")); }
        @Override public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
            return store.computeIfAbsent(name, ignored -> new InMemorySharedPreferences());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        TestContext context = new TestContext();
        DesiredServerStore.save(context, "bedrock-main", "LD_LIBRARY_PATH=. box64 ./bedrock_server", true, 7);
        DesiredServerStore.save(context, "java-test", null, false, 0);
        List<DesiredServerStore.ServerSpec> saved = DesiredServerStore.load(context);
        check(saved.size() == 2, "both desired servers must be persisted");
        check(saved.get(0).id.equals("bedrock-main"), "desired server list must be deterministic");
        check(saved.get(0).command.contains("box64"), "custom command was not preserved");
        check(saved.get(0).autoRestart && saved.get(0).maxRestarts == 7,
                "restart settings were not preserved");
        check(saved.get(1).command == null && !saved.get(1).autoRestart,
                "default command and restart settings were not preserved");

        DesiredServerStore.remove(context, "bedrock-main");
        check(DesiredServerStore.load(context).size() == 1, "single-server stop must remove only that entry");
        DesiredServerStore.clear(context);
        check(DesiredServerStore.load(context).isEmpty(), "stop-all must clear desired server state");

        boolean invalidRejected = false;
        try { DesiredServerStore.save(context, "../escape", "true", false, 0); }
        catch (IllegalArgumentException expected) { invalidRejected = true; }
        check(invalidRejected, "unsafe server IDs must not enter persistent state");
        System.out.println("PASS: desired servers persist and reconcile safely across sticky-service recreation");
    }
}

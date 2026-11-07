package dev.servers.app.runtime;

import android.content.Context;
import android.content.Intent;

import java.io.File;

public final class ServerRuntimeControllerTest {
    private static final class TestContext extends Context {
        Intent last;
        int starts;
        @Override public File getDataDir() { return new File(System.getProperty("java.io.tmpdir")); }
        @Override public void startForegroundService(Intent intent) { last = intent; starts++; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        TestContext context = new TestContext();
        ServerRuntimeController.start(context, "bedrock-1", "./bedrock_server", true, 12);
        check(context.starts == 1, "start must dispatch one foreground-service request");
        check(ServerRuntimeService.ACTION_START.equals(context.last.getAction()), "wrong start action");
        check("bedrock-1".equals(context.last.getStringExtra(ServerRuntimeService.EXTRA_SERVER_ID)),
                "server ID missing from start request");
        check("./bedrock_server".equals(context.last.getStringExtra(ServerRuntimeService.EXTRA_START_COMMAND)),
                "start command missing from start request");
        check(context.last.getBooleanExtra(ServerRuntimeService.EXTRA_AUTO_RESTART, false),
                "auto-restart setting missing from start request");
        check(context.last.getIntExtra(ServerRuntimeService.EXTRA_MAX_RESTARTS, 0) == 12,
                "restart limit missing from start request");

        ServerRuntimeController.stop(context, "bedrock-1");
        check(ServerRuntimeService.ACTION_STOP.equals(context.last.getAction()), "wrong stop action");
        ServerRuntimeController.stopAll(context);
        check(ServerRuntimeService.ACTION_STOP_ALL.equals(context.last.getAction()), "wrong stop-all action");

        int before = context.starts;
        boolean rejected = false;
        try { ServerRuntimeController.start(context, "../bad", "true", false, 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && context.starts == before, "unsafe IDs must be rejected before dispatch");
        System.out.println("PASS: Activity controller dispatches explicit foreground-service start/stop actions");
    }
}

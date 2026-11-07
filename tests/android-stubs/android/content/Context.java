package android.content;

import android.content.pm.ApplicationInfo;
import java.io.File;

/** Minimal test-only Android Context stub. */
public abstract class Context {
    public static final String POWER_SERVICE = "power";
    public static final int MODE_PRIVATE = 0;
    public abstract File getDataDir();
    public ApplicationInfo getApplicationInfo() { return new ApplicationInfo(); }
    public Object getSystemService(String name) { return null; }
    public <T> T getSystemService(Class<T> type) { return null; }
    public String getPackageName() { return "dev.servers.app"; }
    public void startForegroundService(Intent intent) { }
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
}

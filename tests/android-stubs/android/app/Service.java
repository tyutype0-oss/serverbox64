package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
public abstract class Service extends Context {
    public static final int START_STICKY = 1;
    public static final int START_NOT_STICKY = 2;
    public static final int STOP_FOREGROUND_REMOVE = 1;
    @Override public File getDataDir() { return new File(System.getProperty("java.io.tmpdir"), "serverbox-service-data"); }
    public void onCreate() {}
    public int onStartCommand(Intent intent, int flags, int startId) { return 0; }
    public void onDestroy() {}
    public abstract IBinder onBind(Intent intent);
    public void startForeground(int id, Notification n) {}
    public void startForeground(int id, Notification n, int type) {}
    public void stopForeground(int flags) {}
    public void stopForeground(boolean remove) {}
    public void stopSelf() {}
    public void stopSelf(int id) {}
}

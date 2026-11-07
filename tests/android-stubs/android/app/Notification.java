package android.app;
import android.content.Context;
import android.graphics.drawable.Icon;
public class Notification {
    public static final String CATEGORY_SERVICE = "service";
    public static class Builder {
        public Builder(Context c) {}
        public Builder(Context c, String channel) {}
        public Builder setSmallIcon(int id) { return this; }
        public Builder setContentTitle(CharSequence s) { return this; }
        public Builder setContentText(CharSequence s) { return this; }
        public Builder setOngoing(boolean b) { return this; }
        public Builder setCategory(String s) { return this; }
        public Builder addAction(Action a) { return this; }
        public Notification build() { return new Notification(); }
    }
    public static class Action {
        public static class Builder {
            public Builder(Icon i, CharSequence title, PendingIntent pi) {}
            public Action build() { return new Action(); }
        }
    }
}

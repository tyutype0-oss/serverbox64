package android.content;
import java.util.Set;
public interface SharedPreferences {
    Set<String> getStringSet(String key, Set<String> fallback);
    String getString(String key, String fallback);
    boolean getBoolean(String key, boolean fallback);
    int getInt(String key, int fallback);
    Editor edit();
    interface Editor {
        Editor putStringSet(String key, Set<String> value);
        Editor putString(String key, String value);
        Editor putBoolean(String key, boolean value);
        Editor putInt(String key, int value);
        Editor remove(String key);
        Editor clear();
        void apply();
        boolean commit();
    }
}

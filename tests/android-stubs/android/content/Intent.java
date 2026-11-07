package android.content;

public class Intent {
    private String action;
    private final java.util.Map<String, Object> extras = new java.util.HashMap<>();
    public Intent() {}
    public Intent(Context context, Class<?> type) {}
    public Intent setAction(String value) { action = value; return this; }
    public String getAction() { return action; }
    public Intent putExtra(String key, String value) { extras.put(key, value); return this; }
    public Intent putExtra(String key, boolean value) { extras.put(key, value); return this; }
    public Intent putExtra(String key, int value) { extras.put(key, value); return this; }
    public String getStringExtra(String key) { Object v=extras.get(key); return v instanceof String ? (String)v : null; }
    public boolean getBooleanExtra(String key, boolean fallback) { Object v=extras.get(key); return v instanceof Boolean ? (Boolean)v : fallback; }
    public int getIntExtra(String key, int fallback) { Object v=extras.get(key); return v instanceof Integer ? (Integer)v : fallback; }
}

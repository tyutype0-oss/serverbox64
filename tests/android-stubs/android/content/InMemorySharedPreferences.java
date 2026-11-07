package android.content;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
public final class InMemorySharedPreferences implements SharedPreferences {
    private final Map<String,Object> values = new HashMap<>();
    @SuppressWarnings("unchecked") public synchronized Set<String> getStringSet(String k, Set<String> d) { Object v=values.get(k); return v instanceof Set ? new HashSet<>((Set<String>)v) : d; }
    public synchronized String getString(String k,String d) { Object v=values.get(k); return v instanceof String ? (String)v : d; }
    public synchronized boolean getBoolean(String k,boolean d) { Object v=values.get(k); return v instanceof Boolean ? (Boolean)v : d; }
    public synchronized int getInt(String k,int d) { Object v=values.get(k); return v instanceof Integer ? (Integer)v : d; }
    public Editor edit() { return new MemoryEditor(); }
    private final class MemoryEditor implements Editor {
        private final Map<String,Object> updates=new HashMap<>(); private final Set<String> removes=new HashSet<>(); private boolean clear;
        public Editor putStringSet(String k,Set<String> v){updates.put(k,new HashSet<>(v));removes.remove(k);return this;}
        public Editor putString(String k,String v){updates.put(k,v);removes.remove(k);return this;}
        public Editor putBoolean(String k,boolean v){updates.put(k,v);removes.remove(k);return this;}
        public Editor putInt(String k,int v){updates.put(k,v);removes.remove(k);return this;}
        public Editor remove(String k){removes.add(k);updates.remove(k);return this;}
        public Editor clear(){clear=true;updates.clear();removes.clear();return this;}
        public void apply(){commit();}
        public boolean commit(){synchronized(InMemorySharedPreferences.this){if(clear)values.clear();for(String k:removes)values.remove(k);values.putAll(updates);return true;}}
    }
}

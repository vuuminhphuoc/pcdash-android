package vn.vuumi.pcdash;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class FakeSharedPreferences implements SharedPreferences {
    private final Map<String, Object> map = new HashMap<String, Object>();

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<String, Object>(map);
    }

    @Override
    public String getString(String key, String defValue) {
        Object val = map.get(key);
        return val instanceof String ? (String) val : defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object val = map.get(key);
        return val instanceof Integer ? (Integer) val : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object val = map.get(key);
        return val instanceof Long ? (Long) val : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object val = map.get(key);
        return val instanceof Float ? (Float) val : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object val = map.get(key);
        return val instanceof Boolean ? (Boolean) val : defValue;
    }

    @Override
    public boolean contains(String key) {
        return map.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

    public class FakeEditor implements Editor {
        private final Map<String, Object> pending = new HashMap<String, Object>();
        private boolean clearPending = false;

        @Override
        public Editor putString(String key, String value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            pending.put(key, this);
            return this;
        }

        @Override
        public Editor clear() {
            clearPending = true;
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            if (clearPending) {
                map.clear();
            }
            for (Map.Entry<String, Object> e : pending.entrySet()) {
                if (e.getValue() == this) {
                    map.remove(e.getKey());
                } else {
                    map.put(e.getKey(), e.getValue());
                }
            }
        }
    }
}

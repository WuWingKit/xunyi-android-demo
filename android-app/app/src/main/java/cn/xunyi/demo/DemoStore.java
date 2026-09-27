package cn.xunyi.demo;

import android.content.Context;
import android.content.SharedPreferences;

/** Local demo state. A future device/API repository can replace this boundary. */
final class DemoStore {
    private final SharedPreferences p;
    DemoStore(Context context) { p = context.getSharedPreferences("xunyi_demo_v1", Context.MODE_PRIVATE); }
    boolean get(String key, boolean fallback) { return p.getBoolean(key, fallback); }
    String get(String key, String fallback) { return p.getString(key, fallback); }
    void put(String key, boolean value) { p.edit().putBoolean(key, value).apply(); }
    void put(String key, String value) { p.edit().putString(key, value).apply(); }
    void reset() {
        p.edit().clear().apply();
    }
}

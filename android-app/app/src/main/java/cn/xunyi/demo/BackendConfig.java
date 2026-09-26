package cn.xunyi.demo;

import android.net.Uri;

/** Public HTTPS endpoint. Authentication stays in device-local preferences. */
final class BackendConfig {
    static final String DEFAULT_URL = "https://api.qianban.cloud/xunyi";
    static boolean valid(String value) {
        if (value.isEmpty()) return true;
        Uri uri = Uri.parse(value);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isEmpty();
    }
    private BackendConfig() {}
}

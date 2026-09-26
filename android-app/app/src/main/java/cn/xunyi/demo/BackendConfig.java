package cn.xunyi.demo;

import android.net.Uri;

/** Stores only a future server address; the demo never sends personal data. */
final class BackendConfig {
    static boolean valid(String value) {
        if (value.isEmpty()) return true;
        Uri uri = Uri.parse(value);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isEmpty();
    }
    private BackendConfig() {}
}

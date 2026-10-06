package com.shumtugle.hora;

import android.content.Context;
import android.content.pm.PackageManager;

/** Reads the installed version code; used to re-unpack assets after an update. */
final class BuildConfigLite {
    private BuildConfigLite() {
    }

    static long versionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /**
     * The content stamp the build wrote next to a large asset, so the asset
     * is unpacked again only when it changed; the app's version when there
     * is no stamp.
     */
    static String assetStamp(Context context, String path) {
        try {
            java.io.InputStream in = context.getAssets().open(path);
            try {
                byte[] buf = new byte[64];
                int n = in.read(buf);
                if (n > 0) {
                    String s = new String(buf, 0, n, "US-ASCII").trim();
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
            } finally {
                in.close();
            }
        } catch (java.io.IOException ignored) {
            // No stamp: fall back to the version below.
        }
        return "v" + versionCode(context);
    }
}

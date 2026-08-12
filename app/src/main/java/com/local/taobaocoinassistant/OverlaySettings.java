package com.local.taobaocoinassistant;

import android.content.Context;

/** 保存顶部悬浮状态条开关。默认关闭，避免首次启动时突然覆盖其它 App。 */
public final class OverlaySettings {
    private static final String PREFS = "ui_settings_v1";
    private static final String KEY_TOP_STATUS_OVERLAY = "top_status_overlay";

    private OverlaySettings() {}

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TOP_STATUS_OVERLAY, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TOP_STATUS_OVERLAY, enabled)
                .apply();
    }
}

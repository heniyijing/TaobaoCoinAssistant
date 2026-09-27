package com.local.taobaocoinassistant;

import android.content.Context;

/**
 * 拟人度自检开关。默认关闭——自检只是自查工具，不参与任务执行，
 * 由用户在主界面主动打开后再记录与出报告。
 */
public final class AuditSettings {
    private static final String PREFS = "ui_settings_v1";
    private static final String KEY_MOTION_AUDIT = "motion_audit";

    private AuditSettings() {}

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MOTION_AUDIT, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MOTION_AUDIT, enabled)
                .apply();
    }
}

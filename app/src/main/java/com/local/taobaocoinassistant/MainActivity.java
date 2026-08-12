package com.local.taobaocoinassistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_SHIZUKU = 7001;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView envText;
    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;
    private long lastRenderedLogVersion = -1L;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> {
        if (requestCode == REQ_SHIZUKU) {
            Toast.makeText(this, grantResult == PackageManager.PERMISSION_GRANTED ? "Shizuku 已授权" : "Shizuku 授权失败", Toast.LENGTH_SHORT).show();
            if (grantResult == PackageManager.PERMISSION_GRANTED) ShizukuShell.prepare();
            refresh();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFFFF6A1A);
        getWindow().setNavigationBarColor(0xFFF8F8F8);
        setContentView(buildUi());
        try { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 8001);
        }
        AppState.log("应用已打开。淘金币助手 v1.1 为纯 Shizuku + OCR，无需无障碍；首次使用请授权 Shizuku。");
        handler.post(ticker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (OverlaySettings.isEnabled(this) && Settings.canDrawOverlays(this)) {
            AutomationService.syncOverlay(this);
        }
        refresh();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(18);

        // The entire settings/status page must be scrollable. On short screens or devices
        // with a large display density, a fixed vertical LinearLayout can push the lower
        // controls completely outside the visible area.
        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setClipToPadding(false);
        pageScroll.setBackgroundColor(0xFFF8F8F8);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFF8F8F8);

        LinearLayout hero = cardLayout();
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        hero.setBackground(heroBg());

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        iconLp.rightMargin = dp(14);
        hero.addView(icon, iconLp);

        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        TextView title = new TextView(this);
        title.setText("淘金币助手");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        heroText.addView(title);

        TextView sub = new TextView(this);
        sub.setText("基于 Shizuku + OCR 的自动任务助手，支持新旧版淘金币界面、跨应用跳转，以及自定义黑白名单规则。");
        sub.setTextColor(0xFFFFF3E8);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        sub.setLineSpacing(0f, 1.2f);
        sub.setPadding(0, dp(6), 0, 0);
        heroText.addView(sub);

        TextView version = pill("v1.1", 0x33FFFFFF, 0xFFFFFFFF);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(-2, -2);
        verLp.topMargin = dp(8);
        heroText.addView(version, verLp);

        hero.addView(heroText);
        root.addView(hero);

        TextView tinyHint = new TextView(this);
        tinyHint.setText("运行流程：Shizuku 截图 → OCR 识别 → 状态判断 → Shizuku 点击/滑动。遇到安全验证会停止，首次测试建议看着手机运行。");
        tinyHint.setTextColor(0xFF6B7280);
        tinyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tinyHint.setPadding(dp(2), dp(12), dp(2), dp(10));
        root.addView(tinyHint);

        envText = new TextView(this);
        envText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        envText.setTextColor(0xFF111827);
        envText.setPadding(dp(14), dp(12), dp(14), dp(12));
        envText.setBackground(cardBg(0xFFFFFBEB, 16, 0x22F59E0B, 1));
        root.addView(envText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(14), 0, 0);
        Button shi = ghostButton("授权 Shizuku");
        shi.setOnClickListener(v -> requestShizuku());
        row1.addView(shi, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(10), 0, 0);
        Button start = primaryButton("开始执行");
        start.setOnClickListener(v -> startAutomation());
        row2.addView(start, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button stop = dangerButton("停止");
        stop.setOnClickListener(v -> AutomationService.requestStop(this));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(48), 1);
        sp.setMarginStart(dp(10));
        row2.addView(stop, sp);
        root.addView(row2);

        LinearLayout overlayRow = cardLayout();
        overlayRow.setOrientation(LinearLayout.HORIZONTAL);
        overlayRow.setGravity(Gravity.CENTER_VERTICAL);
        overlayRow.setPadding(dp(14), dp(6), dp(14), dp(6));
        overlayRow.setBackground(cardBg(0xFFFFFFFF, 14, 0x11000000, 1));
        LinearLayout.LayoutParams overlayCardLp = new LinearLayout.LayoutParams(-1, -2);
        overlayCardLp.topMargin = dp(12);

        LinearLayout overlayInfo = new LinearLayout(this);
        overlayInfo.setOrientation(LinearLayout.VERTICAL);
        overlayInfo.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView overlayTitle = new TextView(this);
        overlayTitle.setText("顶部悬浮状态条");
        overlayTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        overlayTitle.setTypeface(Typeface.DEFAULT_BOLD);
        overlayTitle.setTextColor(0xFF111827);
        overlayInfo.addView(overlayTitle);
        TextView overlayDesc = new TextView(this);
        overlayDesc.setText("显示当前运行状态，并提供快速停止按钮。可选开启，不影响 OCR。");
        overlayDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        overlayDesc.setTextColor(0xFF6B7280);
        overlayDesc.setPadding(0, dp(3), 0, 0);
        overlayInfo.addView(overlayDesc);
        overlayRow.addView(overlayInfo);

        Switch overlaySwitch = new Switch(this);
        overlaySwitch.setChecked(OverlaySettings.isEnabled(this));
        overlaySwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            OverlaySettings.setEnabled(this, checked);
            AppState.log(checked ? "已开启顶部悬浮状态条" : "已关闭顶部悬浮状态条");
            if (checked && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮状态条需要一次性‘显示在其他应用上层’权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                AutomationService.syncOverlay(this);
            }
        });
        overlayRow.addView(overlaySwitch);
        root.addView(overlayRow, overlayCardLp);

        Button rules = ghostButton("任务规则设置（黑名单 / 跨应用 / 搜索 / 浏览）");
        rules.setOnClickListener(v -> showTaskRuleDialog());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(48));
        rp.topMargin = dp(12);
        root.addView(rules, rp);

        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        statusText.setTextColor(0xFF111827);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusText.setBackground(cardBg(0xFFFFFFFF, 14, 0x11000000, 1));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(14);
        statusLp.bottomMargin = dp(10);
        root.addView(statusText, statusLp);

        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(0xFF111827);
        logHeader.addView(logTitle, new LinearLayout.LayoutParams(0, -2, 1));

        Button copyLog = ghostButton("复制日志");
        copyLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        copyLog.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("淘金币助手运行日志", AppState.logs()));
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
        logHeader.addView(copyLog, new LinearLayout.LayoutParams(-2, dp(42)));
        root.addView(logHeader);

        logScroll = new ScrollView(this);
        logScroll.setBackground(cardBg(0xFFFFFFFF, 16, 0x11000000, 1));
        // Keep the log independently scrollable, while the full page itself can also scroll.
        // A fixed minimum-like height is more robust than weight=1 on short displays.
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(320));
        logLp.topMargin = dp(8);
        logLp.bottomMargin = dp(18);

        logText = new TextView(this);
        logText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        logText.setTextColor(0xFF1F2937);
        logText.setPadding(dp(12), dp(12), dp(12), dp(12));
        logText.setTextIsSelectable(true);
        logScroll.addView(logText);
        root.addView(logScroll, logLp);

        pageScroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        return pageScroll;
    }

    private LinearLayout cardLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView pill(String text, int bgColor, int textColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackground(cardBg(bgColor, 999, 0, 0));
        return tv;
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(cardBg(0xFFF59E0B, 14, 0, 0));
        return b;
    }

    private Button ghostButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(0xFF92400E);
        b.setGravity(Gravity.CENTER);
        b.setBackground(cardBg(0xFFFFFFFF, 14, 0x33F59E0B, 1));
        return b;
    }

    private Button dangerButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(0xFFB42318);
        b.setGravity(Gravity.CENTER);
        b.setBackground(cardBg(0xFFFFF7F6, 14, 0x33D92D20, 1));
        return b;
    }

    private GradientDrawable heroBg() {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF5B22, 0xFFFFA000}
        );
        gd.setCornerRadius(dp(20));
        return gd;
    }

    private GradientDrawable cardBg(int fillColor, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0 && strokeDp > 0) gd.setStroke(dp(strokeDp), strokeColor);
        return gd;
    }

    private void showTaskRuleDialog() {
        int pad = dp(14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView tip = new TextView(this);
        tip.setText("一行一个关键词，按‘包含’匹配。黑名单用于跳过任务；跳转名单用于支付宝/蚂蚁等外部任务；搜索名单用于搜一搜类任务；普通浏览名单可强制把某些任务按浏览处理。保存后从下一次‘开始执行’生效。\n\n恢复默认会重新载入推荐的默认名单。");
        tip.setTextSize(13);
        tip.setTextColor(Color.DKGRAY);
        tip.setPadding(0, 0, 0, dp(10));
        box.addView(tip);

        EditText skip = ruleEditor("例如：淘金币趣味课堂\n下单\n闲鱼", TaskRuleStore.getRawSkip(this));
        addRuleField(box, "黑名单 / 跳过任务关键词", skip);

        EditText external = ruleEditor("例如：蚂蚁森林\n蚂蚁庄园\n支付宝", TaskRuleStore.getRawExternal(this));
        addRuleField(box, "跳转任务关键词", external);

        EditText search = ruleEditor("例如：搜一搜\n搜索", TaskRuleStore.getRawSearch(this));
        addRuleField(box, "搜索任务关键词", search);

        EditText browse = ruleEditor("可留空：用于强制按普通浏览处理", TaskRuleStore.getRawBrowse(this));
        addRuleField(box, "指定普通浏览任务关键词（可选）", browse);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("任务规则设置")
                .setView(scroll)
                .setPositiveButton("保存", (d, which) -> {
                    TaskRuleStore.save(this,
                            skip.getText().toString(),
                            external.getText().toString(),
                            search.getText().toString(),
                            browse.getText().toString());
                    Toast.makeText(this, "任务规则已保存，下次开始执行时生效", Toast.LENGTH_LONG).show();
                    AppState.log("用户已保存任务规则：" + TaskRuleStore.load(this).summary());
                    refresh();
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", (d, which) -> {
                    TaskRuleStore.reset(this);
                    Toast.makeText(this, "已恢复默认规则", Toast.LENGTH_SHORT).show();
                    AppState.log("任务规则已恢复默认");
                    handler.postDelayed(this::showTaskRuleDialog, 150);
                })
                .create();
        dialog.show();
    }

    private void addRuleField(LinearLayout parent, String title, EditText editor) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(15);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(Color.BLACK);
        label.setPadding(0, dp(6), 0, dp(4));
        parent.addView(label);
        parent.addView(editor);
    }

    private EditText ruleEditor(String hint, String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(14);
        e.setTextColor(Color.BLACK);
        e.setHintTextColor(0xff888888);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setMinLines(4);
        e.setMaxLines(9);
        e.setSingleLine(false);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        e.setBackground(cardBg(0xFFF9FAFB, 12, 0x22000000, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(12);
        e.setLayoutParams(p);
        return e;
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku 服务未运行，请先在 Shizuku 中启动服务", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuShell.prepare();
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable e) {
            Toast.makeText(this, "Shizuku 不可用: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startAutomation() {
        if (!ShizukuShell.hasPermission()) {
            Toast.makeText(this, "请先启动并授权 Shizuku", Toast.LENGTH_LONG).show();
            requestShizuku();
            return;
        }
        ShizukuShell.prepare();
        if (OverlaySettings.isEnabled(this) && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限未授予；任务仍会执行，但顶部状态条不会显示", Toast.LENGTH_LONG).show();
        }
        AutomationService.start(this);
    }

    private void refresh() {
        boolean shizuku = ShizukuShell.binderAlive();
        boolean permission = ShizukuShell.hasPermission();
        boolean taobao = getPackageManager().getLaunchIntentForPackage("com.taobao.taobao") != null;
        boolean overlayPerm = Settings.canDrawOverlays(this);
        envText.setText(
                "运行架构：纯 Shizuku + OCR\n" +
                "Shizuku：" + (shizuku ? (permission ? "✓ 已授权" : "△ 已运行，未授权") : "✗ 未运行") + "\n" +
                "控制服务：" + (AutomationService.isServiceActive() ? "✓ 已启动" : "未启动") + "\n" +
                "悬浮窗：" + (OverlaySettings.isEnabled(this)
                        ? (overlayPerm ? "✓ 已启用" : "△ 已开启开关，未授权悬浮窗")
                        : "关闭") + "\n" +
                "淘宝：" + (taobao ? "✓ 已安装" : "✗ 未安装") + "\n" +
                "规则：" + TaskRuleStore.load(this).summary()
        );
        statusText.setText(AppState.displayStatus());
        refreshLogsWithoutJumping();
    }

    private void refreshLogsWithoutJumping() {
        long version = AppState.logsVersion();
        if (version == lastRenderedLogVersion || logScroll == null || logText == null) return;

        final boolean wasAtBottom = isLogNearBottom();
        final int savedY = logScroll.getScrollY();
        logText.setText(AppState.logs());
        lastRenderedLogVersion = version;

        logScroll.post(() -> {
            if (wasAtBottom) {
                logScroll.fullScroll(View.FOCUS_DOWN);
            } else {
                logScroll.scrollTo(0, savedY);
            }
        });
    }

    private boolean isLogNearBottom() {
        if (logScroll == null || logScroll.getChildCount() == 0) return true;
        View child = logScroll.getChildAt(0);
        int maxScroll = Math.max(0, child.getHeight() - logScroll.getHeight());
        return maxScroll == 0 || logScroll.getScrollY() >= maxScroll - dp(24);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

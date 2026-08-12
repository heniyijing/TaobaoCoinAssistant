package com.local.taobaocoinassistant;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class AppState {
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LOGS = new ArrayDeque<>();
    private static long LOG_VERSION = 0L;
    public static volatile boolean running = false;
    public static volatile String status = "等待开始";

    private AppState() {}

    public static void log(String text) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        synchronized (LOCK) {
            LOGS.addLast(ts + "  " + text);
            while (LOGS.size() > 1200) LOGS.removeFirst();
            LOG_VERSION++;
        }
    }

    public static long logsVersion() {
        synchronized (LOCK) { return LOG_VERSION; }
    }

    public static String displayStatus() {
        return "状态：" + status + (running ? "（运行中）" : "");
    }

    public static String logs() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (String s : LOGS) sb.append(s).append('\n');
        }
        return sb.toString();
    }
}

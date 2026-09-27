package com.local.taobaocoinassistant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行时拟人度自检。
 *
 * 记录每一次真实发出的手势（抖动幅度、震颤强度、轨迹弧度、速度剖面、注入通道、与上一次动作的间隔），
 * 跑完给出一份「这一段操作有多少机器特征」的量化评估。
 *
 * 用途是自查：哪一步还停留在匀速直线、哪一步抖动没生效、哪一段间隔过于规律，
 * 一眼看得出问题在哪，而不是靠感觉。评分只反映动作形态，不代表平台是否真的判定。
 *
 * 纯 Java，不依赖 Android API，便于单独跑统计验证。
 */
public final class MotionAudit {

    private static final int CAPACITY = 300;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Entry> LOG = new ArrayDeque<>();
    private static long lastActionAt = 0L;
    private static int total = 0;
    private static int fallback = 0;
    private static int lastReported = 0;
    /** 默认关闭：自检只是自查工具，由用户在设置里主动打开。 */
    private static volatile boolean enabled = false;

    public static final int TYPE_TAP = 0;
    public static final int TYPE_SWIPE = 1;

    private MotionAudit() {}

    public static final class Entry {
        public final int type;
        public final long intervalMs;     // 与上一次动作的间隔
        public final long durationMs;
        public final float jitterPx;
        public final float tremorPx;
        public final float bowRatio;
        public final float peakPos;
        public final boolean injected;    // true=逐点注入 false=回退 input 命令
        public final int x;
        public final int y;
        public final float downX;   // 实际按下坐标（含抖动）
        public final float downY;

        Entry(int type, long intervalMs, long durationMs, float jitterPx, float tremorPx,
              float bowRatio, float peakPos, boolean injected, int x, int y,
              float downX, float downY) {
            this.type = type;
            this.intervalMs = intervalMs;
            this.durationMs = durationMs;
            this.jitterPx = jitterPx;
            this.tremorPx = tremorPx;
            this.bowRatio = bowRatio;
            this.peakPos = peakPos;
            this.injected = injected;
            this.x = x;
            this.y = y;
            this.downX = downX;
            this.downY = downY;
        }
    }

    public static final class Score {
        public final int score;       // 0~100，越高越像人手
        public final String level;    // 低 / 中 / 高
        public final List<String> findings;
        public final int samples;
        public int totalActions;
        public int fallbackActions;

        Score(int score, String level, List<String> findings, int samples) {
            this.score = score;
            this.level = level;
            this.findings = findings;
            this.samples = samples;
        }

        public boolean lowRisk() { return score >= 80; }
    }

    public static void setEnabled(boolean on) {
        synchronized (LOCK) {
            enabled = on;
            if (!on) {
                LOG.clear();
                lastActionAt = 0L;
                total = 0;
                fallback = 0;
                lastReported = 0;
            }
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 距上次出简报是否已满 25 次动作（用累计计数，不用会被环形缓冲截断的样本数）。 */
    public static boolean shouldReport() {
        synchronized (LOCK) { return enabled && total - lastReported >= 25; }
    }

    public static void markReported() {
        synchronized (LOCK) { lastReported = total; }
    }

    public static void reset() {
        synchronized (LOCK) {
            LOG.clear();
            lastActionAt = 0L;
            total = 0;
            fallback = 0;
            lastReported = 0;
        }
    }

    /** 取与上一次动作的间隔，并更新计时基准。 */
    public static long beginAction() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long gap = lastActionAt == 0L ? -1L : now - lastActionAt;
            lastActionAt = now;
            return gap;
        }
    }

    public static void recordTap(long intervalMs, int x, int y, boolean injected,
                                 HumanMotion.GestureStats st) {
        if (!enabled) return;
        float dx = st == null ? x : st.downX;
        float dy = st == null ? y : st.downY;
        add(new Entry(TYPE_TAP, intervalMs,
                st == null ? 0L : st.durationMs,
                st == null ? 0f : st.jitterPx, 0f, 0f, 0f, injected, x, y, dx, dy));
    }

    public static void recordSwipe(long intervalMs, int x, int y, boolean injected,
                                   HumanMotion.GestureStats st) {
        if (!enabled) return;
        float dx = st == null ? x : st.downX;
        float dy = st == null ? y : st.downY;
        add(new Entry(TYPE_SWIPE, intervalMs,
                st == null ? 0L : st.durationMs,
                st == null ? 0f : st.jitterPx,
                st == null ? 0f : st.tremorPx,
                st == null ? 0f : st.bowRatio,
                st == null ? 0f : st.peakPos, injected, x, y, dx, dy));
    }

    private static void add(Entry e) {
        synchronized (LOCK) {
            total++;
            if (!e.injected) fallback++;
            LOG.addLast(e);
            while (LOG.size() > CAPACITY) LOG.removeFirst();
        }
    }

    public static int sampleCount() {
        synchronized (LOCK) { return LOG.size(); }
    }

    public static Score evaluate() {
        List<Entry> snapshot;
        int totalCount;
        int fallbackCount;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(LOG);
            totalCount = total;
            fallbackCount = fallback;
        }
        List<String> findings = new ArrayList<>();
        if (snapshot.size() < 5) {
            findings.add(String.format("样本不足（%d 次动作，至少需要 5 次），无法评估", snapshot.size()));
            Score low = new Score(-1, "未知", findings, snapshot.size());
            low.totalActions = totalCount;
            low.fallbackActions = fallbackCount;
            return low;
        }

        int n = snapshot.size();
        double penalty = 0;

        // 1) 注入通道：回退到 input 命令意味着线性匀速轨迹，是强机器特征
        int noInject = 0;
        for (Entry e : snapshot) if (!e.injected) noInject++;
        double fbRatio = (double) noInject / n;
        if (fbRatio > 0) {
            penalty += 45 * fbRatio;
            findings.add(String.format("有 %.0f%% 的动作回退到 input 命令（匀速直线，最强机器特征）", fbRatio * 100));
        }

        // 2) 动作间隔的规律性：人手 CV 通常在 0.3 以上
        double[] gaps = new double[n];
        int g = 0;
        for (Entry e : snapshot) if (e.intervalMs >= 0) gaps[g++] = e.intervalMs;
        if (g >= 3) {
            double mean = 0;
            for (int i = 0; i < g; i++) mean += gaps[i];
            mean /= g;
            double var = 0;
            for (int i = 0; i < g; i++) var += (gaps[i] - mean) * (gaps[i] - mean);
            var /= g;
            double cv = mean > 0 ? Math.sqrt(var) / mean : 0;
            if (cv < 0.12) {
                penalty += 25;
                findings.add(String.format("动作间隔过于规律（变异系数 %.2f，均值 %.0fms）", cv, mean));
            } else if (cv < 0.25) {
                penalty += 12;
                findings.add(String.format("动作间隔偏规律（变异系数 %.2f）", cv));
            } else if (cv < 0.40) {
                penalty += 5;
                findings.add(String.format("动作间隔略规律（变异系数 %.2f）", cv));
            }
        }

        // 3) 点击抖动 + 落点重复
        int taps = 0;
        double jitterSum = 0;
        int repeat = 0;
        Entry prevTap = null;
        for (Entry e : snapshot) {
            if (e.type != TYPE_TAP) continue;
            taps++;
            jitterSum += e.jitterPx;
            if (prevTap != null
                    && Math.abs(e.downX - prevTap.downX) <= 1f
                    && Math.abs(e.downY - prevTap.downY) <= 1f) repeat++;
            prevTap = e;
        }
        if (taps >= 3) {
            double meanJitter = jitterSum / taps;
            double repRatio = (double) repeat / Math.max(1, taps - 1);
            if (meanJitter < 1.2) {
                penalty += 15;
                findings.add(String.format("点击抖动偏小（均值 %.2fpx）", meanJitter));
            } else if (meanJitter < 2.0) {
                penalty += 7;
                findings.add(String.format("点击抖动略小（均值 %.2fpx）", meanJitter));
            }
            if (repRatio > 0.3) {
                penalty += 20 * repRatio;
                findings.add(String.format("有 %.0f%% 的点击落在完全相同的像素上", repRatio * 100));
            }
        }

        // 4) 滑动形态：震颤、弧度、速度剖面
        int swipes = 0, weakTremor = 0, flat = 0, oddPeak = 0;
        double tremorSum = 0, bowSum = 0;
        for (Entry e : snapshot) {
            if (e.type != TYPE_SWIPE) continue;
            swipes++;
            tremorSum += e.tremorPx;
            bowSum += e.bowRatio;
            if (e.tremorPx < 0.35f) weakTremor++;
            if (e.bowRatio < 0.004f) flat++;
            if (e.peakPos < 0.2f || e.peakPos > 0.8f) oddPeak++;
        }
        if (swipes >= 3) {
            double weakRatio = (double) weakTremor / swipes;
            double flatRatio = (double) flat / swipes;
            double oddRatio = (double) oddPeak / swipes;
            if (weakRatio > 0.3) {
                penalty += 15 * weakRatio;
                findings.add(String.format("%.0f%% 的滑动缺少高频震颤（均值 %.2fpx）", weakRatio * 100, tremorSum / swipes));
            }
            if (flatRatio > 0.3) {
                penalty += 15 * flatRatio;
                findings.add(String.format("%.0f%% 的滑动接近直线（弧度均值 %.2f%%）", flatRatio * 100, bowSum / swipes * 100));
            }
            if (oddRatio > 0.3) {
                penalty += 10 * oddRatio;
                findings.add(String.format("%.0f%% 的滑动速度峰值不在行程中段（不像加速-减速）", oddRatio * 100));
            }
        }

        // 5) 操作密度
        int rush = 0;
        for (Entry e : snapshot) if (e.intervalMs >= 0 && e.intervalMs < 300) rush++;
        double rushRatio = (double) rush / n;
        if (rushRatio > 0.25) {
            penalty += 10;
            findings.add(String.format("%.0f%% 的动作间隔短于 300ms，节奏过密", rushRatio * 100));
        }

        int score = (int) Math.max(0, Math.min(100, Math.round(100 - penalty)));
        String level = score >= 80 ? "低" : (score >= 60 ? "中" : "高");
        if (findings.isEmpty()) findings.add("未发现明显机器特征");
        Score s = new Score(score, level, findings, n);
        s.totalActions = totalCount;
        s.fallbackActions = fallbackCount;
        return s;
    }

    /** 供 UI / 日志使用的一行摘要。 */
    public static String summary(Score s) {
        if (s.score < 0) {
            return String.format("拟人度自检：样本不足（%d 次动作，至少需 5 次）", s.samples);
        }
        return String.format("拟人度 %d/100（机器特征%s）· 样本 %d 次", s.score, s.level, s.samples);
    }

    /** 完整报告，多行，直接进日志。 */
    public static String report() {
        Score s = evaluate();
        StringBuilder sb = new StringBuilder();
        sb.append("===== 拟人度自检 =====\n");
        sb.append(summary(s)).append("\n");
        sb.append(String.format("累计动作 %d 次，其中降级到 input 命令 %d 次\n",
                s.totalActions, s.fallbackActions));
        for (String f : s.findings) sb.append(" - ").append(f).append("\n");
        sb.append("说明：评分只衡量动作形态（抖动/震颤/弧度/节奏），不代表平台实际判定结果。");
        return sb.toString();
    }
}

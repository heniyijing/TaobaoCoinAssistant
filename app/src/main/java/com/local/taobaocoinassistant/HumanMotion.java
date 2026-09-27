package com.local.taobaocoinassistant;

import java.util.Locale;
import java.util.Random;

/**
 * 生理级轨迹仿真：贝塞尔路径 + 最小 jerk 速度剖面 + 8~12Hz 震颤 + Fitts 时长模型。
 *
 * 本类只做「采样」，不依赖任何 Android API，因此既能在 App 进程里跑，
 * 也能在 Shizuku 的 shell 侧 CoinUserService 里跑（逐点注入必须放在 shell 侧，
 * 否则 binder 往返抖动会把轨迹节拍打散）。
 *
 * 时间单位统一毫秒，坐标单位统一像素。
 */
public final class HumanMotion {

    /** 采样间隔。8ms 才能在 12Hz 震颤下不混叠（12Hz 需要 <= 41ms，留足余量）。 */
    private static final int SAMPLE_MS = 8;

    /** 点击落点高斯抖动标准差（像素）。人类指尖点击误差实测约 2~4px。 */
    private static final float TAP_SIGMA = 2.2f;
    private static final float TAP_CLAMP = 5.0f;

    /** 慢漂移偏差：人的点击误差不是白噪声，而是带一个缓慢游走的系统偏置。 */
    private static volatile float biasX = 0f;
    private static volatile float biasY = 0f;
    private static volatile long biasUpdatedAt = 0L;

    private HumanMotion() {}

    public static final class Point {
        public final float x;
        public final float y;
        public final float pressure;
        public final float size;
        public final long tMs;

        Point(float x, float y, float pressure, float size, long tMs) {
            this.x = x;
            this.y = y;
            this.pressure = pressure;
            this.size = size;
            this.tMs = tMs;
        }
    }

    // ------------------------------------------------------------------
    // 采样工具
    // ------------------------------------------------------------------

    private static float gaussian(Random rnd) {
        double u1 = Math.max(1e-12, rnd.nextDouble());
        double u2 = rnd.nextDouble();
        return (float) (Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 更新慢漂移偏置：每次调用做一次小随机游走，幅度限制在 ±1.8px。 */
    private static void driftBias(Random rnd, long now) {
        if (now - biasUpdatedAt < 4000L) return;
        biasUpdatedAt = now;
        biasX = clamp(biasX + 0.28f * gaussian(rnd), -1.8f, 1.8f);
        biasY = clamp(biasY + 0.28f * gaussian(rnd), -1.8f, 1.8f);
    }

    /**
     * 非均匀等待：对数正态主体 + 5% 重尾（走神）。
     * 用来替换所有 sleep(固定值)，避免「每两次动作间隔完全一样」这种机器特征。
     */
    public static long delayMs(long base, Random rnd) {
        if (base <= 0) return 0;
        double f = Math.exp(0.30d * gaussian(rnd));
        if (rnd.nextDouble() < 0.04d) f *= 1.4d + rnd.nextDouble() * 1.2d;
        long ms = Math.round(base * f);
        long lo = (long) (base * 0.55d);
        long hi = (long) (base * 2.4d);
        return Math.max(lo, Math.min(hi, ms));
    }

    /** 点击前后的微停顿：韦布尔（k<1 右偏），比均匀分布更像人手。 */
    public static long microDelayMs(long base, Random rnd) {
        double u = Math.max(1e-9, rnd.nextDouble());
        double w = Math.pow(-Math.log(u), 1.0d / 0.85d);
        long ms = Math.round(base * 0.55d * w);
        return Math.max((long) (base * 0.35d), Math.min((long) (base * 2.4d), ms));
    }

    /** 最小 jerk 归一化位移：s(0)=0, s(1)=1，首尾速度与加速度均为 0，jerk 有界。 */
    private static float minJerk(double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return (float) (10 * t3 - 15 * t3 * t + 6 * t3 * t2);
    }

    /** 时间重参数化：gamma<1 前段快（甩动），gamma>1 后段快（精确调整）。 */
    private static float profile(double t, double gamma) {
        return minJerk(Math.pow(clamp((float) t, 0f, 1f), gamma));
    }

    /** 三次贝塞尔取点。 */
    private static void bezier(float p0x, float p0y, float c1x, float c1y,
                               float c2x, float c2y, float p3x, float p3y,
                               float s, float[] out) {
        float u = 1f - s;
        float a = u * u * u;
        float b = 3f * u * u * s;
        float c = 3f * u * s * s;
        float d = s * s * s;
        out[0] = a * p0x + b * c1x + c * c2x + d * p3x;
        out[1] = a * p0y + b * c1y + c * c2y + d * p3y;
    }

    /** Fitts 时长模型：T = a + b * log2(D/W + 1)，输出毫秒并夹紧到人手合理区间。 */
    private static long fittsDurationMs(float dx, float dy, float targetWidth, Random rnd) {
        double d = Math.hypot(dx, dy);
        double w = Math.max(24f, targetWidth);
        double bits = Math.log(d / w + 1.0d) / Math.log(2.0d);
        double seconds = 0.10d + 0.18d * bits;
        seconds *= 0.92d + 0.16d * rnd.nextDouble();
        long ms = Math.round(seconds * 1000d);
        return Math.max(320L, Math.min(900L, ms));
    }

    // ------------------------------------------------------------------
    // 点击：落点抖动 + 按压期间的亚像素游走
    // ------------------------------------------------------------------

    /**
     * 生成一次点击的轨迹点。
     *
     * @param cx,cy   目标中心（OCR 得到的按钮中心）
     * @param halfW,halfH 目标可容错的半宽/半高，用来把抖动限制在按钮内
     */
    public static Point[] buildTap(float cx, float cy, float halfW, float halfH, Random rnd) {
        driftBias(rnd, System.currentTimeMillis());

        float sig = TAP_SIGMA;
        float limX = Math.min(TAP_CLAMP, Math.max(1.0f, halfW * 0.42f));
        float limY = Math.min(TAP_CLAMP, Math.max(1.0f, halfH * 0.42f));
        float dx = clamp(gaussian(rnd) * sig + biasX, -limX, limX);
        float dy = clamp(gaussian(rnd) * sig + biasY, -limY, limY);

        float x = cx + dx;
        float y = cy + dy;

        // 按压时长 55~110ms，期间手指会有亚像素级游走。
        long hold = 55L + (long) (rnd.nextDouble() * 55d);
        int moveCount = 1 + rnd.nextInt(2);
        float basePressure = 0.82f + rnd.nextFloat() * 0.16f;

        Point[] pts = new Point[2 + moveCount];
        pts[0] = new Point(x, y, basePressure, 0.52f, 0L);
        for (int i = 1; i <= moveCount; i++) {
            long t = hold * i / (moveCount + 1);
            // 按压力度先升后降，位移只有零点几像素。
            float p = basePressure + (i == 1 ? 0.06f : -0.05f) + (rnd.nextFloat() - 0.5f) * 0.04f;
            pts[i] = new Point(x + (rnd.nextFloat() - 0.5f) * 0.8f,
                    y + (rnd.nextFloat() - 0.5f) * 0.8f,
                    clamp(p, 0.55f, 1.0f), 0.52f, t);
        }
        pts[pts.length - 1] = new Point(x + (rnd.nextFloat() - 0.5f) * 0.6f,
                y + (rnd.nextFloat() - 0.5f) * 0.6f,
                clamp(basePressure - 0.08f, 0.4f, 1.0f), 0.5f, hold);
        return pts;
    }

    // ------------------------------------------------------------------
    // 滑动：贝塞尔弧线 + Fitts 时长 + 震颤
    // ------------------------------------------------------------------

    public static final int PROFILE_FITTS = 0;
    public static final int PROFILE_FLING = 1;

    /**
     * 生成一次滑动的轨迹点（含 DOWN / MOVE... / UP 前的最后一个点）。
     *
     * @param targetWidth 目标宽度（滚动时可传屏幕宽的 0.6，按钮拖动时传按钮宽）
     * @param profile     PROFILE_FITTS 精确定向移动；PROFILE_FLING 甩动（前段快）
     */
    public static Point[] buildSwipe(float x1, float y1, float x2, float y2,
                                     float targetWidth, int profile, Random rnd) {
        return buildSwipe(x1, y1, x2, y2, targetWidth, profile, rnd, 0L);
    }

    /**
     * @param forcedDurationMs 大于 0 时用指定时长（慢速浏览用），不再走 Fitts 模型
     */
    public static Point[] buildSwipe(float x1, float y1, float x2, float y2,
                                     float targetWidth, int profile, Random rnd,
                                     long forcedDurationMs) {
        driftBias(rnd, System.currentTimeMillis());

        float dx = x2 - x1;
        float dy = y2 - y1;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < 1f) return buildTap(x2, y2, Math.max(12f, targetWidth * 0.5f), 12f, rnd);

        // 起手点本身就有误差
        float sx = x1 + clamp(gaussian(rnd) * 1.6f + biasX, -4f, 4f);
        float sy = y1 + clamp(gaussian(rnd) * 1.6f + biasY, -4f, 4f);

        // 垂直方向上的弧度：人的滑动不会是直线
        float nx = dist == 0 ? 0 : -dy / dist;
        float ny = dist == 0 ? 0 : dx / dist;
        // 垂直方向上的弧度：人的滑动不会是直线。范围放宽到 1%~12%，
        // 另有约 1/10 的概率几乎是直线（人也会随手一划）。
        float bowScale = 0.010f + rnd.nextFloat() * 0.11f;
        if (rnd.nextFloat() < 0.10f) bowScale *= 0.18f;
        float bow = dist * bowScale;
        if (rnd.nextBoolean()) bow = -bow;

        float c1x = sx + dx * 0.28f + nx * bow;
        float c1y = sy + dy * 0.28f + ny * bow;
        // 第二个控制点略微越过终点方向，制造末段的轻微过冲回拉
        float over = dist * (0.02f + rnd.nextFloat() * 0.05f);
        float c2x = x2 - dx * 0.16f + nx * bow * 0.45f + (dx / dist) * over;
        float c2y = y2 - dy * 0.16f + ny * bow * 0.45f + (dy / dist) * over;

        long total = fittsDurationMs(dx, dy, targetWidth, rnd);
        if (profile == PROFILE_FLING) total = (long) (total * (0.74d + 0.16d * rnd.nextDouble()));
        // 慢速浏览：指定时长优先（手指慢慢划过屏幕，读内容的样子）
        if (forcedDurationMs > 0L) total = forcedDurationMs;
        total = Math.max(160L, total);

        // 震颤参数：8~12Hz 主频 + 二次谐波，双轴相位随机
        float f1 = 8f + rnd.nextFloat() * 4f;
        float f2 = f1 * (1.7f + rnd.nextFloat() * 0.6f);
        float a1 = 0.55f + rnd.nextFloat() * 0.85f;
        float a2 = 0.20f + rnd.nextFloat() * 0.35f;
        float ph1 = rnd.nextFloat() * 6.283f;
        float ph2 = rnd.nextFloat() * 6.283f;
        float ph3 = rnd.nextFloat() * 6.283f;
        float ph4 = rnd.nextFloat() * 6.283f;

        double gamma = profile == PROFILE_FLING ? 0.88d + rnd.nextDouble() * 0.14d
                : 1.0d + rnd.nextDouble() * 0.22d;

        int n = Math.max(16, (int) (total / SAMPLE_MS));
        Point[] pts = new Point[n + 1];
        float[] tmp = new float[2];

        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            float s = profile(t, gamma);
            bezier(sx, sy, c1x, c1y, c2x, c2y, x2, y2, s, tmp);

            float sec = (float) (total * t) / 1000f;
            float tremorX = a1 * (float) Math.sin(2 * Math.PI * f1 * sec + ph1)
                    + a2 * (float) Math.sin(2 * Math.PI * f2 * sec + ph2);
            float tremorY = a1 * (float) Math.sin(2 * Math.PI * f1 * sec + ph3)
                    + a2 * (float) Math.sin(2 * Math.PI * f2 * sec + ph4);

            // 压力：起落轻、中段重，叠加测量噪声
            float bell = (float) Math.sin(Math.PI * s);
            float pressure = clamp(0.68f + 0.30f * bell + (rnd.nextFloat() - 0.5f) * 0.05f, 0.35f, 1.0f);

            pts[i] = new Point(tmp[0] + tremorX, tmp[1] + tremorY, pressure,
                    0.45f + 0.12f * bell, Math.round(total * t));
        }
        return pts;
    }

    /** 终点是否需要一个抬手前的静止段（手指松开前会短暂贴住屏幕）。 */
    public static long releaseDelayMs(Random rnd) {
        return 18L + (long) (rnd.nextDouble() * 42d);
    }

    // ------------------------------------------------------------------
    // 手势统计：给上层的拟人度自检提供客观指标
    // ------------------------------------------------------------------

    public static final class GestureStats {
        public final float jitterPx;    // 落点/起手点相对目标中心的偏移
        public final float tremorPx;    // 高频分量（震颤）强度，二阶差分均值
        public final float bowRatio;    // 轨迹最大偏离弦长 / 弦长
        public final float peakPos;     // 速度峰值所在的归一化位置 0~1
        public final long durationMs;
        public final int points;
        /** 实际按下坐标（含抖动），自检判断“是否每次都点在同一像素”要用它，不能用按钮中心。 */
        public final float downX;
        public final float downY;

        GestureStats(float jitterPx, float tremorPx, float bowRatio, float peakPos,
                     long durationMs, int points, float downX, float downY) {
            this.jitterPx = jitterPx;
            this.tremorPx = tremorPx;
            this.bowRatio = bowRatio;
            this.peakPos = peakPos;
            this.durationMs = durationMs;
            this.points = points;
            this.downX = downX;
            this.downY = downY;
        }

        public String encode() {
            return String.format(Locale.US, "%.2f,%.3f,%.5f,%.3f,%d,%d,%.2f,%.2f",
                    jitterPx, tremorPx, bowRatio, peakPos, durationMs, points, downX, downY);
        }

        public static GestureStats decode(String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split(",");
            if (p.length < 8) return null;
            try {
                return new GestureStats(Float.parseFloat(p[0]), Float.parseFloat(p[1]),
                        Float.parseFloat(p[2]), Float.parseFloat(p[3]),
                        Long.parseLong(p[4]), Integer.parseInt(p[5]),
                        Float.parseFloat(p[6]), Float.parseFloat(p[7]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * 对一条轨迹做客观测量。
     *
     * @param targetX,targetY 点击时传按钮中心；滑动时传起点
     */
    public static GestureStats stats(Point[] pts, float targetX, float targetY, boolean isTap) {
        if (pts == null || pts.length == 0) return null;
        float jitter = (float) Math.hypot(pts[0].x - targetX, pts[0].y - targetY);
        float downX = pts[0].x;
        float downY = pts[0].y;
        long dur = pts[pts.length - 1].tMs;
        if (isTap) {
            return new GestureStats(jitter, 0f, 0f, 0f, dur, pts.length, downX, downY);
        }

        // 震颤：点相对"跨 ±W 点基线"的偏离，衡量高频抖动。
        // 不用二阶差分也不用相邻点残差——它们的响应系数太小（8ms 采样、10Hz 震颤时只有 0.12），
        // 数值会被真实加速度主导，导致 1~2 秒的慢速滑动被误判成"没有震颤"。
        // 跨 ±3 点（48ms）时对 8~12Hz 的响应约 0.94，与滑动速度基本无关。
        int window = Math.min(3, Math.max(1, (pts.length - 1) / 4));
        double devSum = 0;
        int devCount = 0;
        for (int i = window; i < pts.length - window; i++) {
            double mx = (pts[i - window].x + pts[i + window].x) * 0.5d;
            double my = (pts[i - window].y + pts[i + window].y) * 0.5d;
            devSum += Math.hypot(pts[i].x - mx, pts[i].y - my);
            devCount++;
        }
        float tremor = devCount > 0 ? (float) (devSum / devCount) : 0f;

        float x1 = pts[0].x;
        float y1 = pts[0].y;
        Point last = pts[pts.length - 1];
        double chord = Math.hypot(last.x - x1, last.y - y1);
        float bow = 0f;
        if (chord > 1.0) {
            double maxDev = 0;
            for (Point p : pts) {
                double d = Math.abs((last.x - x1) * (y1 - p.y) - (last.y - y1) * (x1 - p.x)) / chord;
                if (d > maxDev) maxDev = d;
            }
            bow = (float) (maxDev / chord);
        }

        int peak = 0;
        double maxV = -1;
        for (int i = 1; i < pts.length; i++) {
            double dt = Math.max(1, pts[i].tMs - pts[i - 1].tMs);
            double d = Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
            double v = d / dt * 1000.0;
            if (v > maxV) {
                maxV = v;
                peak = i;
            }
        }
        float peakPos = pts.length > 1 ? (float) peak / (pts.length - 1) : 0f;
        return new GestureStats(jitter, tremor, bow, peakPos, dur, pts.length, downX, downY);
    }
}

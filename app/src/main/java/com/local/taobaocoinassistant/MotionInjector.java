package com.local.taobaocoinassistant;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 逐点注入器。运行在 Shizuku 的 shell 侧（CoinUserService 所在进程，UID 2000）。
 *
 * 通过反射拿 android.hardware.input.InputManager 并调用 injectInputEvent，
 * 把 HumanMotion 采样出的每一个点作为独立事件发出去，而不是交给
 * `input swipe`（后者只能做线性插值，速度恒定，端点无减速，轨迹是完美直线）。
 *
 * 任何一步失败都会返回 false，调用方回退到 `input tap / input swipe`。
 */
public final class MotionInjector {

    private static final int MODE_ASYNC = 0;

    private static volatile int state = 0; // 0 未初始化 1 可用 -1 不可用
    private static volatile Object inputManager;
    private static volatile Method injectMethod;
    private static volatile String lastError = "";

    private MotionInjector() {}

    public static synchronized boolean available() {
        if (state == 0) init();
        return state == 1;
    }

    public static String lastError() {
        return lastError;
    }

    private static void init() {
        exemptHiddenApi();
        try {
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = imClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object im = getInstance.invoke(null);
            Method inject = imClass.getDeclaredMethod("injectInputEvent",
                    android.view.InputEvent.class, int.class);
            inject.setAccessible(true);
            inputManager = im;
            injectMethod = inject;
            state = 1;
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            state = -1;
        }
    }

    /**
     * 注入一条轨迹。points[0] 是按下点，最后一个点是抬起点之前的终点。
     * 事件时间戳用 uptimeMillis 逐点推进，节拍由本进程自己 sleep 对齐。
     *
     * 不变量：只要 DOWN 注入成功，无论后续抛什么异常，finally 里都会补发一次 UP。
     * 否则触摸指针会悬挂在按下状态，InputDispatcher 在超时前会吞掉后续手势。
     */
    public static boolean inject(HumanMotion.Point[] points, boolean isTap) {
        if (points == null || points.length == 0) return false;
        if (!available()) return false;

        long downTime = 0L;
        boolean downSent = false;
        boolean upSent = false;
        try {
            downTime = SystemClock.uptimeMillis();
            long start = downTime;

            HumanMotion.Point p0 = points[0];
            send(MotionEvent.ACTION_DOWN, downTime, downTime, p0.x, p0.y, p0.pressure, p0.size);
            downSent = true;

            for (int i = 1; i < points.length; i++) {
                HumanMotion.Point p = points[i];
                long target = start + p.tMs;
                long wait = target - SystemClock.uptimeMillis();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                send(MotionEvent.ACTION_MOVE, downTime, Math.max(target, SystemClock.uptimeMillis()),
                        p.x, p.y, p.pressure, p.size);
            }

            HumanMotion.Point last = points[points.length - 1];
            long upAt = Math.max(start + last.tMs + (isTap ? 0 : 12), SystemClock.uptimeMillis());
            send(MotionEvent.ACTION_UP, downTime, upAt, last.x, last.y, last.pressure * 0.85f, last.size);
            upSent = true;
            return true;
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        } finally {
            if (downSent && !upSent) {
                try {
                    HumanMotion.Point last = points[points.length - 1];
                    send(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(),
                            last.x, last.y, Math.max(0.4f, last.pressure * 0.85f), last.size);
                } catch (Throwable ignored) {
                    // 补救失败只能放弃；调用方会回退 input 命令，指针最终会被系统超时回收。
                }
            }
        }
    }

    /**
     * 解除 hidden API 拦截。三条路都试，全失败也只是退回 input 命令：
     * 1) shell 进程本身通常已被 zygote 豁免，什么都不做也能反射成功；
     * 2) dalvik VMRuntime.setHiddenApiExemptions；
     * 3) LSPosed HiddenApiBypass（可选依赖，不存在就跳过）。
     */
    private static void exemptHiddenApi() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Method get = vm.getDeclaredMethod("getRuntime");
            Method set = vm.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            set.invoke(get.invoke(null), (Object) new String[]{"L"});
        } catch (Throwable ignored) {}

        try {
            Class<?> bypass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
            Method add = bypass.getDeclaredMethod("addHiddenApiExemptions", String[].class);
            add.invoke(null, (Object) new String[]{"L"});
        } catch (Throwable ignored) {}
    }

    private static void send(int action, long downTime, long eventTime,
                             float x, float y, float pressure, float size) throws Exception {
        MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action,
                x, y, pressure, Math.max(0.1f, size), 0, 1.0f, 1.0f, 0, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            injectMethod.invoke(inputManager, ev, MODE_ASYNC);
        } finally {
            ev.recycle();
        }
    }
}

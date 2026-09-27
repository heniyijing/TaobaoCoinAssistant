package com.local.taobaocoinassistant;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import rikka.shizuku.Shizuku;

/**
 * Small synchronous client for CoinUserService.
 *
 * Shizuku.newProcess() is no longer a public API in current Shizuku API.
 * Commands are therefore executed inside a Shizuku UserService instead.
 */
public final class ShizukuShell {
    private static final Object LOCK = new Object();
    private static volatile IBinder userServiceBinder;
    private static volatile boolean binding;

    private static final Shizuku.UserServiceArgs USER_SERVICE_ARGS =
            new Shizuku.UserServiceArgs(new ComponentName(
                    "com.local.taobaocoinassistant",
                    CoinUserService.class.getName()))
                    .daemon(true)
                    .processNameSuffix("coin_shell")
                    .debuggable(false)
                    .version(3);

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LOCK) {
                userServiceBinder = service;
                binding = false;
                LOCK.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                userServiceBinder = null;
                binding = false;
                LOCK.notifyAll();
            }
        }
    };

    private ShizukuShell() {}

    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return binderAlive()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Begin binding the privileged UserService without blocking. */
    public static void prepare() {
        if (!hasPermission()) return;
        IBinder current = userServiceBinder;
        if (current != null && current.pingBinder()) return;

        synchronized (LOCK) {
            current = userServiceBinder;
            if (current != null && current.pingBinder()) return;
            if (binding) return;
            binding = true;
            try {
                Shizuku.bindUserService(USER_SERVICE_ARGS, CONNECTION);
            } catch (Throwable e) {
                binding = false;
                LOCK.notifyAll();
            }
        }
    }

    private static IBinder awaitService(long timeoutMs) {
        prepare();
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (LOCK) {
            while (true) {
                IBinder current = userServiceBinder;
                if (current != null && current.pingBinder()) return current;
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) return null;
                try {
                    LOCK.wait(Math.min(remain, 250));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /** Stream one real device screenshot from the Shizuku shell process. */
    public static ParcelFileDescriptor openScreenshot() {
        if (!hasPermission()) return null;

        IBinder binder = awaitService(6000);
        if (binder == null) return null;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            boolean sent = binder.transact(CoinUserService.TRANSACTION_SCREENSHOT, data, reply, 0);
            if (!sent) return null;
            reply.readException();
            int present = reply.readInt();
            if (present == 0) {
                // Optional error string written by the service. Consume it if present.
                try { reply.readString(); } catch (Throwable ignored) {}
                return null;
            }
            return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
        } catch (Throwable e) {
            synchronized (LOCK) {
                userServiceBinder = null;
                binding = false;
            }
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static Result exec(String command) {
        if (!hasPermission()) return new Result(-1, "Shizuku 未授权或服务未运行");

        IBinder binder = awaitService(6000);
        if (binder == null) return new Result(-1, "Shizuku UserService 连接超时");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeString(command);
            boolean sent = binder.transact(CoinUserService.TRANSACTION_EXEC, data, reply, 0);
            if (!sent) return new Result(-1, "Shizuku UserService transact 失败");
            reply.readException();
            int code = reply.readInt();
            String output = reply.readString();
            return new Result(code, output);
        } catch (Throwable e) {
            synchronized (LOCK) {
                userServiceBinder = null;
                binding = false;
            }
            return new Result(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static final class GestureResult {
        public final boolean ok;
        public final HumanMotion.GestureStats stats;
        public final String detail;

        GestureResult(boolean ok, HumanMotion.GestureStats stats, String detail) {
            this.ok = ok;
            this.stats = stats;
            this.detail = detail == null ? "" : detail;
        }
    }

    /**
     * 逐点注入一次手势。ok=false 表示调用方需要回退到 input 命令；stats 是 shell 侧实测的轨迹指标。
     */
    public static GestureResult injectGesture(int type, float x1, float y1, float x2, float y2,
                                              float halfW, float halfH, long seed) {
        if (!hasPermission()) return new GestureResult(false, null, "no permission");
        IBinder binder = awaitService(6000);
        if (binder == null) return new GestureResult(false, null, "service timeout");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(type);
            data.writeFloat(x1);
            data.writeFloat(y1);
            data.writeFloat(x2);
            data.writeFloat(y2);
            data.writeFloat(halfW);
            data.writeFloat(halfH);
            data.writeLong(seed);
            if (!binder.transact(CoinUserService.TRANSACTION_INJECT, data, reply, 0)) {
                return new GestureResult(false, null, "transact failed");
            }
            reply.readException();
            boolean ok = reply.readInt() == 1;
            String detail = reply.readString();
            String encoded = reply.readString();
            return new GestureResult(ok, HumanMotion.GestureStats.decode(encoded), detail);
        } catch (Throwable e) {
            // 与 exec() 保持一致：transact 抛异常大概率意味着 binder 已死，清缓存让下次重连。
            synchronized (LOCK) {
                userServiceBinder = null;
                binding = false;
            }
            return new GestureResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static final class Result {
        public final int code;
        public final String output;

        public Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            return code == 0;
        }
    }
}

package com.local.taobaocoinassistant;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Shizuku UserService running as shell (UID 2000 on non-root Shizuku).
 *
 * v2.0 deliberately exposes only two privileged primitives:
 * 1) execute a shell command and return text output;
 * 2) stream a real `screencap -p` PNG through a pipe.
 *
 * Screenshot bytes are never placed in a Binder String/byte[] transaction because a full phone
 * screenshot can exceed Binder's transaction limit. ParcelFileDescriptor keeps the transfer
 * streaming and avoids temporary files in shared storage.
 */
public final class CoinUserService extends Binder {
    public static final int TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION;
    public static final int TRANSACTION_SCREENSHOT = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int TRANSACTION_INJECT = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int TRANSACTION_DESTROY = 16777115;

    public static final int GESTURE_TAP = 0;
    public static final int GESTURE_SWIPE_FITTS = 1;
    public static final int GESTURE_SWIPE_FLING = 2;

    public CoinUserService() {}

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == TRANSACTION_EXEC) {
            String command = data.readString();
            CommandResult result = runCommand(command == null ? "" : command);
            reply.writeNoException();
            reply.writeInt(result.code);
            reply.writeString(result.output);
            return true;
        }

        if (code == TRANSACTION_SCREENSHOT) {
            final ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createPipe();
            } catch (Throwable e) {
                reply.writeNoException();
                reply.writeInt(0);
                reply.writeString(e.getClass().getSimpleName() + ": " + e.getMessage());
                return true;
            }

            final ParcelFileDescriptor readSide = pipe[0];
            final ParcelFileDescriptor writeSide = pipe[1];
            reply.writeNoException();
            reply.writeInt(1);
            // PARCELABLE_WRITE_RETURN_VALUE transfers a dup of the fd to the client and closes
            // this process' read-side after writing it into the Binder reply.
            readSide.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);

            Thread writer = new Thread(() -> streamScreenshot(writeSide), "coin-screencap");
            writer.setDaemon(true);
            try {
                writer.start();
            } catch (Throwable ignored) {
                try { writeSide.close(); } catch (Throwable ignoredClose) {}
            }
            return true;
        }

        if (code == TRANSACTION_INJECT) {
            int type = data.readInt();
            float x1 = data.readFloat();
            float y1 = data.readFloat();
            float x2 = data.readFloat();
            float y2 = data.readFloat();
            float halfW = data.readFloat();
            float halfH = data.readFloat();
            long seed = data.readLong();

            String detail;
            String statsEncoded = "";
            boolean ok;
            try {
                HumanMotion.Point[] points;
                boolean isTap = type == GESTURE_TAP;
                if (isTap) {
                    points = HumanMotion.buildTap(x1, y1, halfW, halfH, new Random(seed));
                } else {
                    points = HumanMotion.buildSwipe(x1, y1, x2, y2, Math.max(24f, halfW * 2f),
                            type == GESTURE_SWIPE_FLING
                                    ? HumanMotion.PROFILE_FLING : HumanMotion.PROFILE_FITTS,
                            new Random(seed));
                }
                HumanMotion.GestureStats st =
                        HumanMotion.stats(points, isTap ? x1 : x1, isTap ? y1 : y1, isTap);
                if (st != null) statsEncoded = st.encode();
                ok = MotionInjector.inject(points, isTap);
                detail = ok ? "ok" : MotionInjector.lastError();
            } catch (Throwable e) {
                ok = false;
                detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            reply.writeNoException();
            reply.writeInt(ok ? 1 : 0);
            reply.writeString(detail == null ? "" : detail);
            reply.writeString(statsEncoded);
            return true;
        }

        if (code == TRANSACTION_DESTROY) {
            System.exit(0);
            return true;
        }

        return super.onTransact(code, data, reply, flags);
    }

    private static void streamScreenshot(ParcelFileDescriptor writeSide) {
        Process process = null;
        try (ParcelFileDescriptor.AutoCloseOutputStream out =
                     new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
            process = new ProcessBuilder("/system/bin/screencap", "-p").start();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n > 0) out.write(buffer, 0, n);
                }
                out.flush();
            }
            process.waitFor();
        } catch (Throwable ignored) {
            // Closing the pipe is the error signal to the client. OCR will log "截图失败".
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static CommandResult runCommand(String command) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            process = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }

            int exitCode = process.waitFor();
            return new CommandResult(exitCode, out.toString().trim());
        } catch (Throwable e) {
            return new CommandResult(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static final class CommandResult {
        final int code;
        final String output;
        CommandResult(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }
}

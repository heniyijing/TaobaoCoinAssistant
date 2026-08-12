package com.local.taobaocoinassistant;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure visual OCR engine for v2.1.
 *
 * The screenshot source is Shizuku shell `screencap -p`; Android Accessibility APIs are not used.
 * Every page decision therefore sees the same real pixels the user sees.
 */
public final class OcrEngine {
    private static final long SCREENSHOT_MIN_INTERVAL_MS = 350;
    private final TextRecognizer recognizer;
    private final Object screenshotLock = new Object();
    private long lastScreenshotRequestAt = 0L;

    public OcrEngine() {
        this.recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    }

    public Snapshot capture() throws Exception {
        // General purpose OCR. Downscale before ML Kit to reduce latency while keeping original coordinates.
        return captureInternal(140, 80, 250, 720, 1.0f);
    }

    /** Fast upper-screen OCR for Taobao home / coin home navigation. */
    public Snapshot captureNavigation() throws Exception {
        // Navigation targets are all in the upper ~58% of the screen. Ignoring the product feed
        // removes almost half of the pixels ML Kit previously had to scan.
        // Different accounts place the shortcut row at slightly different heights. Scan a wider
        // upper area while still excluding most of the product feed. A bit more recognition width
        // also helps distinguish short Chinese shortcut labels such as “领淘金币”.
        return captureInternal(80, 60, 80, 840, 0.72f);
    }

    /** OCR for the quick-earn panel and its expanded task area. */
    public Snapshot captureTaskPanel() throws Exception {
        return captureInternal(120, 70, 140, 840, 0.94f);
    }

    public Snapshot captureSearchPage() throws Exception {
        // Search input/button live near the top; the lower recommendation grid is irrelevant.
        return captureInternal(90, 60, 90, 860, 0.62f);
    }

    public Snapshot captureTaskList() throws Exception {
        // Full-height list context is still needed for task rows, but downscaling saves OCR time.
        // Task titles are safety-sensitive because they drive blacklist/routing rules. Use a
        // moderately higher OCR resolution than navigation so Chinese look-alike characters are
        // less likely to collapse after downscaling.
        return captureInternal(190, 70, 190, 960, 1.0f);
    }

    private Snapshot captureInternal(int requestedTopCrop, int requestedBottomCrop, int minRowY,
                                     int maxRecognitionWidth, float maxBottomFraction) throws Exception {
        Bitmap bitmap = takeScreenshotBlocking();
        if (bitmap == null) throw new IllegalStateException("Shizuku screencap 截图失败");
        Bitmap croppedBitmap = bitmap;
        Bitmap recognitionBitmap = bitmap;
        int yOffset = 0;
        float recognitionScale = 1.0f;
        try {
            if ((requestedTopCrop > 0 || requestedBottomCrop > 0 || maxBottomFraction < 0.999f)
                    && bitmap.getHeight() > 300) {
                int top = Math.min(Math.max(0, requestedTopCrop), bitmap.getHeight() - 2);
                int bottomInset = Math.min(Math.max(0, requestedBottomCrop), Math.max(0, bitmap.getHeight() - top - 1));
                if (maxBottomFraction > 0f && maxBottomFraction < 1.0f) {
                    int desiredBottomY = Math.max(top + 120, Math.min(bitmap.getHeight(),
                            Math.round(bitmap.getHeight() * maxBottomFraction)));
                    bottomInset = Math.max(bottomInset, bitmap.getHeight() - desiredBottomY);
                }
                int cropHeight = bitmap.getHeight() - top - bottomInset;
                if (cropHeight > 100) {
                    croppedBitmap = Bitmap.createBitmap(bitmap, 0, top, bitmap.getWidth(), cropHeight);
                    recognitionBitmap = croppedBitmap;
                    yOffset = top;
                }
            }

            if (maxRecognitionWidth > 0 && recognitionBitmap.getWidth() > maxRecognitionWidth) {
                recognitionScale = maxRecognitionWidth / (float) recognitionBitmap.getWidth();
                int scaledHeight = Math.max(1, Math.round(recognitionBitmap.getHeight() * recognitionScale));
                recognitionBitmap = Bitmap.createScaledBitmap(recognitionBitmap, maxRecognitionWidth, scaledHeight, true);
            }

            Text text = recognizeBlocking(recognitionBitmap);
            List<OcrRow> rows = mergeRows(text, bitmap.getHeight(), yOffset, minRowY, recognitionScale);
            List<OcrRow> elements = extractElements(text, bitmap.getHeight(), yOffset, minRowY, recognitionScale);
            return new Snapshot(bitmap.getWidth(), bitmap.getHeight(), rows, elements);
        } finally {
            if (recognitionBitmap != croppedBitmap && recognitionBitmap != bitmap) recognitionBitmap.recycle();
            if (croppedBitmap != bitmap) croppedBitmap.recycle();
            bitmap.recycle();
        }
    }

    private Bitmap takeScreenshotBlocking() throws Exception {
        synchronized (screenshotLock) {
            long now = SystemClock.elapsedRealtime();
            long wait = SCREENSHOT_MIN_INTERVAL_MS - (now - lastScreenshotRequestAt);
            if (wait > 0) SystemClock.sleep(wait);
            lastScreenshotRequestAt = SystemClock.elapsedRealtime();
        }

        ParcelFileDescriptor pfd = ShizukuShell.openScreenshot();
        if (pfd == null) throw new IllegalStateException("无法从 Shizuku UserService 获取截图管道");
        try (ParcelFileDescriptor.AutoCloseInputStream in =
                     new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) throw new IllegalStateException("screencap PNG 解码失败");
            if (bitmap.getConfig() == Bitmap.Config.ARGB_8888) return bitmap;
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();
            if (copy == null) throw new IllegalStateException("截图 Bitmap 转换失败");
            return copy;
        }
    }

    private Text recognizeBlocking(Bitmap bitmap) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Text> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(t -> { resultRef.set(t); latch.countDown(); })
                .addOnFailureListener(e -> { errorRef.set(e); latch.countDown(); });
        if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("OCR 超时");
        if (errorRef.get() != null) throw new Exception(errorRef.get());
        Text t = resultRef.get();
        if (t == null) throw new IllegalStateException("OCR 返回空结果");
        return t;
    }

    private List<OcrRow> extractElements(Text text, int screenHeight, int yOffset, int minRowY, float recognitionScale) {
        List<OcrRow> out = new ArrayList<>();
        float invScale = recognitionScale <= 0f ? 1.0f : (1.0f / recognitionScale);
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    Rect rawBox = element.getBoundingBox();
                    if (rawBox == null) continue;
                    Rect box = new Rect(
                            Math.round(rawBox.left * invScale),
                            Math.round(rawBox.top * invScale) + yOffset,
                            Math.round(rawBox.right * invScale),
                            Math.round(rawBox.bottom * invScale) + yOffset
                    );
                    if (box.bottom < minRowY || box.top > screenHeight - 80) continue;
                    String t = element.getText();
                    if (t == null || t.trim().isEmpty()) continue;
                    out.add(new OcrRow(t, box));
                }
            }
        }
        out.sort(Comparator.comparingInt(a -> a.bounds.centerY()));
        return out;
    }

    private List<OcrRow> mergeRows(Text text, int screenHeight, int yOffset, int minRowY, float recognitionScale) {
        List<OcrPiece> pieces = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect rawBox = line.getBoundingBox();
                if (rawBox == null) continue;
                float invScale = recognitionScale <= 0f ? 1.0f : (1.0f / recognitionScale);
                Rect box = new Rect(
                        Math.round(rawBox.left * invScale),
                        Math.round(rawBox.top * invScale) + yOffset,
                        Math.round(rawBox.right * invScale),
                        Math.round(rawBox.bottom * invScale) + yOffset
                );
                if (box.bottom < minRowY || box.top > screenHeight - 80) continue;
                pieces.add(new OcrPiece(line.getText(), box));
            }
        }
        pieces.sort(Comparator.comparingInt(a -> a.bounds.centerY()));

        List<List<OcrPiece>> groups = new ArrayList<>();
        for (OcrPiece p : pieces) {
            List<OcrPiece> target = null;
            for (List<OcrPiece> g : groups) {
                int avgY = 0;
                for (OcrPiece q : g) avgY += q.bounds.centerY();
                avgY /= Math.max(1, g.size());
                if (Math.abs(avgY - p.bounds.centerY()) <= 42) {
                    target = g;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                groups.add(target);
            }
            target.add(p);
        }

        List<OcrRow> rows = new ArrayList<>();
        for (List<OcrPiece> group : groups) {
            group.sort(Comparator.comparingInt(a -> a.bounds.left));
            StringBuilder sb = new StringBuilder();
            Rect union = null;
            for (OcrPiece p : group) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(p.text);
                if (union == null) union = new Rect(p.bounds);
                else union.union(p.bounds);
            }
            if (union != null && sb.length() > 0) rows.add(new OcrRow(sb.toString(), union));
        }
        rows.sort(Comparator.comparingInt(a -> a.bounds.centerY()));
        return rows;
    }

    public void close() {
        try { recognizer.close(); } catch (Throwable ignored) {}
    }

    private static final class OcrPiece {
        final String text;
        final Rect bounds;
        OcrPiece(String text, Rect bounds) { this.text = text == null ? "" : text; this.bounds = bounds; }
    }

    public static final class OcrRow {
        public final String text;
        public final Rect bounds;
        OcrRow(String text, Rect bounds) { this.text = text; this.bounds = bounds; }
    }

    public static final class Snapshot {
        public final int width;
        public final int height;
        public final List<OcrRow> rows;
        // Unmerged ML Kit word-like elements. Navigation taps prefer these bounds because a merged
        // row may contain several shortcut labels on the same horizontal line.
        public final List<OcrRow> elements;
        Snapshot(int width, int height, List<OcrRow> rows, List<OcrRow> elements) {
            this.width = width;
            this.height = height;
            this.rows = rows;
            this.elements = elements;
        }
        public String allText() {
            StringBuilder sb = new StringBuilder();
            for (OcrRow r : rows) sb.append(r.text).append('\n');
            return sb.toString();
        }
    }
}

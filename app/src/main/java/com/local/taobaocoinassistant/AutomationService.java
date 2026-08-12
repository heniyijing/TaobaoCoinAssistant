package com.local.taobaocoinassistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutomationService extends Service {
    private static final String TB = "com.taobao.taobao";
    private static final String TMALL = "com.tmall.wireless";
    private static final int COIN_HOME_EVIDENCE_THRESHOLD = 4;
    private static volatile AutomationService INSTANCE;
    private static final String ACTION_START = "com.local.taobaocoinassistant.START";
    private static final String ACTION_STOP = "com.local.taobaocoinassistant.STOP";
    private static final String ACTION_SYNC_OVERLAY = "com.local.taobaocoinassistant.SYNC_OVERLAY";
    private static final int FOREGROUND_ID = 2001;
    private static final String FOREGROUND_CHANNEL = "coin_automation";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private StatusOverlay statusOverlay;
    private final Runnable overlayTicker = new Runnable() {
        @Override public void run() {
            syncOverlay();
            mainHandler.postDelayed(this, 500);
        }
    };
    private final Map<String, Integer> attempted = new HashMap<>();
    private volatile boolean stopRequested = false;
    private volatile boolean automationJobActive = false;
    private OcrEngine ocr;
    private int executedCount = 0;
    private int noChangeScrollCount = 0;
    private int scrollCount = 0;
    private int finalTopRecheckRounds = 0;
    private TaskRuleStore.Rules taskRules;
    private int lastDailySignX = -1;
    private int lastDailySignY = -1;
    // “赚更多金币”几何兜底每次打开任务面板最多使用一次，避免弹窗已出现但 OCR 未刷新时重复点击背景。
    private boolean coinHomeGeometryFallbackUsed = false;
    // v1.1: generic modal/ad obstruction recovery. Back is safer than tapping an unknown X.
    private int popupDismissCount = 0;

    // 当新版 UC WebView 不暴露 EditText 时，Android shell 的 `input text` 对中文并不可靠。
    // 因此 OCR+Shizuku 兜底使用纯 ASCII 搜索词；只用于完成“搜一搜”任务，不改变任务筛选规则。
    private static final List<String> SEARCH_KEYS_ASCII = Arrays.asList(
            "iphone", "macbook", "xiaomi", "camera", "milk"
    );

    public static boolean isServiceActive() {
        AutomationService s = INSTANCE;
        return s != null;
    }

    public static void start(Context context) {
        Intent i = new Intent(context, AutomationService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void requestStop(Context context) {
        AutomationService s = INSTANCE;
        if (s != null) {
            s.stopAutomation();
            return;
        }
        // No active controller means there is nothing to stop.
        AppState.running = false;
        AppState.status = "已停止";
    }

    public static void syncOverlay(Context context) {
        AutomationService s = INSTANCE;
        if (s != null) {
            s.syncOverlay();
            return;
        }
        if (!OverlaySettings.isEnabled(context)) return;
        Intent i = new Intent(context, AutomationService.class).setAction(ACTION_SYNC_OVERLAY);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        if (ocr == null) ocr = new OcrEngine();
        if (statusOverlay == null) statusOverlay = new StatusOverlay(this, this::stopAutomation);
        Notification foreground = buildForegroundNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_ID, foreground, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FOREGROUND_ID, foreground);
        }
        mainHandler.removeCallbacks(overlayTicker);
        mainHandler.post(overlayTicker);
        AppState.log("纯 Shizuku + OCR 自动化服务已启动（不使用无障碍）");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAutomation();
        } else if (ACTION_START.equals(action)) {
            startAutomation();
        } else {
            syncOverlay();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        AppState.running = false;
        INSTANCE = null;
        mainHandler.removeCallbacks(overlayTicker);
        if (statusOverlay != null) statusOverlay.hide();
        if (ocr != null) ocr.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    private Notification buildForegroundNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    FOREGROUND_CHANNEL, "淘金币自动化", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持纯 Shizuku + OCR 自动化运行");
            nm.createNotificationChannel(ch);
        }
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, AutomationService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "停止脚本", stopPi).build();
        return new Notification.Builder(this, FOREGROUND_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("淘金币助手")
                .setContentText(AppState.running ? "自动化运行中" : "自动化服务就绪")
                .setContentIntent(pi)
                .addAction(stopAction)
                .setOngoing(true)
                .build();
    }

    private void refreshForegroundNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(FOREGROUND_ID, buildForegroundNotification());
        } catch (Throwable ignored) {}
    }

    private void syncOverlay() {
        if (statusOverlay == null) return;
        mainHandler.post(() -> {
            if (statusOverlay == null) return;
            if (OverlaySettings.isEnabled(this)) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    statusOverlay.hide();
                    if (!AppState.running) stopSelf();
                    return;
                }
                if (!statusOverlay.isAttached()) statusOverlay.show();
                statusOverlay.update();
            } else {
                statusOverlay.hide();
                if (!AppState.running) stopSelf();
            }
        });
    }

    private synchronized void startAutomation() {
        if (automationJobActive || AppState.running) {
            AppState.log("上一轮任务仍在收尾，请稍后再开始");
            return;
        }
        automationJobActive = true;
        stopRequested = false;
        attempted.clear();
        executedCount = 0;
        noChangeScrollCount = 0;
        scrollCount = 0;
        finalTopRecheckRounds = 0;
        popupDismissCount = 0;
        taskRules = TaskRuleStore.load(this);
        AppState.log("━━━━━━━━━━ 新一轮执行 ━━━━━━━━━━");
        AppState.log("任务规则已加载：" + taskRules.summary());
        AppState.running = true;
        AppState.status = "正在启动";
        refreshForegroundNotification();
        worker.execute(this::runAutomation);
    }

    private void stopAutomation() {
        stopRequested = true;
        AppState.running = false;
        AppState.status = "已停止";
        AppState.log("收到停止请求");
        refreshForegroundNotification();
    }

    private void runAutomation() {
        NotificationHelper.notify(this, "淘金币助手", "任务开始执行", 1001);
        AppState.log("开始执行：单线程状态机，不启用后台自动点击");
        try {
            if (!ShizukuShell.hasPermission()) {
                throw new IllegalStateException("Shizuku 未连接或未授权");
            }
            if (!enterTaskList(true)) {
                throw new IllegalStateException("无法进入淘金币任务列表");
            }
            AppState.status = "识别任务中";
            mainLoop();
            if (!stopRequested) {
                AppState.status = "已完成";
                AppState.log("本次结束，共发起执行 " + executedCount + " 次任务");
                NotificationHelper.notify(this, "淘金币助手", "任务执行结束，共发起 " + executedCount + " 次", 1002);
            }
        } catch (Throwable e) {
            AppState.status = "异常结束";
            AppState.log("异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            NotificationHelper.notify(this, "淘金币助手", "任务异常结束，请查看日志", 1003);
        } finally {
            AppState.running = false;
            automationJobActive = false;
            refreshForegroundNotification();
            if (!OverlaySettings.isEnabled(this)) mainHandler.post(this::stopSelf);
        }
    }

    private void mainLoop() throws Exception {
        int emptyCount = 0;
        while (!stopRequested) {
            sleep(1800);
            if (stopRequested) return;

            ForegroundState fg = currentForeground();
            if (!fg.isPackage(TB)) {
                AppState.log("主循环检测到已离开淘宝: " + fg + "，执行保险恢复");
                if (!enterTaskList(true)) throw new IllegalStateException("恢复任务列表失败");
                noChangeScrollCount = 0;
                scrollCount = 0;
                emptyCount = 0;
                continue;
            }

            OcrEngine.Snapshot snap = ocr.captureTaskList();
            if (dismissBlockingPopupIfPresent(snap, "任务列表")) {
                sleep(900);
                continue;
            }
            String all = snap.allText();
            if (containsVerification(all)) {
                AppState.log("检测到淘宝安全验证，本次停止；请手动处理，不自动绕过验证");
                stopRequested = true;
                return;
            }

            // 先强认证“这真的是淘金币任务列表”，再允许解析/点击。
            // 单独看到一个 (1/3) 之类进度绝不能作为任务页证据，否则商品活动日期会被误当任务。
            if (!TaskParser.isTrustedTaskContext(snap.rows, snap.height)) {
                AppState.log("淘宝前台但当前画面不具备可信任务列表特征；禁止解析/点击，先等待一帧复核");
                sleep(900);
                OcrEngine.Snapshot contextRetry = ocr.captureTaskList();
                if (!TaskParser.isTrustedTaskContext(contextRetry.rows, contextRetry.height)) {
                    AppState.log("连续两帧未通过任务页认证，执行返回/完整恢复，避免把商品页当任务页");
                    returnToTaskList();
                    if (stopRequested) return;
                    OcrEngine.Snapshot recovered = ocr.captureTaskList();
                    if (!TaskParser.isTrustedTaskContext(recovered.rows, recovered.height)) {
                        AppState.log("常规返回后仍不是可信任务列表，强制重建淘金币任务页");
                        if (!enterTaskList(true)) throw new IllegalStateException("任务页上下文恢复失败");
                    }
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
                snap = contextRetry;
            }

            List<TaskParser.TaskItem> tasks = TaskParser.parse(snap.rows, snap.height);
            boolean expandedPanelContext = TaskParser.isExpandedTaskPanelView(snap.rows, snap.height);
            if (tasks.isEmpty() && expandedPanelContext) {
                emptyCount = 0;
                AppState.log("已确认今日速赚/专享福利任务面板，但当前视口没有带进度任务；直接进入下滑流程");
            } else if (tasks.isEmpty()) {
                emptyCount++;
                AppState.log("当前 OCR 为 0 个任务 (" + emptyCount + "/3)，只等待重识别");
                sleep(1500);

                OcrEngine.Snapshot retrySnap = ocr.captureTaskList();
                List<TaskParser.TaskItem> retryTasks = TaskParser.parse(retrySnap.rows, retrySnap.height);
                if (!retryTasks.isEmpty()) {
                    AppState.log("重新识别恢复，当前可见任务 " + retryTasks.size() + " 个");
                    emptyCount = 0;
                    continue;
                }

                ForegroundState retryFg = currentForeground();
                if (!retryFg.isPackage(TB)) {
                    AppState.log("OCR 持续为空且已离开淘宝: " + retryFg);
                    if (!enterTaskList(true)) throw new IllegalStateException("OCR 空白后恢复失败");
                    emptyCount = 0;
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    continue;
                }

                if (emptyCount >= 3) {
                    AppState.log("连续 3 次 OCR 为空但仍在淘金币容器，执行完整页面恢复作为保险");
                    if (!enterTaskList(true)) throw new IllegalStateException("OCR 空白后恢复失败");
                    emptyCount = 0;
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                }
                continue;
            }
            emptyCount = 0;

            AppState.log("当前屏幕识别到 " + tasks.size() + " 个任务");
            List<TaskParser.TaskItem> ordered = new ArrayList<>(tasks);
            ordered.sort(Comparator.comparingInt(TaskParser::priority).thenComparingInt(t -> t.y));

            TaskParser.TaskItem chosen = null;
            for (TaskParser.TaskItem t : ordered) {
                AppState.log("候选: " + t.name);
                if (TaskParser.skip(t, taskRules)) {
                    AppState.log("跳过: " + t.name);
                    continue;
                }
                if (attempted.getOrDefault(t.name, 0) >= 2) {
                    AppState.log("已尝试过多次，跳过: " + t.name);
                    continue;
                }
                chosen = t;
                break;
            }

            if (chosen != null) {
                noChangeScrollCount = 0;
                scrollCount = 0;
                executeTask(chosen, snap.width, snap.height);
                continue;
            }

            if (TaskParser.bottomVisible(snap.rows)) {
                AppState.log("已到任务列表底部；当前剩余任务均为已完成/黑名单，进入收尾复查/领奖");
                if (finalRewardSweep()) {
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
                return;
            }

            scrollCount++;
            String before = signature(tasks);
            AppState.log("当前视口没有可执行任务，使用纯 Shizuku input swipe 向下滚动 (" + scrollCount + "/10)");
            shellSwipeList(snap.width, snap.height);
            sleep(1200);

            List<TaskParser.TaskItem> afterTasks = new ArrayList<>();
            OcrEngine.Snapshot afterSnap = null;
            try {
                afterSnap = ocr.captureTaskList();
                afterTasks = TaskParser.parse(afterSnap.rows, afterSnap.height);
                if (afterTasks.isEmpty()) {
                    sleep(550);
                    afterSnap = ocr.captureTaskList();
                    afterTasks = TaskParser.parse(afterSnap.rows, afterSnap.height);
                }
            } catch (Throwable e) {
                AppState.log("滚动后 OCR 暂时失败: " + e.getMessage());
            }

            ForegroundState afterFg = currentForeground();
            if (!afterFg.isPackage(TB)) {
                AppState.log("滑动后检测到已离开淘宝: " + afterFg + "，执行完整任务页恢复");
                if (!enterTaskList(true)) throw new IllegalStateException("滑动后恢复任务列表失败");
                scrollCount = 0;
                noChangeScrollCount = 0;
                emptyCount = 0;
                continue;
            }

            // Reuse the same post-swipe OCR frame for context certification; v2.0 used to take
            // an extra full screenshot here, which made every list swipe unnecessarily slow.
            try {
                if (afterSnap != null && !TaskParser.isTrustedTaskContext(afterSnap.rows, afterSnap.height)) {
                    AppState.log("滚动后任务页上下文丢失，停止解析并执行恢复");
                    if (!enterTaskList(true)) throw new IllegalStateException("滚动后任务页上下文恢复失败");
                    scrollCount = 0;
                    noChangeScrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
            } catch (Throwable e) {
                AppState.log("滚动后任务页认证暂时失败: " + e.getMessage());
            }

            if (afterTasks.isEmpty()) {
                // 与最终 Python 版一致：仍在 TMS 容器时，不把一次空 OCR 当成“没有滚动”。
                AppState.log("滚动后 OCR 暂时为空；不补点击、不后退，下一轮重新确认");
                noChangeScrollCount = 0;
            } else {
                String after = signature(afterTasks);
                if (after.equals(before)) {
                    noChangeScrollCount++;
                    AppState.log("滚动后任务未变化 (" + noChangeScrollCount + "/2)");
                } else {
                    noChangeScrollCount = 0;
                    AppState.log("滚动后任务已变化");
                }
            }

            if (noChangeScrollCount >= 2) {
                AppState.log("连续两次滚动后任务内容未变化，判断已到底，进入收尾复查/领奖");
                if (finalRewardSweep()) {
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
                return;
            }
            if (scrollCount >= 10) {
                AppState.log("连续多次滚动后仍未找到可执行任务，进入收尾复查/领奖");
                if (finalRewardSweep()) {
                    noChangeScrollCount = 0;
                    scrollCount = 0;
                    emptyCount = 0;
                    continue;
                }
                return;
            }
        }
    }

    private void executeTask(TaskParser.TaskItem task, int width, int height) throws Exception {
        attempted.put(task.name, attempted.getOrDefault(task.name, 0) + 1);
        executedCount++;
        AppState.status = "执行: " + task.name;
        boolean externalTask = TaskParser.external(task, taskRules);
        boolean searchTask = TaskParser.isSearch(task, taskRules);
        AppState.log("准备执行任务[" + TaskParser.displayMode(task, taskRules) + "]: " + task.name);

        int x = (int) (width * 0.875f);
        shellTap(x, task.y);
        sleep(3500);
        if (stopRequested) return;

        if (searchTask) {
            boolean submitted = handleSearchTask();
            if (stopRequested) return;
            if (!submitted) {
                // 搜索页没有真正提交关键词时，不要在入口页机械滑动 22 秒。
                AppState.log("搜索任务未能提交搜索；直接返回任务列表，不在搜索入口页空滑");
                returnToTaskList();
                if (!stopRequested) AppState.status = "识别任务中";
                return;
            }
        }

        if (stopRequested) return;
        browseCurrentTask(width, height, externalTask);
        if (stopRequested) return;
        returnToTaskList();
        if (!stopRequested) AppState.status = "识别任务中";
    }

    private void browseCurrentTask(int width, int height, boolean external) throws Exception {
        final long durationMs = 22_000L;
        final long startedAt = SystemClock.elapsedRealtime();
        long lastDialogCheckAt = 0L;

        AppState.log(external ? "跨应用任务：等待约 22 秒，不在外部 App 内主动滑动" : "普通浏览任务：按最终脚本逻辑浏览约 22 秒");

        while (!stopRequested && SystemClock.elapsedRealtime() - startedAt < durationMs) {
            // 纯 OCR 模式：每隔约 3 秒检查一次“浏览器打开”系统弹窗。
            long now = SystemClock.elapsedRealtime();
            if (now - lastDialogCheckAt >= 3000L) {
                lastDialogCheckAt = now;
                if (cancelBrowserOpenDialogIfPresent()) break;
            }

            ForegroundState fg = currentForeground();
            if (external || (!fg.isPackage(TB) && !fg.isPackage(TMALL))) {
                // 与 Python task_loop 一致：跨应用任务只等待，不对外部 App 做机械滑动。
                sleep(1000);
                continue;
            }

            int startX = randomBetween(Math.max(1, width / 6), Math.max(2, width / 2));
            int startY = randomBetween(Math.max(1, height / 2), Math.max(2, height - height / 4));
            int endX = randomBetween(Math.max(1, startX - Math.max(20, width / 14)), Math.max(2, startX));
            int endYUpper = Math.max(220, startY - 300);
            int endY = randomBetween(200, endYUpper);
            int verticalDistance = Math.abs(startY - endY);
            int duration = verticalDistance > 500
                    ? randomBetween(400, 1000)
                    : randomBetween(200, 500);

            ShizukuShell.Result r = ShizukuShell.exec(
                    "input swipe " + startX + " " + startY + " " + endX + " " + endY + " " + duration
            );
            if (!r.ok()) AppState.log("浏览滑动失败: " + r.output);
            sleep(800 + random.nextInt(1201));
        }
    }

    private int randomBetween(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        if (hi <= lo) return lo;
        return lo + random.nextInt(hi - lo + 1);
    }

    /**
     * 搜一搜任务：纯 OCR 定位搜索栏，Shizuku 负责聚焦、输入和提交。
     * 不读取 EditText 或 WebView UI tree；搜索页只以截图 OCR 与 Shizuku 输入为准。
     */
    private boolean handleSearchTask() throws Exception {
        AppState.status = "搜索任务：OCR定位搜索框";

        for (int attempt = 1; attempt <= 8 && !stopRequested; attempt++) {
            try {
                OcrEngine.Snapshot snap = ocr.captureSearchPage();
                SearchVisualTarget target = findSearchVisualTarget(snap);
                if (target != null) {
                    AppState.log("OCR 已定位新版搜索页，搜索栏 y=" + target.inputY + "，使用 Shizuku 输入");
                    if (submitSearchByVisualTarget(target)) return true;
                    if (stopRequested) return false;
                } else if (attempt == 1 || attempt == 4 || attempt == 8) {
                    AppState.log("搜索页 OCR 暂未定位输入栏 (" + attempt + "/8): " + summarizeSearchOcr(snap));
                }
            } catch (Throwable e) {
                if (attempt == 1 || attempt == 8) {
                    AppState.log("搜索页 OCR 失败 (" + attempt + "/8): " + e.getMessage());
                }
            }
            sleep(650);
        }

        if (!stopRequested) AppState.log("未能通过 OCR 定位/提交新版搜索框");
        return false;
    }

    private boolean submitSearchByVisualTarget(SearchVisualTarget target) throws Exception {
        if (target == null || stopRequested) return false;

        shellTap(target.inputX, target.inputY);
        sleep(650);
        if (stopRequested) return false;

        String keyword = SEARCH_KEYS_ASCII.get(random.nextInt(SEARCH_KEYS_ASCII.size()));
        // 先尝试清掉可能残留的旧关键词，再输入纯 ASCII，避免依赖任何 EditText/IME API。
        ShizukuShell.exec("input keyevent KEYCODE_MOVE_END");
        ShizukuShell.exec("i=0; while [ $i -lt 24 ]; do input keyevent KEYCODE_DEL; i=$((i+1)); done");
        ShizukuShell.Result typed = ShizukuShell.exec("input text " + keyword);
        if (!typed.ok()) {
            AppState.log("Shizuku 向搜索框输入关键词失败: " + typed.output);
            return false;
        }
        AppState.log("已用 Shizuku 向 OCR 定位的搜索框输入关键词: " + keyword);

        sleep(650);
        if (stopRequested) return false;
        shellTap(target.searchX, target.searchY);
        AppState.log("通过 OCR 几何位置点击右侧“搜索”按钮: (" + target.searchX + ", " + target.searchY + ")");

        AppState.status = "搜索任务：已提交 " + keyword;
        sleep(1800);
        if (stopRequested) return false;

        try {
            OcrEngine.Snapshot verify = ocr.captureSearchPage();
            String verifyText = normalizeOcr(verify.allText());
            if (verifyText.contains("搜索宝贝") && !verifyText.toLowerCase().contains(keyword.toLowerCase())) {
                AppState.log("点击搜索后仍检测到空白‘搜索宝贝’占位符，判定本次搜索未提交");
                return false;
            }
        } catch (Throwable e) {
            AppState.log("搜索提交后的 OCR 确认暂时失败，继续执行: " + e.getMessage());
        }

        sleep(1200);
        return !stopRequested;
    }

    private SearchVisualTarget findSearchVisualTarget(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        String all = normalizeOcr(snap.allText());
        boolean pageEvidence = all.contains("搜索有福利")
                || all.contains("搜索后浏览")
                || all.contains("搜索宝贝")
                || fuzzyContains(all, "搜索宝贝", 1);
        if (!pageEvidence) return null;

        int minY = (int) (snap.height * 0.09f);
        int maxY = (int) (snap.height * 0.20f);
        int y = -1;
        for (OcrEngine.OcrRow row : snap.rows) {
            int cy = row.bounds.centerY();
            if (cy < minY || cy > maxY) continue;
            String t = normalizeOcr(row.text);
            if (t.contains("搜索宝贝") || fuzzyContains(t, "搜索宝贝", 1)
                    || (t.contains("搜索") && row.bounds.width() > snap.width * 0.35f)) {
                y = cy;
                break;
            }
        }

        // 某些 OCR 帧只识别到“搜索有福利/搜索后浏览”，但漏掉 placeholder。
        // 已有页面证据时，搜索栏在新版页面顶部固定带中，使用比例 y 作为有限兜底。
        if (y < 0) y = (int) (snap.height * 0.132f);

        int inputX = (int) (snap.width * 0.36f);
        int searchX = (int) (snap.width * 0.895f);
        return new SearchVisualTarget(inputX, y, searchX, y);
    }

    private String summarizeSearchOcr(OcrEngine.Snapshot snap) {
        if (snap == null) return "<空截图>";
        StringBuilder sb = new StringBuilder();
        for (OcrEngine.OcrRow row : snap.rows) {
            int y = row.bounds.centerY();
            if (y > snap.height * 0.34f) break;
            String t = normalizeOcr(row.text);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
            if (sb.length() > 180) break;
        }
        return sb.length() == 0 ? "<顶部无文字>" : sb.toString();
    }

    private static final class SearchVisualTarget {
        final int inputX;
        final int inputY;
        final int searchX;
        final int searchY;

        SearchVisualTarget(int inputX, int inputY, int searchX, int searchY) {
            this.inputX = inputX;
            this.inputY = inputY;
            this.searchX = searchX;
            this.searchY = searchY;
        }
    }

    private boolean enterTaskList(boolean cleanStart) throws Exception {
        AppState.status = "进入淘金币";

        // 完整恢复流程只在需要时清理并启动一次淘宝。
        // 进入“领淘金币”后不要求 UC WebView 暴露任何 UI 节点，只按真实截图继续
        // “签到/赚更多金币”的下一阶段，避免明明已到淘金币首页却被误判为失败。
        if (cleanStart) {
            ShizukuShell.exec("am force-stop " + TB);
        }
        launchTaobao();
        sleep(2600);

        for (int attempt = 1; attempt <= 2 && !stopRequested; attempt++) {
            AppState.log("进入任务页尝试 " + attempt + "/2");

            if (!TB.equals(currentPackage())) {
                AppState.log("当前不在淘宝，重新拉起淘宝，但不重复 force-stop");
                launchTaobao();
                sleep(1800);
            } else if (attempt > 1) {
                AppState.log("继续在当前淘宝页面重试，不重启 App");
                sleep(900);
            }

            // One fast navigation frame handles all three states: already in task panel,
            // already on coin home, or still on Taobao home with the coin entry visible.
            boolean atCoinHome = openCoinHomeFromTaobaoHome();
            if (!atCoinHome) {
                AppState.log("未能打开淘金币入口");
                continue;
            }

            // 点击“领淘金币”后，即使下一帧 OCR 暂时没更新，也直接尝试处理中央的
            // “签到领金币 / 赚更多金币”，不再返回淘宝首页重复导航。
            AppState.log("已到淘金币页面，直接处理签到/赚更多金币");
            if (openTaskPanel()) {
                AppState.log("已进入淘金币任务列表");
                return true;
            }

            AppState.log("当前淘金币页面暂未打开/确认任务面板；保持当前页面重试，不重启淘宝");
        }
        return false;
    }

    private boolean openCoinHomeFromTaobaoHome() throws Exception {
        AppState.log("检查当前页面：快速 OCR 确认是否已在淘金币首页，否则寻找‘领淘金币’入口");

        for (int i = 1; i <= 3 && !stopRequested; i++) {
            OcrEngine.Snapshot snap;
            try {
                snap = ocr.captureNavigation();
            } catch (Throwable e) {
                AppState.log("导航 OCR 失败: " + e.getMessage());
                sleep(500);
                continue;
            }

            if (dismissBlockingPopupIfPresent(snap, "淘宝/淘金币首页")) {
                sleep(850);
                continue;
            }

            if (taskPanelVisibleInSnapshot(snap)) {
                AppState.log("OCR 已确认任务弹窗已经打开，不重复走首页导航");
                return true;
            }
            if (looksLikeCoinHomeSnapshot(snap)) {
                AppState.log("OCR 已确认当前就在淘金币首页，不重复走首页导航");
                return true;
            }

            int[] p = findTextPoint(snap, "领淘金币", false);
            if (p == null) p = findTextPoint(snap, "淘金币", false);
            if (p != null) {
                shellTap(p[0], p[1]);
                AppState.log("通过宽范围 OCR 点击淘宝首页淘金币入口: (" + p[0] + ", " + p[1] + ")");

                // Do not assume a tap succeeded. Shortcut labels from multiple icons are sometimes
                // merged into one OCR row; the next frame is the source of truth. If we are still
                // on Taobao home, retry with a fresh frame instead of treating the product feed as
                // the coin page.
                sleep(1900);
                boolean confirmed = false;
                for (int confirm = 1; confirm <= 2 && !stopRequested; confirm++) {
                    try {
                        OcrEngine.Snapshot afterTap = ocr.captureNavigation();
                        if (taskPanelVisibleInSnapshot(afterTap) || looksLikeCoinHomeSnapshot(afterTap)) {
                            confirmed = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                    if (confirm < 2) sleep(650);
                }
                if (confirmed) {
                    AppState.log("已确认进入淘金币页面");
                    return true;
                }
                AppState.log("点击淘金币入口后仍未确认进入；重新定位入口，不把淘宝商品首页误当淘金币页");
                continue;
            }
            AppState.log("第 " + i + "/3 次宽范围 OCR 未找到淘金币入口");
            sleep(500);
        }

        AppState.log("OCR 未找到淘宝首页淘金币入口");
        return false;
    }

    private void launchTaobao() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TB);
        ComponentName component = launch == null ? null : launch.getComponent();
        if (component == null) {
            AppState.log("无法解析淘宝启动 Activity");
            return;
        }
        String flat = component.flattenToShortString();
        ShizukuShell.Result r = ShizukuShell.exec("am start -n " + flat + " >/dev/null");
        if (!r.ok()) AppState.log("Shizuku 拉起淘宝失败: " + r.output);
    }

    private boolean openTaskPanel() throws Exception {
        // v2.1 fast path: capture the upper screen once and reuse it for panel/sign-in/more-coins
        // decisions instead of doing 2~3 full OCR passes before the first click.
        coinHomeGeometryFallbackUsed = false;

        OcrEngine.Snapshot first = null;
        try {
            first = ocr.captureNavigation();
        } catch (Throwable e) {
            AppState.log("淘金币首页快速 OCR 失败: " + e.getMessage());
        }

        if (first != null && dismissBlockingPopupIfPresent(first, "淘金币首页")) {
            sleep(850);
            try { first = ocr.captureNavigation(); } catch (Throwable ignored) {}
        }

        if (first != null && taskPanelVisibleInSnapshot(first)) {
            return ensureTaskListExpanded();
        }

        if (first != null) {
            int[] sign = findOcrTextCenter(first, "签到领金币");
            if (sign == null) sign = findOcrTextCenter(first, "签到领淘金币");
            if (sign != null) {
                lastDailySignX = sign[0];
                lastDailySignY = sign[1];
                shellTap(sign[0], sign[1]);
                AppState.log("通过单帧 OCR 点击每日首次‘签到领金币’");
                AppState.log("已处理每日首次签到；等待 5 秒让按钮原地切换为‘赚更多金币’");
                return waitAndOpenMoreAfterDailySign();
            }

            int[] more = findMoreCoinsVisualPoint(first);
            int score = coinHomeEvidenceScore(first);
            if (more == null) more = findCoinHomeCenterActionFallback(first, score);
            if (more != null) {
                coinHomeGeometryFallbackUsed = true;
                shellTap(more[0], more[1]);
                AppState.log("通过单帧快速 OCR/首页特征点击‘赚更多金币’: ("
                        + more[0] + ", " + more[1] + ")");
                sleep(1500);
                if (isTaskPanelVisibleForNavigation()) return ensureTaskListExpanded();
            }
        }

        // Only use the slower retry path when the first fast frame was genuinely inconclusive.
        return clickMoreCoinsAndConfirm(3);
    }

    /**
     * 签到之后专用等待阶段。中央大按钮通常在原位置从“签到领金币”变成“赚更多金币”。
     * 不重启淘宝，也不重新走首页导航；最长等待约 15 秒。
     */
    /**
     * 签到之后专用阶段：按用户实测固定等待 5 秒，随后立即点击原地切换出的“赚更多金币”。
     * 不做 15 秒轮询，也不重启淘宝。
     */
    private boolean waitAndOpenMoreAfterDailySign() throws Exception {
        AppState.log("签到完成，固定等待 5 秒让按钮切换为‘赚更多金币’");
        sleep(5000);
        if (stopRequested) return false;

        if (isTaskPanelVisibleForNavigation()) {
            return ensureTaskListExpanded();
        }

        // 第一优先：通过 OCR 找“赚更多金币”。
        if (clickMoreCoinsOnce()) {
            AppState.log("签到后已点击‘赚更多金币’，等待任务弹窗");
            sleep(2500);
            if (isTaskPanelVisibleForNavigation()) {
                return ensureTaskListExpanded();
            }
        }

        // UC WebView 可能已经换字但 OCR 仍是旧帧。中央按钮位置不变，
        // 因此只在“刚刚明确完成签到”的前提下，使用签到按钮原坐标兜底点击一次。
        if (lastDailySignX > 0 && lastDailySignY > 0) {
            AppState.log("5 秒后仍未识别到‘赚更多金币’，按签到按钮原位置兜底点击一次");
            shellTap(lastDailySignX, lastDailySignY);
            sleep(2500);
            if (isTaskPanelVisibleForNavigation()) {
                return ensureTaskListExpanded();
            }
        }

        // 最后只再做一次正常识别点击，不继续长时间等待。
        if (clickMoreCoinsOnce()) {
            AppState.log("签到后第二次识别到‘赚更多金币’，已点击");
            sleep(2500);
            if (isTaskPanelVisibleForNavigation()) {
                return ensureTaskListExpanded();
            }
        }

        AppState.log("签到后 5 秒流程结束，仍未确认任务弹窗；保持当前页面交给外层重试");
        return isTaskPanelVisibleForNavigation() && ensureTaskListExpanded();
    }

    private boolean clickMoreCoinsAndConfirm(int maxAttempts) throws Exception {
        for (int i = 1; i <= maxAttempts && !stopRequested; i++) {
            if (isTaskPanelVisibleForNavigation()) {
                return ensureTaskListExpanded();
            }

            if (clickMoreCoinsOnce()) {
                sleep(1500);
                if (isTaskPanelVisibleForNavigation()) {
                    return ensureTaskListExpanded();
                }
                AppState.log("已点击‘赚更多金币’，但暂未确认任务弹窗，继续等待 (" + i + "/" + maxAttempts + ")");
                sleep(450);
            } else {
                AppState.log("等待‘赚更多金币’入口加载 (" + i + "/" + maxAttempts + ")");
                sleep(500);
            }
        }
        return isTaskPanelVisibleForNavigation() && ensureTaskListExpanded();
    }

    /**
     * 只负责点击一次“赚更多金币”，不做重启、不做页面跳转。
     *
     * 新版淘金币把中央按钮绘制在 UC WebView/Canvas 中，澎湃 OS 3 上经常出现：
     * 1) 屏幕肉眼已经显示“赚更多金币”；
     * 2) 页面没有可供本工具使用的结构化控件信息；
     * 3) ML Kit 把“金币”中的某一个字识别错，导致精确 contains 失败。
     *
     * 因此这里采用两层策略：OCR 模糊文字 -> 淘金币首页多特征确认后的
     * 中央按钮安全区域兜底。第二层每次 openTaskPanel() 最多执行一次。
     */
    private boolean clickMoreCoinsOnce() throws Exception {
        OcrEngine.Snapshot snap = null;
        try {
            snap = ocr.captureNavigation();
            if (dismissBlockingPopupIfPresent(snap, "淘金币首页")) {
                sleep(800);
                return false;
            }
            int[] p = findMoreCoinsVisualPoint(snap);
            if (p != null) {
                shellTap(p[0], p[1]);
                AppState.log("通过 OCR 模糊匹配点击淘金币首页“赚更多金币”: (" + p[0] + ", " + p[1] + ")");
                return true;
            }

            if (!coinHomeGeometryFallbackUsed) {
                int score = coinHomeEvidenceScore(snap);
                int[] fallback = findCoinHomeCenterActionFallback(snap, score);
                if (fallback != null) {
                    coinHomeGeometryFallbackUsed = true;
                    shellTap(fallback[0], fallback[1]);
                    AppState.log("OCR 未精确读出“赚更多金币”，但淘金币首页特征分=" + score
                            + "；按中央大按钮安全位置兜底点击一次: ("
                            + fallback[0] + ", " + fallback[1] + ")");
                    return true;
                }
            }
        } catch (Throwable e) {
            AppState.log("赚更多金币 OCR 定位失败: " + e.getMessage());
        }

        // 识别不到时输出一条精简诊断，下一份日志可以直接看到 ML Kit 实际读到了什么。
        if (snap != null) {
            AppState.log("未定位到“赚更多金币”；首页 OCR 摘要: " + summarizeCoinHomeOcr(snap));
        }
        return false;
    }

    /**
     * 从 OCR 行中寻找中央“赚更多金币”。允许 1 个汉字被 ML Kit 识别错，
     * 并要求命中位置处于淘金币首页中央操作带，避免商品区其它“金币”文字造成误点。
     */
    private int[] findMoreCoinsVisualPoint(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        int minY = (int) (snap.height * 0.18f);
        int maxY = (int) (snap.height * 0.36f);
        for (OcrEngine.OcrRow row : snap.rows) {
            int y = row.bounds.centerY();
            if (y < minY || y > maxY) continue;
            String text = normalizeOcr(row.text);
            int earnPos = text.indexOf("赚");
            int morePos = text.indexOf("更多");
            // 要求“赚”出现在“更多”之前且距离很近，避免把任务弹窗标题
            // “更多金币等你赚”误当成首页按钮。
            boolean orderedEarnMore = earnPos >= 0 && morePos > earnPos && (morePos - earnPos) <= 3;
            boolean likely = text.contains("赚更多金币")
                    || (orderedEarnMore && fuzzyContains(text, "赚更多金币", 1))
                    || (orderedEarnMore && text.contains("金"));
            if (!likely) continue;

            // 同一水平行可能被 mergeRows 合并成“玩游戏 赚更多金币 找好友”，
            // 这种情况下整行中心正好仍落在中央大按钮。
            int x = row.bounds.centerX();
            if (row.bounds.width() > snap.width * 0.55f) x = snap.width / 2;
            x = Math.max(24, Math.min(snap.width - 24, x));
            return new int[]{x, y};
        }
        return null;
    }

    /**
     * 只根据“淘金币首页”独有的多个视觉文字共同打分。
     * 单独出现“淘金币”或“搜索”绝不会触发几何兜底。
     */
    private int coinHomeEvidenceScore(OcrEngine.Snapshot snap) {
        if (snap == null) return 0;
        String all = normalizeOcr(snap.allText());
        int score = 0;
        if (all.contains("淘金币") || fuzzyContains(all, "淘金币", 1)) score += 3;
        if (all.contains("搜索抵钱") || (all.contains("搜索") && all.contains("金币额外抵扣"))) score += 2;
        if (all.contains("玩游戏")) score += 1;
        if (all.contains("找好友")) score += 1;
        if (all.contains("快速赚") || all.contains("明天可领") || all.contains("后天")) score += 1;
        if (all.contains("铂金会员") || all.contains("足迹加抵")) score += 1;
        return score;
    }

    /**
     * 当中央文字本身 OCR 失败时，优先使用“玩游戏/找好友”同一行推断按钮 y；
     * 若侧边文字也没读到，则只有首页特征分足够高时才使用屏幕比例坐标。
     * 用户当前 921x2048 截图中按钮中心约为 (0.50W, 0.265H)。
     */
    private int[] findCoinHomeCenterActionFallback(OcrEngine.Snapshot snap, int score) {
        if (snap == null || score < COIN_HOME_EVIDENCE_THRESHOLD) return null;
        String all = normalizeOcr(snap.allText());
        // 如果 OCR 已经读到任务弹窗特征，即使 isTaskPanelVisibleForNavigation() 刚才因为
        // 截图时序没确认成功，也绝不再按首页中央坐标。
        if (all.contains("今日速赚") || all.contains("更多金币等你赚")
                || all.contains("完成下方任务得额外金币") || all.contains("快速赚奖励已拿完")) {
            return null;
        }
        int minY = (int) (snap.height * 0.18f);
        int maxY = (int) (snap.height * 0.36f);
        int gameY = -1;
        int friendY = -1;

        for (OcrEngine.OcrRow row : snap.rows) {
            int y = row.bounds.centerY();
            if (y < minY || y > maxY) continue;
            String t = normalizeOcr(row.text);
            if (t.contains("玩游戏")) gameY = y;
            if (t.contains("找好友")) friendY = y;
            if (t.contains("玩游戏") && t.contains("找好友")) {
                return new int[]{snap.width / 2, y};
            }
        }

        if (gameY > 0 && friendY > 0 && Math.abs(gameY - friendY) <= 90) {
            return new int[]{snap.width / 2, (gameY + friendY) / 2};
        }
        if (gameY > 0) return new int[]{snap.width / 2, gameY};
        if (friendY > 0) return new int[]{snap.width / 2, friendY};

        // 最后一级只在达到多特征阈值的强首页证据下启用。
        return new int[]{snap.width / 2, (int) (snap.height * 0.265f)};
    }

    private boolean fuzzyContains(String text, String target, int maxDistance) {
        if (text == null || target == null) return false;
        String a = normalizeOcr(text);
        String b = normalizeOcr(target);
        if (a.contains(b)) return true;
        if (b.isEmpty() || a.isEmpty()) return false;

        int minLen = Math.max(1, b.length() - maxDistance);
        int maxLen = b.length() + maxDistance;
        for (int len = minLen; len <= maxLen; len++) {
            if (len > a.length()) continue;
            for (int i = 0; i + len <= a.length(); i++) {
                if (editDistance(a.substring(i, i + len), b, maxDistance) <= maxDistance) return true;
            }
        }
        return false;
    }

    /** 带上限的 Levenshtein；超过 limit 时提前退出。 */
    private int editDistance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) return limit + 1;
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private boolean looksLikeCoinHomeSnapshot(OcrEngine.Snapshot snap) {
        if (snap == null) return false;
        if (findMoreCoinsVisualPoint(snap) != null) return true;
        String all = normalizeOcr(snap.allText());
        if (all.contains("签到领金币") || all.contains("签到领淘金币")) return true;
        return coinHomeEvidenceScore(snap) >= COIN_HOME_EVIDENCE_THRESHOLD;
    }

    private String summarizeCoinHomeOcr(OcrEngine.Snapshot snap) {
        if (snap == null) return "<无截图>";
        StringBuilder sb = new StringBuilder();
        int minY = (int) (snap.height * 0.08f);
        int maxY = (int) (snap.height * 0.42f);
        for (OcrEngine.OcrRow row : snap.rows) {
            int y = row.bounds.centerY();
            if (y < minY || y > maxY) continue;
            String t = normalizeOcr(row.text);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
            if (sb.length() > 220) break;
        }
        return sb.length() == 0 ? "<顶部区域无文字>" : sb.toString();
    }

    /**
     * 处理淘金币首页每天第一次出现的“签到领金币/签到领淘金币”大按钮。
     * 返回 true 表示本轮确实执行了签到点击；false 表示当前无需签到。
     */
    private boolean clickDailySignInIfPresent() throws Exception {
        lastDailySignX = -1;
        lastDailySignY = -1;
        if (!TB.equals(currentPackage())) return false;

        try {
            // One upper-screen frame is enough to decide panel/sign-in state and locate the button.
            OcrEngine.Snapshot snap = ocr.captureNavigation();
            if (taskPanelVisibleInSnapshot(snap)) return false;

            int[] p = findOcrTextCenter(snap, "签到领金币");
            if (p == null) p = findOcrTextCenter(snap, "签到领淘金币");
            if (p != null) {
                lastDailySignX = p[0];
                lastDailySignY = p[1];
                shellTap(p[0], p[1]);
                AppState.log("通过单帧 OCR 点击每日首次‘签到领金币’");
                return true;
            }
        } catch (Throwable e) {
            AppState.log("签到 OCR 定位失败: " + e.getMessage());
        }
        return false;
    }

    private int[] findOcrTextCenter(OcrEngine.Snapshot snap, String needle) {
        if (snap == null) return null;
        String normalizedNeedle = normalizeOcr(needle);
        for (OcrEngine.OcrRow row : snap.rows) {
            if (!normalizeOcr(row.text).contains(normalizedNeedle)) continue;
            Rect b = row.bounds;
            int x = b.centerX();
            int y = b.centerY();
            if (x > 0 && y > 0 && x < snap.width && y < snap.height) return new int[]{x, y};
        }
        return null;
    }

    /**
     * 打开“今日速赚”后，尽量点击一次“展开”；但是否可以进入主循环，
     * 不再依赖某个单一的“展开/收起”文字或固定 Activity 名。
     *
     * 淘宝 UC WebView 的实际表现有两种都属于可执行状态：
     * 1) “快速赚”区域完全展开；
     * 2) 快速赚区域收起，但下方“更多专享福利”的带进度任务和右侧操作按钮已经可见。
     *
     * v1.3 把“真正展开”判得过严，用户截图中页面已经可执行仍被判失败。
     * v1.4 改成“可见任务行本身就是成功证据”。
     */
    private boolean ensureTaskListExpanded() throws Exception {
        if (!TB.equals(currentPackage())) return false;

        // v2.2: “更多金币等你赚  展开”这张折叠卡的优先级必须高于“可信任务面板”。
        // v2.1 先做可信面板判断，导致上方今日速赚已经可见时提前 return，实际从未点击底部展开。
        for (int attempt = 1; attempt <= 3 && !stopRequested; attempt++) {
            OcrEngine.Snapshot snap;
            try {
                snap = ocr.captureTaskPanel();
            } catch (Throwable e) {
                AppState.log("任务面板 OCR 失败: " + e.getMessage());
                sleep(420);
                continue;
            }

            if (dismissBlockingPopupIfPresent(snap, "任务面板")) {
                sleep(800);
                continue;
            }

            if (attempt == 1) logTaskPanelVariant(snap);

            // 第一优先：只要折叠卡还在，就必须先展开。即使 ML Kit 漏掉“展开”两个字，
            // 也用“更多金币等你赚”这一行作为锚点，点击同一行右侧的安全区域。
            int[] expand = findExpandTaskPanelPoint(snap);
            if (expand != null) {
                shellTap(expand[0], expand[1]);
                AppState.log("检测到‘更多金币等你赚/展开’折叠卡，优先点击展开: ("
                        + expand[0] + ", " + expand[1] + ")");
                sleep(720);

                OcrEngine.Snapshot after = ocr.captureTaskPanel();
                int[] stillCollapsed = findExpandTaskPanelPoint(after);
                if (stillCollapsed == null && TaskParser.isTrustedTaskContext(after.rows, after.height)) {
                    AppState.log("展开控件已消失，且任务面板认证通过；进入主循环");
                    return true;
                }
                if (stillCollapsed != null) {
                    AppState.log("展开控件仍可见，等待 UC WebView 刷新后重试 (" + attempt + "/3)");
                    sleep(420);
                    continue;
                }
                if (TaskParser.isTrustedTaskContext(after.rows, after.height)) {
                    AppState.log("展开后已确认可信任务面板；允许继续识别/下滑");
                    return true;
                }
                if (taskPanelVisibleInSnapshot(after)) {
                    AppState.log("展开后任务弹窗仍在，但任务列表特征尚未稳定；等待下一帧 ("
                            + attempt + "/3)");
                    sleep(420);
                    continue;
                }
            }

            // 只有确认不存在折叠卡，才允许“可信任务面板”直接进入主循环。
            if (TaskParser.isTrustedTaskContext(snap.rows, snap.height)) {
                AppState.log("未看到‘更多金币等你赚’折叠卡，任务面板认证通过；可直接识别/下滑");
                return true;
            }

            AppState.log("第 " + attempt + "/3 次未确认展开控件或可执行任务列表，短暂等待页面刷新");
            sleep(420);
        }

        boolean ready = isExpandedTaskListVisible();
        if (ready) AppState.log("最终确认：任务面板已可执行");
        return ready;
    }

    /**
     * “任务列表可执行”的视觉判定。
     *
     * 这里刻意只要求淘宝包名，不再强制 resumed Activity 必须恰好叫 TMSActivity。
     * 淘宝不同版本/ROM 上活动名会变化，而真实截图 OCR 才是当前页面的最终依据。
     */
    private boolean isExpandedTaskListVisible() {
        ForegroundState fg = currentForeground();
        if (!fg.isPackage(TB)) return false;

        // v2.1：不把单独的“收起”或任意 x/y 当成功证据，同时允许金币圆标型任务面板。
        // UC WebView 页面只以真实截图 OCR 的组合特征作为任务列表认证依据。
        try {
            OcrEngine.Snapshot snap = ocr.captureTaskPanel();
            if (findExpandTaskPanelPoint(snap) != null) {
                AppState.log("仍看到‘更多金币等你赚/展开’折叠卡，不能把当前画面当作已展开任务列表");
                return false;
            }
            if (TaskParser.isTrustedTaskContext(snap.rows, snap.height)) {
                List<TaskParser.TaskItem> tasks = TaskParser.parse(snap.rows, snap.height);
                AppState.log("OCR 任务上下文认证通过：可见带进度任务 " + tasks.size()
                        + " 个 / 动作行 " + TaskParser.countActionRows(snap.rows));
                return true;
            }
        } catch (Throwable e) {
            AppState.log("任务区域 OCR 强认证失败: " + e.getMessage());
        }
        return false;
    }

    private void returnToTaskList() throws Exception {
        AppState.log("开始返回任务列表");
        long deadline = SystemClock.elapsedRealtime() + 35_000L;
        int backs = 0;
        int foreignBounceCount = 0;

        while (!stopRequested && SystemClock.elapsedRealtime() < deadline && backs < 6) {
            ForegroundState fg = currentForeground();
            if (!fg.valid()) {
                sleep(500);
                continue;
            }
            if (fg.activityContains("Ext2ContainerActivity")) {
                sleep(500);
                continue;
            }

            AppState.log("返回状态: " + fg);

            if (fg.isPackage(TB) && isExpandedTaskListVisible()) {
                AppState.log("当前已经是任务列表画面，停止继续返回");
                return;
            }

            if (!fg.isPackage(TB)) {
                foreignBounceCount++;
                AppState.log("当前在其他应用，拉回淘宝: " + fg.packageName
                        + " (跨应用回跳 " + foreignBounceCount + "/2)");

                // 闲鱼/淘宝广告深链可能形成“闲鱼 -> 淘宝商品 -> back -> 闲鱼”的环。
                // 第二次回到外部 App 时不再继续猜 Back 栈，直接 force-stop 淘宝并重建淘金币页。
                if (foreignBounceCount >= 2) {
                    AppState.log("检测到跨应用深链来回跳转，放弃当前返回栈，强制重建淘金币任务页");
                    if (!enterTaskList(true)) throw new IllegalStateException("跨应用深链后恢复任务列表失败");
                    return;
                }

                launchTaobao();
                sleep(2000);
                if (tapOcrTextContains("跳过", true)) {
                    AppState.log("通过 OCR 点击淘宝启动页‘跳过’");
                    sleep(1800);
                }
                continue;
            }

            if (fg.activityContains("com.taobao.themis.container.app.TMSActivity") && isCoinHomeVisibleForNavigation()) {
                if (openTaskPanel() && isExpandedTaskListVisible()) {
                    AppState.log("已重新打开并确认任务列表");
                    return;
                }
            }

            if (fg.activityContains("com.taobao.tao.welcome.Welcome")) {
                AppState.log("返回到了淘宝首页而不是任务列表，执行完整恢复");
                if (!enterTaskList(true)) throw new IllegalStateException("返回淘宝首页后恢复失败");
                return;
            }

            AppState.log("点击系统后退");
            shellBack();
            backs++;
            sleep(500);
        }

        if (stopRequested) return;
        AppState.log("常规返回未能恢复任务列表，执行完整恢复");
        if (!enterTaskList(true)) throw new IllegalStateException("无法恢复任务列表");
    }


    /**
     * 主任务结束后的统一收尾阶段。
     *
     * 1) 无论新旧版，先强制把任务列表滑回最上方；
     * 2) 在顶部重新检查是否还有未完成且不在黑名单中的任务；
     * 3) 若有，则返回主循环继续执行；
     * 4) 若没有，才开始领取旧版“今日累计奖励”或当前可见的领取按钮。
     *
     * @return true 表示顶部复查发现了新的可执行任务，调用方应继续 mainLoop。
     */
    private boolean finalRewardSweep() throws Exception {
        if (stopRequested) return false;
        AppState.status = "收尾复查";
        AppState.log("开始收尾：先强制返回任务面板顶部，再复查未完成任务");

        if (!TB.equals(currentPackage())) {
            AppState.log("收尾时已离开淘宝，先恢复淘金币任务页");
            if (!enterTaskList(true)) {
                AppState.log("收尾恢复失败，跳过领奖并结束");
                return false;
            }
        }

        OcrEngine.Snapshot topSnap = rewindTaskPanelToTop();
        if (stopRequested || topSnap == null) return false;

        // 顶部复查改用任务列表专用高分辨率 OCR，尽量减少形近字/进度漏识别。
        // 最多允许触发两轮，避免某个失败任务导致“到底 -> 回顶部 -> 再到底”的死循环。
        OcrEngine.Snapshot taskRecheckSnap = ocr.captureTaskList();
        List<TaskParser.TaskItem> topTasks = TaskParser.parse(taskRecheckSnap.rows, taskRecheckSnap.height);
        AppState.log("收尾复查：顶部识别到 " + topTasks.size() + " 个带进度任务");
        TaskParser.TaskItem unfinished = null;
        for (TaskParser.TaskItem task : topTasks) {
            if (TaskParser.skip(task, taskRules)) continue;
            if (attempted.getOrDefault(task.name, 0) >= 2) {
                AppState.log("收尾复查发现未完成任务但已达到尝试上限，跳过: " + task.name);
                continue;
            }
            unfinished = task;
            break;
        }

        if (unfinished != null && finalTopRecheckRounds < 2) {
            finalTopRecheckRounds++;
            AppState.log("收尾复查发现仍有可执行任务: " + unfinished.name
                    + "；返回主循环继续执行 (" + finalTopRecheckRounds + "/2)");
            AppState.status = "识别任务中";
            return true;
        }

        if (unfinished != null) {
            AppState.log("收尾复查仍看到未完成任务，但已达到复查轮次上限；为避免循环，继续领奖并结束");
        } else {
            AppState.log("收尾复查：顶部未发现黑名单外的未完成任务，开始检查奖励");
        }

        AppState.status = "收尾领奖";
        logTaskPanelVariant(topSnap);

        // 经典版只有明确看到“今日累计奖励”时才点击三个奖励槽。
        // 不再因为固定标题“赚金币抵钱”存在就用兜底坐标点击，避免在列表底部误点第一条任务。
        if (hasClassicDailyRewardHeader(topSnap)) {
            tapClassicRewardSlots(topSnap);
        }

        int claimed = 0;
        String lastClaimFrame = "";
        int unchanged = 0;
        for (int i = 0; i < 8 && !stopRequested; i++) {
            OcrEngine.Snapshot snap = ocr.captureTaskPanel();
            int[] claim = findVisibleClaimPoint(snap);
            if (claim == null) break;

            String frame = compactPanelSignature(snap);
            if (frame.equals(lastClaimFrame)) unchanged++; else unchanged = 0;
            lastClaimFrame = frame;
            if (unchanged >= 2) {
                AppState.log("领奖按钮画面连续未变化，停止重复点击");
                break;
            }

            shellTap(claim[0], claim[1]);
            claimed++;
            AppState.log("收尾领奖：点击可领取奖励 (" + claim[0] + ", " + claim[1] + ")");
            sleep(850);
        }

        AppState.log(claimed > 0
                ? "收尾领奖完成：额外点击可领取按钮 " + claimed + " 次"
                : "收尾领奖完成：未发现额外可领取按钮");
        AppState.status = "识别任务中";
        return false;
    }

    /**
     * 收尾阶段始终至少执行一次向上滑动，不再依赖固定标题判断“已经到顶”。
     * 经典版的“赚金币抵钱”标题在列表滚到底时仍然可见，旧逻辑因此会误判已经在顶部。
     */
    private OcrEngine.Snapshot rewindTaskPanelToTop() throws Exception {
        String lastSignature = "";
        int unchanged = 0;
        OcrEngine.Snapshot snap = null;

        for (int i = 1; i <= 8 && !stopRequested; i++) {
            snap = ocr.captureTaskPanel();
            String all = normalizeOcr(snap.allText());

            // 第一轮无条件滑一下，确保经典版不会因为固定标题而直接进入领奖。
            if (i > 1 && isStrongRewardPanelTop(all)) {
                AppState.log("已确认回到任务面板顶部");
                break;
            }

            String sig = compactPanelSignature(snap);
            if (!sig.isEmpty() && sig.equals(lastSignature)) unchanged++; else unchanged = 0;
            lastSignature = sig;
            if (i > 2 && unchanged >= 2) {
                AppState.log("连续两次向上滑动后画面未变化，按当前画面视为顶部");
                break;
            }

            AppState.log("收尾：向上返回任务面板顶部 (" + i + "/8)");
            shellSwipeToTop(snap.width, snap.height);
            sleep(650);
        }

        if (stopRequested) return snap;
        snap = ocr.captureTaskPanel();
        AppState.log("已回到顶部，开始复查任务与奖励");
        return snap;
    }

    private boolean isStrongRewardPanelTop(String normalizedAll) {
        if (normalizedAll == null) return false;
        // 旧版顶部的强标志：今日累计奖励。
        if (normalizedAll.contains("今日累计奖励")) return true;
        // 新版顶部的强标志：完成下方任务得额外金币。
        return normalizedAll.contains("完成下方任务得额外金币")
                || (normalizedAll.contains("完成下方任务") && normalizedAll.contains("金币"));
    }

    private boolean hasClassicDailyRewardHeader(OcrEngine.Snapshot snap) {
        return snap != null && normalizeOcr(snap.allText()).contains("今日累计奖励");
    }

    private boolean isRewardPanelTop(String normalizedAll) {
        if (normalizedAll == null) return false;
        return normalizedAll.contains("今日累计奖励")
                || normalizedAll.contains("赚金币抵钱")
                || normalizedAll.contains("今日速赚")
                || (normalizedAll.contains("今日速") && normalizedAll.contains("淘金币"))
                || normalizedAll.contains("完成下方任务得额外金币")
                || (normalizedAll.contains("完成下方任务") && normalizedAll.contains("金币"));
    }

    private boolean isClassicRewardPanel(OcrEngine.Snapshot snap) {
        if (snap == null) return false;
        String all = normalizeOcr(snap.allText());
        return all.contains("今日累计奖励") || all.contains("赚金币抵钱");
    }

    private void logTaskPanelVariant(OcrEngine.Snapshot snap) {
        if (snap == null) return;
        String all = normalizeOcr(snap.allText());
        if (all.contains("今日累计奖励") || all.contains("赚金币抵钱")) {
            AppState.log("界面识别：经典‘赚金币抵钱’任务面板");
        } else if (all.contains("今日速赚") || (all.contains("今日速") && all.contains("淘金币"))
                || (all.contains("完成下方任务") && all.contains("金币"))) {
            AppState.log("界面识别：新版‘今日速赚’任务面板");
        } else {
            AppState.log("界面识别：未明确区分新旧版，继续使用通用 OCR 流程");
        }
    }

    private void tapClassicRewardSlots(OcrEngine.Snapshot snap) throws Exception {
        int anchorY = -1;
        for (OcrEngine.OcrRow row : snap.rows) {
            if (normalizeOcr(row.text).contains("今日累计奖励")) {
                anchorY = row.bounds.centerY();
                break;
            }
        }
        if (anchorY < 0) {
            AppState.log("经典版收尾：未明确识别到‘今日累计奖励’，为避免误点任务，不使用坐标兜底");
            return;
        }

        int[] xs = new int[]{
                (int) (snap.width * 0.43f),
                (int) (snap.width * 0.66f),
                (int) (snap.width * 0.87f)
        };
        AppState.log("经典版收尾：检查‘今日累计奖励’三个奖励槽");
        for (int x : xs) {
            if (stopRequested) return;
            shellTap(x, anchorY);
            sleep(500);
        }
    }

    /**
     * 查找明确的领取动作。旧版“立即领取”位于顶部奖励条中部，
     * 普通“领取奖励”位于任务行右侧，两者采用不同的安全点击 x。
     */
    private int[] findVisibleClaimPoint(OcrEngine.Snapshot snap) {
        if (snap == null) return null;
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (t.contains("立即领取")) {
                int x;
                if (t.length() <= 8) x = row.bounds.centerX();
                else x = (int) (snap.width * 0.43f);
                return new int[]{Math.max(24, Math.min(snap.width - 24, x)), row.bounds.centerY()};
            }
        }
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (t.contains("领取奖励")) {
                int x = Math.max((int) (snap.width * 0.86f), row.bounds.right - 8);
                return new int[]{Math.min(snap.width - 24, x), row.bounds.centerY()};
            }
        }
        return null;
    }

    private void shellSwipeToTop(int width, int height) throws Exception {
        int x = Math.max(120, (int) (width * 0.50f));
        int y1 = (int) (height * 0.30f);
        int y2 = (int) (height * 0.82f);
        ShizukuShell.Result r = ShizukuShell.exec(
                "input swipe " + x + " " + y1 + " " + x + " " + y2 + " 650"
        );
        if (!r.ok()) throw new IllegalStateException("返回任务顶部失败: " + r.output);
    }

    private String compactPanelSignature(OcrEngine.Snapshot snap) {
        if (snap == null) return "";
        String all = normalizeOcr(snap.allText());
        if (all.length() > 220) all = all.substring(0, 220);
        return all;
    }

    private void shellTap(int x, int y) throws Exception {
        ShizukuShell.Result r = ShizukuShell.exec("input tap " + x + " " + y);
        if (!r.ok()) throw new IllegalStateException("input tap 失败: " + r.output);
    }

    private void shellSwipeList(int width, int height) throws Exception {
        int x = Math.max(120, (int) (width * 0.278f));
        int y1 = (int) (height * 0.844f);
        int y2 = (int) (height * 0.281f);
        ShizukuShell.Result r = ShizukuShell.exec("input swipe " + x + " " + y1 + " " + x + " " + y2 + " 1000");
        if (!r.ok()) throw new IllegalStateException("列表滚动失败: " + r.output);
    }

    private String signature(List<TaskParser.TaskItem> tasks) {
        StringBuilder sb = new StringBuilder();
        for (TaskParser.TaskItem t : tasks) sb.append(t.name).append('|');
        return sb.toString();
    }

    private boolean containsVerification(String text) {
        if (text == null) return false;
        return text.contains("请拖动下方滑块完成验证") || text.contains("验证失败") || text.contains("通过验证以确保正常访问");
    }

    private boolean isCoinHomeVisibleForNavigation() {
        if (!TB.equals(currentPackage())) return false;
        try {
            OcrEngine.Snapshot snap = ocr.captureNavigation();
            boolean hit = looksLikeCoinHomeSnapshot(snap);
            if (hit) {
                AppState.log("OCR 已确认当前为淘金币首页（特征分=" + coinHomeEvidenceScore(snap) + ")");
            }
            return hit;
        } catch (Throwable e) {
            AppState.log("淘金币首页 OCR 确认失败: " + e.getMessage());
            return false;
        }
    }

    private boolean tapOcrTextContains(String needle, boolean preferRight) {
        try {
            OcrEngine.Snapshot snap = ocr.captureNavigation();
            int[] p = findTextPoint(snap, needle, preferRight);
            if (p == null) return false;
            shellTap(p[0], p[1]);
            return true;
        } catch (Throwable e) {
            AppState.log("OCR 导航兜底失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * v1.1 通用遮挡弹窗恢复。
     *
     * 不尝试维护“广告名称黑名单”，因为淘宝弹窗样式/文案变化非常频繁。
     * 这里使用两类证据：
     * 1) 高特异弹窗文案（组件、额外抵扣、出行、闪购、抵金等）；
     * 2) 屏幕中部出现明显的单一 CTA，而背景仍能看到淘金币/任务页上下文。
     *
     * 命中后只按一次系统 Back，再由下一张真实截图验证页面是否恢复。
     * Back 比点击未知位置的 X 更安全，可避免 X 位置变化时误触广告内容。
     */
    private boolean dismissBlockingPopupIfPresent(OcrEngine.Snapshot snap, String stage) {
        if (snap == null || stopRequested) return false;
        if (!TB.equals(currentPackage())) return false;

        String all = normalizeOcr(snap.allText());
        int score = 0;

        // 高特异文案：这些内容通常只会出现在覆盖式促销/引导弹窗中。
        String[] strong = new String[]{
                "已尝试添加组件", "再次添加", "前往桌面", "添加失败",
                "一键享受金币额外抵扣", "金币额外抵扣", "你加购的",
                "出行玩乐", "订票订酒店", "闪购福利"
        };
        for (String k : strong) {
            if (all.contains(normalizeOcr(k))) score += 2;
        }
        // 这些词在普通淘宝页面也可能出现，只作为弱证据，不能单独触发 Back。
        String[] weak = new String[]{"淘宝闪购", "加抵金", "抵金", "额外抵", "去看看"};
        for (String k : weak) {
            if (all.contains(normalizeOcr(k))) score += 1;
        }

        // 背景仍像淘金币/任务页时，中间出现大 CTA 更像是“盖在原页面上的弹窗”。
        boolean backgroundContext = looksLikeCoinHomeSnapshot(snap)
                || taskPanelVisibleInSnapshot(snap)
                || TaskParser.isTrustedTaskContext(snap.rows, snap.height);
        boolean centeredCta = false;
        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            if (t.isEmpty()) continue;
            int cx = row.bounds.centerX();
            int cy = row.bounds.centerY();
            boolean inCenter = cx > snap.width * 0.22f && cx < snap.width * 0.78f
                    && cy > snap.height * 0.32f && cy < snap.height * 0.82f;
            if (!inCenter) continue;
            boolean actionText = t.contains("去看看") || t.contains("前往") || t.contains("再次")
                    || t.contains("立即") || t.contains("一键") || t.contains("添加")
                    || t.contains("使用") || t.contains("查看") || t.contains("开启");
            if (actionText && row.bounds.width() > snap.width * 0.18f) {
                centeredCta = true;
                break;
            }
        }
        if (backgroundContext && centeredCta) score += 2;

        if (score < 3) return false;
        if (popupDismissCount >= 6) {
            AppState.log("疑似遮挡弹窗仍反复出现，但已达到本轮自动关闭上限；交给页面恢复流程处理");
            return false;
        }

        popupDismissCount++;
        AppState.log("检测到疑似遮挡弹窗（" + stage + "，证据分=" + score + "），优先使用系统返回关闭 ("
                + popupDismissCount + "/6)");
        ShizukuShell.exec("input keyevent KEYCODE_BACK");
        return true;
    }

    private String normalizeOcr(String text) {
        if (text == null) return "";
        return text.replace('額', '额')
                .replace('幣', '币')
                .replace('賺', '赚')
                .replace('獎', '奖')
                .replace('專', '专')
                .replace('領', '领')
                .replace('勵', '励')
                .replaceAll("\\s+", "")
                .replace("·", "")
                .trim();
    }

    private boolean isTaskPanelVisibleForNavigation() {
        if (!TB.equals(currentPackage())) return false;
        try {
            OcrEngine.Snapshot snap = ocr.captureNavigation();
            boolean hit = taskPanelVisibleInSnapshot(snap);
            if (hit) AppState.log("OCR 已确认‘今日速赚/更多金币’等任务弹窗已打开");
            return hit;
        } catch (Throwable e) {
            AppState.log("任务弹窗 OCR 确认失败: " + e.getMessage());
            return false;
        }
    }

    private boolean taskPanelVisibleInSnapshot(OcrEngine.Snapshot snap) {
        if (snap == null) return false;
        String all = normalizeOcr(snap.allText());
        boolean quickEarn = all.contains("今日速赚")
                || (all.contains("今日速") && all.contains("淘金币"));
        boolean rewardHeader = all.contains("完成下方任务得额外金币")
                || (all.contains("完成下方任务") && all.contains("金币"));
        boolean classic = all.contains("赚金币抵钱") || all.contains("今日累计奖励");
        return quickEarn
                || rewardHeader
                || all.contains("更多金币等你赚")
                || all.contains("今日快速赚奖励已拿完")
                || all.contains("快速赚奖励已拿完")
                || (all.contains("记得明天再来") && all.contains("展开"))
                || classic;
    }

    /**
     * v2.2 展开控件定位。
     *
     * 淘宝当前页面常把“更多金币等你赚”和“展开”分别绘制；缩放 OCR 后“展开”偶尔漏字。
     * 因此先找明确“展开”，再以高度特异的“更多金币等你赚”卡片作为锚点点击同一行右侧。
     * 这个兜底只用于已经确认打开淘金币任务弹窗后的阶段，不会在淘宝首页/商品页使用。
     */
    private int[] findExpandTaskPanelPoint(OcrEngine.Snapshot snap) {
        if (snap == null) return null;

        int[] exact = findTextPoint(snap, "展开", true);
        if (exact != null && exact[1] > snap.height * 0.42f) return exact;

        for (OcrEngine.OcrRow row : snap.rows) {
            String t = normalizeOcr(row.text);
            boolean anchor = t.contains("更多金币等你赚")
                    || (t.contains("更多金币") && t.contains("等你赚"));
            if (!anchor) continue;
            int y = row.bounds.centerY();
            if (y < snap.height * 0.50f || y > snap.height * 0.94f) continue;
            int x = Math.min(snap.width - 28, Math.max((int) (snap.width * 0.86f), row.bounds.right + 36));
            return new int[]{x, y};
        }
        return null;
    }

    private int[] findTextPoint(OcrEngine.Snapshot snap, String needle, boolean preferRight) {
        if (snap == null) return null;
        String n = normalizeOcr(needle);

        // Prefer an exact/near-exact ML Kit Element first. On some phones ML Kit merges two or
        // more shortcut labels into one Element (for example “领淘金币阿里拍卖”). Using the merged
        // Element's center can then click the shortcut to the right, so a long Element is handled
        // with substring-relative geometry instead of centerX().
        if (snap.elements != null) {
            for (OcrEngine.OcrRow element : snap.elements) {
                String t = normalizeOcr(element.text);
                if (!t.equals(n) && !(t.contains(n) && t.length() <= n.length() + 1)) continue;
                int[] point = pointInsideMatchedText(element.bounds, t, n, snap.width, snap.height, preferRight);
                if (point != null) return point;
            }
            for (OcrEngine.OcrRow element : snap.elements) {
                String t = normalizeOcr(element.text);
                if (!t.contains(n)) continue;
                int[] point = pointInsideMatchedText(element.bounds, t, n, snap.width, snap.height, preferRight);
                if (point != null) return point;
            }
        }

        for (OcrEngine.OcrRow row : snap.rows) {
            String rowText = normalizeOcr(row.text);
            if (!rowText.contains(n)) continue;
            int[] point = pointInsideMatchedText(row.bounds, rowText, n, snap.width, snap.height, preferRight);
            if (point != null) return point;
        }
        return null;
    }

    private int[] pointInsideMatchedText(Rect b, String fullText, String needle,
                                         int screenWidth, int screenHeight, boolean preferRight) {
        if (b == null || fullText == null || needle == null || needle.isEmpty()) return null;
        int hit = fullText.indexOf(needle);
        if (hit < 0) return null;

        int x;
        if (preferRight) {
            x = Math.min(screenWidth - 24, Math.max(b.right - 8, (int) (screenWidth * 0.84f)));
        } else if (fullText.length() > needle.length() + 1) {
            // Works for both merged Lines and merged Elements. Estimate where the target substring
            // sits inside the OCR bounding box. This is especially important for home shortcuts:
            // “领淘金币阿里拍卖红包签到” must click the first label, not the box center.
            float charCenter = hit + needle.length() * 0.5f;
            float ratio = charCenter / Math.max(1f, fullText.length());
            x = b.left + Math.round(b.width() * ratio);
        } else {
            x = b.centerX();
        }

        x = Math.max(24, Math.min(screenWidth - 24, x));
        int y = b.centerY();
        if (x <= 0 || y <= 0 || x >= screenWidth || y >= screenHeight) return null;
        return new int[]{x, y};
    }

    private String currentPackage() {
        return currentForeground().packageName;
    }

    private ForegroundState currentForeground() {
        String[] commands = new String[]{
                "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' | head -n 1",
                "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1",
                "dumpsys activity top | grep 'ACTIVITY ' | head -n 1"
        };
        Pattern componentPattern = Pattern.compile("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)");
        for (String command : commands) {
            try {
                ShizukuShell.Result r = ShizukuShell.exec(command);
                Matcher m = componentPattern.matcher(r.output);
                if (!m.find()) continue;
                String pkg = m.group(1);
                String act = m.group(2);
                if (act != null && act.startsWith(".")) act = pkg + act;
                return new ForegroundState(pkg, act);
            } catch (Throwable ignored) {}
        }
        return new ForegroundState(null, null);
    }

    private boolean cancelBrowserOpenDialogIfPresent() {
        try {
            OcrEngine.Snapshot snap = ocr.capture();
            String all = normalizeOcr(snap.allText());
            if (!all.contains("浏览器打开")) return false;
            for (OcrEngine.OcrRow row : snap.rows) {
                if (!normalizeOcr(row.text).contains("取消")) continue;
                Rect r = row.bounds;
                shellTap(r.centerX(), r.centerY());
                AppState.log("OCR 检测到‘浏览器打开’弹窗，已点击取消");
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void shellBack() throws Exception {
        ShizukuShell.Result r = ShizukuShell.exec("input keyevent KEYCODE_BACK");
        if (!r.ok()) throw new IllegalStateException("系统后退失败: " + r.output);
    }

    private void sleep(long ms) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + ms;
        while (!stopRequested && SystemClock.elapsedRealtime() < end) {
            Thread.sleep(Math.min(250, Math.max(1, end - SystemClock.elapsedRealtime())));
        }
    }

    private static final class ForegroundState {
        final String packageName;
        final String activityName;

        ForegroundState(String packageName, String activityName) {
            this.packageName = packageName;
            this.activityName = activityName;
        }

        boolean valid() {
            return packageName != null && !packageName.isEmpty();
        }

        boolean isPackage(String pkg) {
            return pkg != null && pkg.equals(packageName);
        }

        boolean activityContains(String text) {
            return text != null && activityName != null && activityName.contains(text);
        }

        boolean isTaobaoTaskContainer() {
            return isPackage(TB) && activityContains("com.taobao.themis.container.app.TMSActivity");
        }

        @Override public String toString() {
            return String.valueOf(packageName) + "--" + String.valueOf(activityName);
        }
    }

}

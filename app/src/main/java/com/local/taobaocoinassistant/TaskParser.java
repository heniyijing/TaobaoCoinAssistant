package com.local.taobaocoinassistant;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TaskParser {
    // 仍兼容 ML Kit 把 0/1 识别成 O/l，但不再让任意“8/4-20”之类日期成为任务。
    private static final Pattern PROGRESS = Pattern.compile("(.{2,}?)[(]?([0-9Oo〇]+)\\s*/\\s*([0-9Il|]+)[)]?");
    private static final int MAX_REASONABLE_TOTAL = 50;
    private static final int ACTION_Y_TOLERANCE = 95;

    private static final List<String> ACTION_WORDS = Arrays.asList(
            "领取奖励", "去完成", "去逛逛", "逛一逛", "去浏览", "去看看",
            "去领取", "立即领", "搜一下", "搜一搜", "逛一下", "点击得"
    );

    private static final List<String> PANEL_MARKERS = Arrays.asList(
            "今日速赚", "更多金币等你赚", "更多专享福利", "完成下方任务得额外金币",
            "任务到访得金币", "每日来任务面板", "赚金币抵钱"
    );

    private static final List<String> PREFERRED = Arrays.asList(
            "搜一搜", "浏览", "好物", "精选", "沉浸看", "看看#", "逛逛", "逛一逛"
    );

    private static final List<String> EXPANDED_PANEL_TASK_HINTS = Arrays.asList(
            "发现精选好物", "淘金币趣味课堂", "任务到访得金币", "逛清单",
            "看看你关注", "搜一搜你心仪的宝贝", "更多专享福利"
    );

    private TaskParser() {}

    /**
     * 宽松解析，但带基础安全约束。主要用于诊断/签名。
     * 主循环应使用 parseActionable()，避免在商品页把日期、销量等误当任务。
     */
    public static List<TaskItem> parse(List<OcrEngine.OcrRow> rows, int screenHeight) {
        return parseInternal(rows, screenHeight, false);
    }

    /**
     * 只返回“旁边确实存在任务动作按钮”的带进度行。
     * 淘金币普通任务右侧都会有领取奖励/去完成/去逛逛等按钮；
     * 商品页、活动页即使偶然出现 1/3，也不会仅凭进度数字进入点击逻辑。
     */
    public static List<TaskItem> parseActionable(List<OcrEngine.OcrRow> rows, int screenHeight) {
        return parseInternal(rows, screenHeight, true);
    }

    private static List<TaskItem> parseInternal(List<OcrEngine.OcrRow> rows, int screenHeight, boolean requireAction) {
        List<TaskItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            OcrEngine.OcrRow row = rows.get(rowIndex);
            String normalized = normalize(row.text);
            Matcher m = PROGRESS.matcher(normalized);
            if (!m.find()) continue;

            boolean hasParen = normalized.indexOf('(') >= 0 || normalized.indexOf(')') >= 0;
            boolean actionEvidence = hasActionEvidenceNear(rows, rowIndex);
            // 强解析要求附近确实存在任务动作按钮；宽松解析只会在外层已经通过
            // isTrustedTaskList() 后用于主循环，因此仍保留 Python 最终版对“括号偶尔漏识别”的兼容。
            if (requireAction && !actionEvidence) continue;

            String prefix = m.group(1);
            int current;
            int total;
            try {
                current = parseProgressNumber(m.group(2));
                total = parseProgressNumber(m.group(3));
            } catch (Exception e) {
                continue;
            }

            // 实际淘金币可见任务常见总数为 1/2/3/10 等。保留到 50 已足够宽松，
            // 同时明确过滤日志中“活动时间(8/420)”这种页面日期误识别。
            if (total <= 0 || total > MAX_REASONABLE_TOTAL || current < 0 || current > total) continue;

            for (String noise : ACTION_WORDS) prefix = prefix.replace(noise, "");
            prefix = prefix.replaceAll("^[-:：| ]+|[-:：| ]+$", "");
            if (prefix.length() < 2) continue;

            // 商品/活动页上常见的非任务前缀，再做一层低成本保险。
            String np = normalize(prefix);
            if (np.equals("活动时间") || np.startsWith("活动时间")
                    || np.startsWith("已售") || np.startsWith("成交")
                    || np.startsWith("近一个月") || np.startsWith("行业新趋")) {
                continue;
            }

            String name = String.format(Locale.CHINA, "%s(%d/%d)", prefix, current, total);
            if (seen.contains(name)) continue;
            int y = row.bounds.centerY();
            if (y < 380 || y > screenHeight - 140) continue;
            seen.add(name);
            out.add(new TaskItem(name, current, total, y, row.bounds));
        }
        out.sort(Comparator.comparingInt(a -> a.y));
        return out;
    }

    /**
     * 强任务列表认证。不能再用“看到一个 x/y”作为成功条件。
     * 至少要同时具备淘金币任务面板特征 + 任务动作按钮，或多条动作任务。
     */
    public static boolean isTrustedTaskList(List<OcrEngine.OcrRow> rows, int screenHeight) {
        if (rows == null || rows.isEmpty()) return false;
        String all = allNormalized(rows);
        int actionRows = countActionRows(rows);
        List<TaskItem> actionable = parseActionable(rows, screenHeight);

        boolean panelMarker = containsAnyRaw(all, PANEL_MARKERS);
        boolean strongSection = all.contains(normalize("更多专享福利"))
                || all.contains(normalize("完成下方任务得额外金币"))
                || all.contains(normalize("今日速赚"));
        boolean bottomMarker = all.contains(normalize("收起更多任务"))
                || all.contains(normalize("以上金币额均为最高可得"))
                || all.contains(normalize("以实际可得数为准"));

        if (strongSection && actionRows >= 1) return true;
        if (panelMarker && actionRows >= 1 && !actionable.isEmpty()) return true;
        if (actionRows >= 2 && !actionable.isEmpty()) return true;
        return bottomMarker && actionRows >= 1 && !actionable.isEmpty();
    }

    /**
     * v2.1 task-panel context certification.
     *
     * The expanded "今日速赚" area sometimes renders rewards as round coin icons rather than
     * text buttons, so requiring 去完成/领取奖励 would reject a real task panel. This method
     * accepts only combinations that are specific to the Taobao coin task sheet; a lone x/y
     * progress string is still never sufficient.
     */
    public static boolean isExpandedTaskPanelView(List<OcrEngine.OcrRow> rows, int screenHeight) {
        if (rows == null || rows.isEmpty()) return false;
        String all = allNormalized(rows);

        // “更多金币等你赚”是当前版本折叠态卡片的稳定锚点。只要它仍可见，
        // 就不能因为上方今日速赚已经有任务标题而把页面误判为“已展开”。
        if (all.contains(normalize("更多金币等你赚"))) return false;

        boolean rewardHeader = all.contains(normalize("完成下方任务得额外金币"))
                || (all.contains(normalize("完成下方任务")) && all.contains(normalize("金币")));
        boolean quickEarn = all.contains(normalize("今日速赚"))
                || (all.contains(normalize("今日速")) && all.contains(normalize("淘金币")));
        boolean exclusive = all.contains(normalize("更多专享福利"));
        int taskHints = 0;
        for (String hint : EXPANDED_PANEL_TASK_HINTS) {
            if (all.contains(normalize(hint))) taskHints++;
        }

        if (rewardHeader && (quickEarn || exclusive)) return true;
        if (rewardHeader && taskHints >= 1) return true;
        if (quickEarn && (rewardHeader || exclusive) && taskHints >= 1) return true;
        if (exclusive && taskHints >= 2) return true;

        // A real panel can also be scrolled so that the header is just outside the crop. In that
        // case keep the stricter old action-button certification.
        return false;
    }

    public static boolean isTrustedTaskContext(List<OcrEngine.OcrRow> rows, int screenHeight) {
        return isTrustedTaskList(rows, screenHeight) || isExpandedTaskPanelView(rows, screenHeight);
    }

    public static int countActionRows(List<OcrEngine.OcrRow> rows) {
        int count = 0;
        for (OcrEngine.OcrRow row : rows) {
            if (containsActionWord(normalize(row.text))) count++;
        }
        return count;
    }

    private static boolean hasActionEvidenceNear(List<OcrEngine.OcrRow> rows, int rowIndex) {
        OcrEngine.OcrRow source = rows.get(rowIndex);
        int sourceY = source.bounds.centerY();
        for (int i = 0; i < rows.size(); i++) {
            OcrEngine.OcrRow candidate = rows.get(i);
            if (Math.abs(candidate.bounds.centerY() - sourceY) > ACTION_Y_TOLERANCE) continue;
            if (containsActionWord(normalize(candidate.text))) return true;
        }
        return false;
    }

    private static boolean containsActionWord(String normalized) {
        for (String word : ACTION_WORDS) {
            if (normalized.contains(normalize(word))) return true;
        }
        return false;
    }

    private static boolean containsAnyRaw(String normalizedHaystack, List<String> words) {
        for (String word : words) {
            if (normalizedHaystack.contains(normalize(word))) return true;
        }
        return false;
    }

    private static String allNormalized(List<OcrEngine.OcrRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (OcrEngine.OcrRow r : rows) sb.append(normalize(r.text));
        return sb.toString();
    }

    private static int parseProgressNumber(String raw) {
        if (raw == null) throw new NumberFormatException("null progress");
        String fixed = raw
                .replace('O', '0').replace('o', '0').replace('〇', '0')
                .replace('I', '1').replace('l', '1').replace('|', '1');
        return Integer.parseInt(fixed);
    }

    public static boolean completed(TaskItem t) { return t.current >= t.total; }

    public static boolean skip(TaskItem t, TaskRuleStore.Rules rules) {
        if (completed(t)) return true;
        // Blacklist matching is intentionally more tolerant than ordinary routing. OCR frequently
        // confuses visually similar Chinese characters (e.g. 淘金币趣味课堂 -> 淘全币趣昧课堂).
        // Short generic rules still require an exact hit; only longer, specific rules get a small
        // edit-distance allowance to avoid turning the whole classifier fuzzy.
        return containsAnyFuzzyForBlacklist(t.name, rules.skip);
    }

    public static boolean external(TaskItem t, TaskRuleStore.Rules rules) {
        if (forcedBrowse(t, rules)) return false;
        // 和黑名单一样走保守模糊匹配：OCR 会把"金豆夺宝"识成"金豆寺宝"，
        // 严格包含匹配会让本该跨应用的任务被误判成普通浏览。
        return containsAnyFuzzyForBlacklist(t.name, rules.external);
    }

    public static boolean isSearch(TaskItem t, TaskRuleStore.Rules rules) {
        if (forcedBrowse(t, rules)) return false;
        if (external(t, rules)) return false;
        return containsAnyFuzzyForBlacklist(t.name, rules.search);
    }

    public static boolean forcedBrowse(TaskItem t, TaskRuleStore.Rules rules) {
        return containsAny(t.name, rules.browse);
    }

    public static int priority(TaskItem t) {
        return containsAny(t.name, PREFERRED) ? 0 : 1;
    }

    public static boolean bottomVisible(List<OcrEngine.OcrRow> rows) {
        String all = allNormalized(rows);
        return all.contains(normalize("收起更多任务"))
                || all.contains(normalize("以上金币额均为最高可得"))
                || all.contains(normalize("以实际可得数为准"));
    }

    public static String displayMode(TaskItem t, TaskRuleStore.Rules rules) {
        if (skip(t, rules)) return "跳过";
        if (forcedBrowse(t, rules)) return "普通浏览(指定)";
        if (external(t, rules)) return "跨应用";
        if (isSearch(t, rules)) return "搜索后浏览";
        return "普通浏览";
    }

    private static boolean containsAny(String s, List<String> words) {
        String ns = TaskRuleStore.normalizeKeyword(s);
        for (String w : words) {
            if (!w.isEmpty() && ns.contains(TaskRuleStore.normalizeKeyword(w))) return true;
        }
        return false;
    }

    private static boolean containsAnyFuzzyForBlacklist(String s, List<String> words) {
        String ns = TaskRuleStore.normalizeKeyword(s);
        for (String word : words) {
            String nw = TaskRuleStore.normalizeKeyword(word);
            if (nw.isEmpty()) continue;
            if (ns.contains(nw)) return true;

            // Keep broad/short rules such as 游戏、下单、UC exact-only.
            int maxDistance = nw.length() >= 6 ? 2 : (nw.length() >= 4 ? 1 : 0);
            if (maxDistance > 0 && fuzzyContains(ns, nw, maxDistance)) return true;
        }
        return false;
    }

    private static boolean fuzzyContains(String text, String target, int maxDistance) {
        if (text == null || target == null || text.isEmpty() || target.isEmpty()) return false;
        if (text.contains(target)) return true;
        int minLen = Math.max(1, target.length() - maxDistance);
        int maxLen = target.length() + maxDistance;
        for (int len = minLen; len <= maxLen; len++) {
            if (len > text.length()) continue;
            for (int i = 0; i + len <= text.length(); i++) {
                if (editDistance(text.substring(i, i + len), target, maxDistance) <= maxDistance) return true;
            }
        }
        return false;
    }

    private static int editDistance(String a, String b, int limit) {
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

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('（', '(')
                .replace('）', ')')
                .replace('／', '/')
                .replace('額', '额')
                .replace('幣', '币')
                .replace('賺', '赚')
                .replace('獎', '奖')
                .replace('專', '专')
                .replace('領', '领')
                .replace('勵', '励')
                .replaceAll("\\s+", "");
    }

    public static final class TaskItem {
        public final String name;
        public final int current;
        public final int total;
        public final int y;
        public final Rect bounds;
        public TaskItem(String name, int current, int total, int y, Rect bounds) {
            this.name = name;
            this.current = current;
            this.total = total;
            this.y = y;
            this.bounds = bounds;
        }
        @Override public String toString() { return name; }
    }
}

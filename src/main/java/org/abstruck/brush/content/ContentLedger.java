package org.abstruck.brush.content;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 内容注册归账表 —— 记录经 {@code api.BrushRegistry} 提交的每一次
 * 内容注册<b>尝试</b>（成功与拒绝），供自检报告与归账审计（退出码位 16）使用。
 *
 * <p><b>零 MC 引用（结构性约束）</b>：本类不引用任何 {@code net.minecraft.*} 类型 ——
 * 与 {@link ModContext} 同属"可被任何加载时机安全加载"的组件：
 * <ul>
 *   <li>{@code org.abstruck.brush.selftest.Selftest}（在 {@code client.tick} 期生成报告）加载它不产生额外类加载；</li>
 *   <li>归账逻辑可在<b>无 MC 类路径</b>的测试 JVM 中单测；
 *   </li>
 *   <li>与 {@code api} 包"零 MC"规则一致 —— {@code Event.register} 经 {@link ModContext} 与本表
 *       交互，若本表引用 MC 类型，会把 MC 类拖进 {@code Event} 类加载链。</li>
 * </ul>
 * 注册调用本身（vanilla 注册表 API）由 {@code api.BrushRegistry} 承担，与本表解耦。
 *
 * <p><b>线程模型</b>：注册发生在 bootstrap 窗口线程（S1 为主线程；宿主下以各宿主为准），
 * 报告在游戏主线程读取 —— 与 {@code Dispatcher} 同款保守口径：mutex + 快照。
 * 自检退出点（首个 tick）晚于内容注册窗口，报告读到的是最终态。
 */
public final class ContentLedger {

    private static final Logger LOGGER = LogManager.getLogger(ContentLedger.class);

    /** 归账条目（{@code type} 为注册表类别短名：{@code item} / {@code block} / 注册表标识路径段）。 */
    public record Entry(String modId, String type, String id) { }

    /**
     * 注册被拒绝的记录（成功路径不产生）。
     * 供审计定位（如"窗口未到达"时期的注册尝试）—— 每次拒绝都在发生点记一条 ERROR 日志。
     */
    public record Rejection(String modId, String id, String reason) { }

    /** 全部归账条目（成功注册，按发生序）。 */
    private static final List<Entry> ENTRIES = new ArrayList<>();

    /** 全部拒绝记录（按发生序；审计时同一 reason 只列一次，见 {@link #audit}）。 */
    private static final List<Rejection> REJECTIONS = new ArrayList<>();

    private static final Object MUTEX = new Object();

    private ContentLedger() {
    }

    /** 记录一次成功注册（{@code modId} / {@code type} / {@code id} 均非空，由 {@code BrushRegistry} 保证）。 */
    public static void record(String modId, String type, String id) {
        Entry entry = new Entry(modId, type, id);
        synchronized (MUTEX) {
            ENTRIES.add(entry);
        }
        LOGGER.info("[brush] content: registered " + type + " " + id + " (by " + modId + ")");
    }

    /** 记录一次被拒绝的注册（{@code reason} 为可读摘要；调用方随后抛异常，不吞错）。 */
    public static void recordRejection(String modId, String id, String reason) {
        Rejection rejection = new Rejection(modId, id, reason);
        synchronized (MUTEX) {
            REJECTIONS.add(rejection);
        }
        LOGGER.error("[brush] content: registration REJECTED (by " + modId + "): " + id
                + " -- " + reason);
    }

    /** 全部条目快照（按发生序）。 */
    public static List<Entry> entries() {
        synchronized (MUTEX) {
            return List.copyOf(ENTRIES);
        }
    }

    /** 全部拒绝快照（按发生序）。 */
    public static List<Rejection> rejections() {
        synchronized (MUTEX) {
            return List.copyOf(REJECTIONS);
        }
    }

    /** 条目总数（成功注册数）。 */
    public static int registrationCount() {
        synchronized (MUTEX) {
            return ENTRIES.size();
        }
    }

    /**
     * 注册类别 → 条目数快照（类型名字典序 —— 报告行文本确定性，便于三运行时逐字比对）。
     * 类别名 == 注册表标识路径段：ITEM → {@code item}、BLOCK → {@code block}，泛型注册的其他类别同理。
     */
    public static Map<String, Integer> counts() {
        synchronized (MUTEX) {
            Map<String, Integer> counts = new TreeMap<>();
            for (Entry entry : ENTRIES) {
                counts.merge(entry.type(), 1, Integer::sum);
            }
            return new LinkedHashMap<>(counts);
        }
    }

    /**
     * 归账审计（退出码位 16 的判定源）。
     *
     * <p><b>断言集（确定性，按序）</b>：
     * <ol>
     *   <li>无被拒绝的注册 —— 任何一条拒绝都意味着某 mod 的注册没有生效（如报告生成时窗口未到达；
     *       窗口内的拒绝在发生点即导致启动失败，此时审计根本不会执行，属"更好"的失败形态）；</li>
     *   <li>每条条目的归属 mod 均在已加载 mod 列表内，且 id / modId / 类别名非空白；</li>
     *   <li>有已加载 mod 时，归账条目必须非空（否则 = 全部 mod 的内容注册均未发生，如窗口未到达）。</li>
     * </ol>
     * 第 1 条排除了"有拒绝但注册数为 0"的模糊态，故不再单列"条目为空"分支。
     *
     * <p>刻意不做：不把"必须注册 N 个物品 / 方块"写进核心断言 ——
     * 那是示例 mod 的具体事实，不是加载器的普遍契约；具体数量的核对以日志中的
     * {@code content: registered ...} 归属行为准。
     *
     * @param loadedModIds 已加载 mod 的 id 集合（不为 null；空集表示无 mod）
     */
    public static Audit audit(Iterable<String> loadedModIds) {
        Map<String, Integer> counts = counts();
        int items = counts.getOrDefault("item", 0);
        int blocks = counts.getOrDefault("block", 0);
        int tabs = counts.getOrDefault("creative_tab", 0);
        StringBuilder sb = new StringBuilder("content.registered: items=").append(items)
                .append(", blocks=").append(blocks).append(", tabs=").append(tabs);

        List<String> problems = new ArrayList<>();

        List<Rejection> rejections = rejections();
        if (!rejections.isEmpty()) {
            // 同一 reason 只列一次（反例行会重复刷屏；条目数如实计）
            Map<String, Integer> byReason = new LinkedHashMap<>();
            for (Rejection rejection : rejections) {
                byReason.merge(rejection.reason(), 1, Integer::sum);
            }
            problems.add(rejections.size() + " rejected registration(s):");
            byReason.forEach((reason, count) ->
                    problems.add(count + "x " + reason));
        }

        List<Entry> entries = entries();
        int modsLoaded = 0;
        for (String modId : loadedModIds) {
            modsLoaded++;
            boolean ownsContent = false;
            for (Entry entry : entries) {
                if (entry.modId().equals(modId)) {
                    ownsContent = true;
                    break;
                }
            }
            if (!ownsContent) {
                // 信息性提示（不置失败）：某 mod 选择不注册内容是完全合法的；
                // 全都不注册的情形由下面的第 3 条兜底。
                LOGGER.info("[brush] content: mod '" + modId + "' registered no content (allowed)");
            }
        }
        for (Entry entry : entries) {
            if (entry.modId() == null || entry.modId().isBlank()
                    || entry.id() == null || entry.id().isBlank()
                    || entry.type() == null || entry.type().isBlank()) {
                problems.add("ledger entry with blank field: " + entry);
            } else if (!contains(loadedModIds, entry.modId())) {
                problems.add("ledger entry attributed to unknown mod '" + entry.modId() + "': "
                        + entry.type() + " " + entry.id());
            }
        }

        if (modsLoaded > 0 && entries.isEmpty() && rejections.isEmpty()) {
            problems.add("no content registered although " + modsLoaded
                    + " brush mod(s) are loaded (mods may have chosen not to register;"
                    + " verify with the 'content: registered' lines)");
        }

        if (problems.isEmpty()) {
            return new Audit(sb.toString(), true);
        }
        sb.append(" -- INCONSISTENT: ");
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(problems.get(i));
        }
        return new Audit(sb.toString(), false);
    }

    /** 审计结果：{@code summary} = 报告行文本；{@code healthy=false} 时置退出码位 16（{@code Selftest}）。 */
    public record Audit(String summary, boolean healthy) { }

    private static boolean contains(Iterable<String> ids, String wanted) {
        for (String id : ids) {
            if (id.equals(wanted)) {
                return true;
            }
        }
        return false;
    }
}

package org.abstruck.brush.mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 模组依赖解析：id 去重 → 依赖完整性 / 版本区间校验 → 拓扑排序（Kahn，确定性顺序）。
 *
 * <p>纯逻辑：无 IO、无 MC / 类加载依赖，可直接单测（{@code ModResolverTest}）。
 * 全部失败都以<b>可读问题串</b>的形式返回（{@link Result#problems()}），由调用方汇总成报告 ——
 * 一次列出全部问题，不是发现第一个就停。
 *
 * <p>特殊依赖 id：{@code brush}（加载器版本）/ {@code minecraft}（游戏版本）；
 * 其余 id 必须在本次扫描到的 mod 集合中。依赖的 mod 先于被依赖者加载（拓扑序），
 * 同层按 id 字典序保证可复现。循环依赖给出含具体路径的问题串。
 */
public final class ModResolver {

    /** 加载器自身的依赖 id（版本 = 加载器自身元数据 brush.mod.json 的 version，构建期展开）。 */
    public static final String LOADER_ID = "brush";

    /** 游戏的依赖 id（版本 = version json 的 id，如 "26.1.2"）。 */
    public static final String GAME_ID = "minecraft";

    private ModResolver() { }

    /**
     * 解析结果。
     *
     * @param ordered  拓扑序（依赖在前）；存在问题串时为空列表（有问题即不实例化任何 mod）
     * @param problems 可读问题串（<b>硬失败</b>；空 = 全部通过）
     * @param warnings 可读警告串（{@code conflicts} 命中 / {@code recommends} 未满足；
     *                 <b>不阻断加载</b>；空 = 无警告）
     */
    public record Result(List<DiscoveredMod> ordered, List<String> problems, List<String> warnings) { }

    /**
     * 解析并排序。
     *
     * @param mods          已解析出元数据的 mod（顺序无关）
     * @param loaderVersion 加载器版本（{@code brush} 依赖的比对对象）
     * @param gameVersion   游戏版本（{@code minecraft} 依赖的比对对象）
     */
    public static Result resolve(List<DiscoveredMod> mods, String loaderVersion, String gameVersion) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1) id 去重（同 id 的后续 jar 全部登记为问题；保留首个用于后续检查）
        Map<String, DiscoveredMod> byId = new TreeMap<>();
        for (DiscoveredMod mod : mods) {
            String id = mod.metadata().id;
            DiscoveredMod previous = byId.putIfAbsent(id, mod);
            if (previous != null) {
                problems.add("duplicate mod id '" + id + "': " + previous.fileName()
                        + " and " + mod.fileName());
            }
        }

        // 1.5) provides 别名（Fabric 语义，docs/25 §3.3）：别名 id 视为"存在的 mod"。
        //      Fabric 原文对冲突的处理是"其它同名 mod 不被加载"；本加载器不静默丢弃任何 mod，
        //      改为可读失败（§3.5）—— 两颗雷都报：别名撞别人的自身 id、两 mod 提供同一别名。
        Map<String, DiscoveredMod> byAlias = new TreeMap<>();
        for (DiscoveredMod mod : byId.values()) {
            List<String> aliases = mod.metadata().provides;
            if (aliases == null) {
                continue;
            }
            for (String alias : aliases) {
                DiscoveredMod owner = byId.get(alias);
                if (owner != null) {
                    problems.add("mod '" + mod.metadata().id + "' provides '" + alias
                            + "', but '" + alias + "' is already the id of " + owner.fileName()
                            + " -- refusing to load them together");
                    continue;
                }
                DiscoveredMod previous = byAlias.putIfAbsent(alias, mod);
                if (previous != null) {
                    problems.add("alias '" + alias + "' is provided by both '"
                            + previous.metadata().id + "' and '" + mod.metadata().id + "'");
                }
            }
        }

        // 2) 依赖校验：存在性 + 版本区间
        for (DiscoveredMod mod : byId.values()) {
            Map<String, String> depends = mod.metadata().depends;
            if (depends == null) {
                continue;
            }
            for (Map.Entry<String, String> dependency : depends.entrySet()) {
                String dependencyId = dependency.getKey();
                String spec = dependency.getValue();

                String actualVersion;
                if (LOADER_ID.equals(dependencyId)) {
                    actualVersion = loaderVersion;
                } else if (GAME_ID.equals(dependencyId)) {
                    actualVersion = gameVersion;
                } else {
                    DiscoveredMod target = byId.get(dependencyId);
                    boolean viaAlias = false;
                    if (target == null) {
                        target = byAlias.get(dependencyId);
                        viaAlias = target != null;
                    }
                    if (target == null) {
                        problems.add("mod '" + mod.metadata().id + "' requires '" + dependencyId
                                + "' (" + describe(spec) + "), not present (available: "
                                + String.join(", ", availableIds(byId, byAlias)) + ")");
                        continue;
                    }
                    actualVersion = target.metadata().version;
                    // viaAlias = true 时是"经别名满足"：不算问题（别名本就是 Fabric 语义认可的
                    // 存在形式），但保留分支以便将来在报告里区分。
                }

                VersionRange range;
                try {
                    range = VersionRange.parse(spec);
                } catch (IllegalArgumentException e) {
                    problems.add("mod '" + mod.metadata().id + "': invalid version range for '"
                            + dependencyId + "': " + e.getMessage());
                    continue;
                }
                boolean satisfied;
                try {
                    satisfied = range.contains(actualVersion);
                } catch (IllegalArgumentException e) {
                    problems.add("mod '" + mod.metadata().id + "': cannot check dependency '"
                            + dependencyId + "': " + e.getMessage() + " (found \"" + actualVersion + "\")");
                    continue;
                }
                if (!satisfied) {
                    problems.add("mod '" + mod.metadata().id + "' requires '" + dependencyId + "' "
                            + describe(spec) + ", found " + actualVersion);
                }
            }
        }

        // 2.5) 反向依赖与提示性声明（Fabric 语义对齐，语义来源见 ModMetadata 各字段 javadoc）：
        //      breaks     = 命中 → 硬失败（problem）
        //      conflicts  = 命中 → 警告（warning，不阻断）
        //      recommends = 缺失/版本不满足 → 警告
        //      suggests   = 纯元数据，有意不读
        for (DiscoveredMod mod : byId.values()) {
            ModMetadata metadata = mod.metadata();
            checkDeclarations(metadata.id, "breaks", metadata.breaks, Declaration.BREAKS,
                    byId, byAlias, loaderVersion, gameVersion, problems, warnings);
            checkDeclarations(metadata.id, "conflicts", metadata.conflicts, Declaration.CONFLICTS,
                    byId, byAlias, loaderVersion, gameVersion, problems, warnings);
            checkDeclarations(metadata.id, "recommends", metadata.recommends, Declaration.RECOMMENDS,
                    byId, byAlias, loaderVersion, gameVersion, problems, warnings);
        }

        // 3) 拓扑排序（依赖在前）；有问题时不再排序（排序结果不会被使用）
        List<DiscoveredMod> ordered = List.of();
        if (problems.isEmpty()) {
            ordered = topologicalOrder(byId, byAlias, problems);
        }
        return new Result(ordered, problems, warnings);
    }

    /** 四张声明表的语义分档（决定命中后是硬失败还是警告）。 */
    private enum Declaration {
        /** 同存即崩 → 硬失败。 */
        BREAKS,
        /** 同存易出 bug → 警告。 */
        CONFLICTS,
        /** 推荐依赖缺失/不满足 → 警告。 */
        RECOMMENDS
    }

    /**
     * 解析某个依赖 id 的实况版本。
     *
     * @return 版本串；目标<b>不在场</b>时 null（{@code breaks} / {@code conflicts} 据此判定"无事发生"）
     */
    private static String actualVersionOf(String id, Map<String, DiscoveredMod> byId,
            Map<String, DiscoveredMod> byAlias, String loaderVersion, String gameVersion) {
        if (LOADER_ID.equals(id)) {
            return loaderVersion;
        }
        if (GAME_ID.equals(id)) {
            return gameVersion;
        }
        DiscoveredMod target = byId.get(id);
        if (target == null) {
            target = byAlias.get(id);   // provides 别名：版本 = 提供者自身的版本
        }
        return target == null ? null : target.metadata().version;
    }

    /** 报告用"可用 id"列表：自身 id + 别名（让 "not present" 消息能提示别名也是合法目标）。 */
    private static List<String> availableIds(Map<String, DiscoveredMod> byId,
            Map<String, DiscoveredMod> byAlias) {
        List<String> ids = new ArrayList<>(byId.keySet());
        for (String alias : byAlias.keySet()) {
            ids.add(alias + " (alias)");
        }
        return ids;
    }

    /**
     * 校验一张声明表并按 {@code kind} 归档到 problems（硬失败）或 warnings（不阻断）。
     *
     * <p>非法版本区间 = 元数据错误 → problems（与 {@code depends} 同口径 fail-fast；
     * 四张表一致，避免"某个字段写错却静默忽略"）。</p>
     */
    private static void checkDeclarations(String ownerId, String field, Map<String, String> table,
            Declaration kind, Map<String, DiscoveredMod> byId, Map<String, DiscoveredMod> byAlias,
            String loaderVersion, String gameVersion, List<String> problems, List<String> warnings) {
        if (table == null) {
            return;
        }
        for (Map.Entry<String, String> entry : table.entrySet()) {
            String targetId = entry.getKey();
            String spec = entry.getValue();
            String actual = actualVersionOf(targetId, byId, byAlias, loaderVersion, gameVersion);

            if (actual == null) {
                if (kind == Declaration.RECOMMENDS) {
                    warnings.add("mod '" + ownerId + "' recommends '" + targetId + "' ("
                            + describe(spec) + "), not present");
                }
                continue;   // breaks / conflicts：目标不在场 → 无事发生
            }

            VersionRange range;
            try {
                range = VersionRange.parse(spec);
            } catch (IllegalArgumentException e) {
                problems.add("mod '" + ownerId + "': invalid version range in '" + field + "' for '"
                        + targetId + "': " + e.getMessage());
                continue;
            }
            boolean matches;
            try {
                matches = range.contains(actual);
            } catch (IllegalArgumentException e) {
                problems.add("mod '" + ownerId + "': cannot check '" + field + "' for '" + targetId
                        + "': " + e.getMessage() + " (found \"" + actual + "\")");
                continue;
            }

            switch (kind) {
                case BREAKS -> {
                    if (matches) {
                        problems.add("mod '" + ownerId + "' declares it breaks with '" + targetId + "' "
                                + describe(spec) + " (found " + actual + ") -- refusing to load both"
                                + " together");
                    }
                }
                case CONFLICTS -> {
                    if (matches) {
                        warnings.add("mod '" + ownerId + "' declares a conflict with '" + targetId + "' "
                                + describe(spec) + " (found " + actual + ") -- both are loaded; bugs"
                                + " are possible");
                    }
                }
                case RECOMMENDS -> {
                    if (!matches) {
                        warnings.add("mod '" + ownerId + "' recommends '" + targetId + "' "
                                + describe(spec) + ", found " + actual);
                    }
                }
                default -> throw new IllegalStateException("unhandled declaration kind: " + kind);
            }
        }
    }

    private static String describe(String spec) {
        return spec == null || spec.isBlank() || spec.trim().equals("*") ? "any version" : spec;
    }

    /**
     * Kahn 拓扑排序（边指向依赖）。同层按 id 字典序出队，结果可复现。
     * 无法排尽时从剩余节点走出一条实际环路径（依据见 {@link #cyclePath} 的兜底依据）。
     */
    private static List<DiscoveredMod> topologicalOrder(Map<String, DiscoveredMod> byId,
            Map<String, DiscoveredMod> byAlias, List<String> problems) {
        // 节点 → 未满足的依赖集合（仅集合内依赖；brush/minecraft 不在 byId 中，天然排除）
        Map<String, Set<String>> pendingDependencies = new TreeMap<>();
        for (DiscoveredMod mod : byId.values()) {
            Set<String> pending = new TreeSet<>();
            Map<String, String> depends = mod.metadata().depends;
            if (depends != null) {
                for (String dependencyId : depends.keySet()) {
                    if (byId.containsKey(dependencyId)) {
                        pending.add(dependencyId);
                    } else {
                        // 别名依赖 = 对"提供者"的依赖（docs/25 §3.3）。**漏掉这条边会让依赖方
                        // 被排到提供者之前** —— 别名支持必须同步改排序，否则是静默的顺序错误。
                        DiscoveredMod provider = byAlias.get(dependencyId);
                        if (provider != null) {
                            pending.add(provider.metadata().id);
                        }
                    }
                }
            }
            pendingDependencies.put(mod.metadata().id, pending);
        }

        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.naturalOrder());
        for (Map.Entry<String, Set<String>> entry : pendingDependencies.entrySet()) {
            if (entry.getValue().isEmpty()) {
                ready.add(entry.getKey());
            }
        }

        List<DiscoveredMod> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            ordered.add(byId.get(id));
            for (Map.Entry<String, Set<String>> entry : pendingDependencies.entrySet()) {
                Set<String> pending = entry.getValue();
                if (pending.remove(id) && pending.isEmpty()) {
                    ready.add(entry.getKey());
                }
            }
        }

        if (ordered.size() != byId.size()) {
            Set<String> leftover = new TreeSet<>(byId.keySet());
            for (DiscoveredMod mod : ordered) {
                leftover.remove(mod.metadata().id);
            }
            problems.add("dependency cycle among: " + cyclePath(leftover, pendingDependencies));
        }
        return ordered;
    }

    /**
     * 从剩余节点走出一条环路径（报告用，非算法必需）。
     * 兜底依据：每个剩余节点必有至少一个剩余依赖 —— 否则它早已出队，矛盾；
     * 因此沿"剩余依赖"行走必然会在有限步内重访节点，截取重访段即为一个真实环。
     */
    private static String cyclePath(Set<String> leftover, Map<String, Set<String>> pendingDependencies) {
        String start = leftover.iterator().next();
        List<String> walked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = start;
        while (!seen.contains(current)) {
            seen.add(current);
            walked.add(current);
            String next = null;
            for (String dependency : pendingDependencies.get(current)) {
                if (leftover.contains(dependency)) {
                    next = dependency;
                    break;
                }
            }
            if (next == null) {
                break; // 理论不可达（见兜底依据）；防御性保留
            }
            current = next;
        }
        if (seen.contains(current)) {
            List<String> cycle = new ArrayList<>(walked.subList(walked.indexOf(current), walked.size()));
            cycle.add(current);
            return String.join(" -> ", cycle);
        }
        return String.join(" -> ", walked);
    }
}

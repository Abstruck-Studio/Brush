package org.abstruck.brush.mod;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 依赖版本区间（brush.mod.json 的 {@code depends} 取值）。
 *
 * <p><b>为什么自研、而不复用现成实现（评审 P2-3 要求写明理由）</b>：本项目 classpath 上确有
 * Maven 的版本工具（{@code maven-artifact}，且是 <b>compileOnly</b>），其中
 * {@code org.apache.maven.artifact.versioning.VersionRange#createFromVersionSpec} 同样能解析区间
 * —— 所以"没有现成的"是**错的**，理由必须写准。仍自研的三条（均已查证）：</p>
 * <ol>
 *   <li><b>语法不同，换实现 = 换对外契约</b>：本类实现的是 README 公开的 Brush 声明语法
 *       （{@code >=} / {@code ^} / {@code ~} / {@code *} / 空格 AND / {@code ||} OR）；
 *       Maven 的 spec 是区间记法（{@code [1.0,2.0)} 一类，其模型见 {@code Restriction}）。
 *       改用后者会改变**已发布 mod 元数据**的语义 —— 这是契约，不是内部细节。</li>
 *   <li><b>依赖可用性（硬理由）</b>：{@code maven-artifact} 是 {@code compileOnly}，因为
 *       <b>宿主运行时自带</b>它（NeoForge 的 {@code IModInfo.getVersion()} 返回其类型）。
 *       把它变成核心解析的**运行时**依赖，会让 S1（纯 Brush、无宿主）不成立 ——
 *       违反 §12.4.1「API 天花板 = 最受约束的平台」。</li>
 *   <li><b>失败信息按 mod 作者口径</b>：解析失败要指出**具体哪个条件**非法（fail-fast），
 *       而不是把宿主 / Maven 的异常类型直接透给 mod 作者。</li>
 * </ol>
 *
 * <p><b>区间语法（Brush 自定义）</b>：
 * <ul>
 *   <li>缺省 / 空串 / {@code "*"} —— 任意版本（{@code *} 也可作为单个条件混入，如 {@code ">=1 || *"}）；</li>
 *   <li>单个版本串 —— 精确相等（等价 {@code =x.y.z}）；</li>
 *   <li>比较符前缀 {@code >= / > / <= / < / =} + 版本串；</li>
 *   <li>{@code ^} 插入符（SemVer 兼容）：{@code ^1.2.3} = {@code ">=1.2.3 <2.0.0"}，{@code ^0.2.3} = {@code ">=0.2.3 <0.3.0"}，
 *       {@code ^0.0.3} = {@code ">=0.0.3 <0.0.4"}，{@code ^0.0} = {@code ">=0.0.0 <0.1.0"}（首个非零段进位，全零时进位最后一个声明段；npm 规则）；</li>
 *   <li>{@code ~} 波浪符：{@code ~1.2.3} = {@code ">=1.2.3 <1.3.0"}（声明了 minor 则允许 patch 级变化），
 *       {@code ~1} = {@code ">=1.0.0 <2.0.0"}（只声明 major 则允许 minor 级变化）；</li>
 *   <li>空格分隔的多个条件为<b>同时满足</b>（AND），如 {@code ">=1.0 <2.0"}；</li>
 *   <li>{@code ||} 分隔的多个分组为<b>任一分组满足</b>（OR），如 {@code "1.0.0 || >=2.0 <3.0"}；组内仍是 AND。</li>
 * </ul>
 *
 * <p><b>版本串格式（SemVer 式）</b>：点分纯数字（{@code 1} / {@code 1.2} / {@code 26.1.2}）+
 * 可选预发布后缀 {@code -xxx}（如 {@code 1.0.0-beta} / {@code 2.0.0-rc.1}，
 * 标识符段只允许 {@code [0-9A-Za-z-]}）。段数不等时缺段按 0（{@code 1.2} 等价 {@code 1.2.0}）。
 * 预发布标识符按 SemVer §11.4 比较：数字段按数值、字母段按 ASCII、数字段低于字母段、
 * 短列表低于长前缀；<b>无预发布后缀 &gt; 有预发布后缀</b>（{@code 1.0.0-beta < 1.0.0}）。
 * 不支持构建元数据（{@code +xxx}），出现即报错（消息可读，进加载失败报告）。
 *
 * <p><b>预发布与区间的匹配规则（Brush 自定义，区别于 npm）</b>：区间比较就是上述<b>全序</b>下的
 * 普通比较，不做 npm 的"预发布须同 [major.minor.patch] 元组且比较符自带预发布才可匹配"的特殊化。
 * 因此 {@code ">=1.0.0"} <b>不</b>匹配 {@code 1.0.0-beta}（全序下更小），要表达它请写
 * {@code ">=1.0.0-beta <1.0.0"}。规则简单、可预测、可自证。
 *
 * <p><b>为何自研（评审 P2-3 决策记录）</b>：
 * <ul>
 *   <li><b>需求面窄</b> —— 只服务 {@code brush.mod.json} 的 {@code depends} 校验（加载期一次性判定），
 *       不需要完整 SemVer 生态（解析/序列化/规范化/范围代数）；</li>
 *   <li><b>依赖政策</b> —— 新增第三方库须登记坐标/许可/来源（CLAUDE.md §18），而 loader 核心
 *       引入版本比较库会扩大 S2/S3 下的依赖重复面（§17.2 C4 正是"宿主已带一份、我们又塞一份"
 *       的典型风险）；</li>
 *   <li><b>语义自定、现成库对不上</b> —— {@code ^} 采用 npm"首个非零段进位"规则、
 *       预发布不做 npm 特殊化（见上），现成 Java 库要么没有 {@code ^}/{@code ~}，
 *       要么语义不同（如 Maven 区间 {@code [1.0,2.0)} 与 {@code ^1.0} 不等价）——
 *       拿来还要包一层并反向解释差异；</li>
 *   <li><b>可自证</b> —— 单文件实现 + {@code VersionRangeTest} 覆盖全序/边界/预发布/非法输入
 *       （fail-fast 消息可读）。</li>
 * </ul>
 * <b>代价如实</b>：不支持构建元数据（{@code +xxx}），出现即报错 —— 属已知边界，非静默忽略。
 *
 * <p>解析失败一律抛 {@link IllegalArgumentException}（消息可读），由调用方转为加载失败原因。
 */
public final class VersionRange {

    /** 条件分组列表：OR 语义；空 list = 任意版本（{@code *}）。每组为 AND 语义的条件列表。 */
    private final List<List<Condition>> alternatives;

    private VersionRange(List<List<Condition>> alternatives) {
        this.alternatives = alternatives;
    }

    /** 解析区间表达式；语法错误抛 {@link IllegalArgumentException}（消息可读，可直接进报告）。 */
    public static VersionRange parse(String spec) {
        if (spec == null || spec.isBlank() || spec.trim().equals("*")) {
            return new VersionRange(List.of());
        }
        List<List<Condition>> alternatives = new ArrayList<>();
        for (String alternative : spec.trim().split("\\|\\|", -1)) {
            String trimmed = alternative.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("empty alternative in version range \"" + spec
                        + "\" (separate alternatives with \"||\")");
            }
            List<Condition> conditions = new ArrayList<>();
            for (String token : trimmed.split("\\s+")) {
                conditions.add(parseToken(token));
            }
            alternatives.add(List.copyOf(conditions));
        }
        return new VersionRange(List.copyOf(alternatives));
    }

    private static Condition parseToken(String token) {
        if (token.equals("*")) {
            return new Condition(Op.ANY, null);
        }
        Op op = Op.EQ;
        String versionPart = token;
        // 顺序敏感：双字符比较符须先于其单字符前缀（">=" 先于 ">"）
        for (Op candidate : new Op[]{Op.GE, Op.LE, Op.GT, Op.LT, Op.EQ, Op.CARET, Op.TILDE}) {
            String symbol = candidate.symbol();
            if (token.startsWith(symbol)) {
                op = candidate;
                versionPart = token.substring(symbol.length());
                break;
            }
        }
        if (versionPart.isEmpty()) {
            throw new IllegalArgumentException("version expected after '" + op.symbol() + "' in \"" + token + "\"");
        }
        return new Condition(op, parseVersion(versionPart));
    }

    /**
     * 判断给定版本是否满足任一条件分组（组内经 AND 全部满足）。
     *
     * @throws IllegalArgumentException 版本串非法（消息可读）
     */
    public boolean contains(String version) {
        if (alternatives.isEmpty()) {
            return true;
        }
        Version actual = parseVersion(version);
        for (List<Condition> alternative : alternatives) {
            boolean all = true;
            for (Condition condition : alternative) {
                if (!condition.matches(actual)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 版本模型与比较
    // ------------------------------------------------------------------

    /** 解析后的版本：数字段 + 预发布标识符列表（空 = 正式版）。 */
    static final class Version {

        final int[] segments;
        final List<String> prerelease;

        Version(int[] segments, List<String> prerelease) {
            this.segments = segments;
            this.prerelease = prerelease;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    out.append('.');
                }
                out.append(segments[i]);
            }
            if (!prerelease.isEmpty()) {
                out.append('-').append(String.join(".", prerelease));
            }
            return out.toString();
        }
    }

    /**
     * 严格解析版本串（数字段 + 可选预发布后缀）。
     * 失败消息可读（含非法片段），由调用方转成加载失败原因。
     */
    static Version parseVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("empty version");
        }
        String trimmed = version.trim();
        if (trimmed.indexOf('+') >= 0) {
            throw new IllegalArgumentException("build metadata ('+...') is not supported: \"" + version + "\"");
        }
        String numericPart = trimmed;
        List<String> prerelease = List.of();
        int dash = trimmed.indexOf('-');
        if (dash >= 0) {
            numericPart = trimmed.substring(0, dash);
            prerelease = List.copyOf(parsePrerelease(trimmed.substring(dash + 1), trimmed));
        }
        if (numericPart.isEmpty()) {
            throw new IllegalArgumentException("missing numeric version before '-': \"" + version + "\"");
        }
        String[] parts = numericPart.split("\\.", -1);
        int[] segments = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException(
                        "not a numeric dotted version: \"" + version + "\" (segment \"" + part + "\")");
            }
            try {
                segments[i] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("version segment too large: \"" + version + "\"");
            }
        }
        return new Version(segments, prerelease);
    }

    private static List<String> parsePrerelease(String prerelease, String fullVersion) {
        if (prerelease.isEmpty()) {
            throw new IllegalArgumentException("empty prerelease after '-': \"" + fullVersion + "\"");
        }
        List<String> identifiers = new ArrayList<>();
        for (String identifier : prerelease.split("\\.", -1)) {
            if (identifier.isEmpty()) {
                throw new IllegalArgumentException("empty prerelease identifier in \"" + fullVersion + "\"");
            }
            for (int i = 0; i < identifier.length(); i++) {
                char c = identifier.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '-')) {
                    throw new IllegalArgumentException("invalid character '" + c
                            + "' in prerelease identifier \"" + identifier + "\" of \"" + fullVersion
                            + "\" (allowed: [0-9A-Za-z-])");
                }
            }
            identifiers.add(identifier);
        }
        return identifiers;
    }

    /** 数值段比较：逐段比较，缺段按 0。 */
    private static int compareSegments(int[] left, int[] right) {
        int segments = Math.max(left.length, right.length);
        for (int i = 0; i < segments; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            int result = Integer.compare(a, b);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /** 版本全序比较（SemVer 式）：数字段 → 无预发布后缀更大 → 预发布标识符逐段（SemVer §11.4）。 */
    static int compare(Version left, Version right) {
        int result = compareSegments(left.segments, right.segments);
        if (result != 0) {
            return result;
        }
        boolean leftPre = !left.prerelease.isEmpty();
        boolean rightPre = !right.prerelease.isEmpty();
        if (!leftPre && !rightPre) {
            return 0;
        }
        if (!leftPre) {
            return 1;
        }
        if (!rightPre) {
            return -1;
        }
        int shared = Math.min(left.prerelease.size(), right.prerelease.size());
        for (int i = 0; i < shared; i++) {
            result = compareIdentifier(left.prerelease.get(i), right.prerelease.get(i));
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.prerelease.size(), right.prerelease.size());
    }

    /** 预发布标识符比较：数字段按数值、字母段按 ASCII、数字段低于字母段（SemVer §11.4）。 */
    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric) {
            return -1;
        }
        if (rightNumeric) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static boolean isNumeric(String identifier) {
        return !identifier.isEmpty() && identifier.chars().allMatch(Character::isDigit);
    }

    @Override
    public String toString() {
        if (alternatives.isEmpty()) {
            return "*";
        }
        List<String> groups = new ArrayList<>(alternatives.size());
        for (List<Condition> alternative : alternatives) {
            List<String> parts = new ArrayList<>(alternative.size());
            for (Condition condition : alternative) {
                parts.add(condition.toString());
            }
            groups.add(String.join(" ", parts));
        }
        return String.join(" || ", groups);
    }

    // ------------------------------------------------------------------
    // 条件
    // ------------------------------------------------------------------

    /** 比较运算符。 */
    private enum Op {
        GE(">="), LE("<="), GT(">"), LT("<"), EQ("="), CARET("^"), TILDE("~"), ANY("*");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        String symbol() {
            return symbol;
        }
    }

    /** 单条条件：运算符 + 右侧版本（{@code *} 无版本）。 */
    private record Condition(Op op, Version version) {

        boolean matches(Version actual) {
            return switch (op) {
                case ANY -> true;
                case EQ -> compare(actual, version) == 0;
                case GT -> compare(actual, version) > 0;
                case GE -> compare(actual, version) >= 0;
                case LT -> compare(actual, version) < 0;
                case LE -> compare(actual, version) <= 0;
                case CARET -> compare(actual, version) >= 0 && compare(actual, caretUpper(version)) < 0;
                case TILDE -> compare(actual, version) >= 0 && compare(actual, tildeUpper(version)) < 0;
            };
        }

        /**
         * {@code ^} 的上界（npm 规则：首个非零段进位；全零段时进位最后一个声明段）：
         * {@code ^1.2.3 → 2.0.0}；{@code ^0.2.3 → 0.3.0}；{@code ^0.0.3 → 0.0.4}；
         * {@code ^0.0 → 0.1.0}；{@code ^0 → 1.0.0}。
         */
        private static Version caretUpper(Version v) {
            int major = segment(v, 0);
            int minor = segment(v, 1);
            int patch = segment(v, 2);
            if (major != 0) {
                return upper(major + 1, 0, 0);
            }
            if (v.segments.length >= 3 && minor == 0) {
                return upper(0, 0, patch + 1);
            }
            if (v.segments.length >= 2) {
                return upper(0, minor + 1, 0);
            }
            return upper(1, 0, 0);
        }

        /**
         * {@code ~} 的上界：声明了 minor 则进位 minor（{@code ~1.2.3 → 1.3.0}），
         * 只声明 major 则进位 major（{@code ~1 → 2.0.0}）。
         */
        private static Version tildeUpper(Version v) {
            int major = segment(v, 0);
            int minor = segment(v, 1);
            if (v.segments.length >= 2) {
                return upper(major, minor + 1, 0);
            }
            return upper(major + 1, 0, 0);
        }

        private static Version upper(int major, int minor, int patch) {
            return new Version(new int[]{major, minor, patch}, List.of());
        }

        private static int segment(Version v, int index) {
            return index < v.segments.length ? v.segments[index] : 0;
        }

        @Override
        public String toString() {
            return op == Op.ANY ? "*" : op.symbol() + version;
        }
    }
}

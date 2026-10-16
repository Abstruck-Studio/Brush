package org.abstruck.brush.transform;

import java.util.Objects;

/**
 * L1 事件 id 的强类型 key（评审 P2-1 整改）—— 评审建议形态 {@code record HookEvent(String id)}。
 *
 * <p><b>为什么不是 enum</b>：派发表（{@link Dispatcher}）与计数快照
 * （{@code CoreHooks#hitCounts()} / {@code infoHitCounts()}）在运行时仍以 id 字符串为键
 * （键值即台账 / 日志 / 自检报告按之核对的原字符串，本类型不改变任何键值）；enum 的
 * 身份相等无法与 String 键互通，record + 与等值 String 互通的相等语义则可以（见下）。
 * 调用方获得的编译期安全：裸 String 字面量不再能进入 {@link Dispatcher} 的注册 /
 * 派发入口，拼错只能在编译期（而不是运行时）发现 —— 与 L2（全类型化）对称。</p>
 *
 * <p><b>equals / hashCode 与等值 String 互通（单向）</b>：{@code hookEvent.equals(idString)}
 * 为 true 且 {@code hashCode == id.hashCode()}（同款先例：Netty {@code AsciiString} 与
 * String 的互通相等）。硬约束原因：{@code CoreHooks.hitCounts()} /
 * {@code infoHitCounts()} 的快照键是 id String，自检源集（{@code src/selftest} 独立源集）
 * 以 {@code HookEvents.X} 常量跨包查询这些 map，且该源集不在本整改的可改范围 ——
 * 互通相等使既有查询（{@code getOrDefault(HookEvents.X, 0L)} 等）语义零变化。
 * 反向（{@code String.equals(hookEvent)}）为 false：{@code String#equals} 不可扩展；
 * map 键存取全部经由本类型一侧完成，不受影响。</p>
 *
 * <p><b>toString = id</b>：日志、自检报告与异常消息按 id 字符串原样打印 ——
 * {@code HookEvents.X} 常量参与字符串拼接时输出与类型化之前逐字节一致
 * （"运行时 id 值一字不改"约束的打印侧保证；值锁定断言见
 * {@code org.abstruck.brush.transform.HookEventTest}）。</p>
 *
 * <p><b>实例定义纪律</b>：事件 id 一律收敛定义在 {@link HookEvents}
 * （单一真相源；生产代码不在该类之外 {@code new HookEvent}）。构造器保持公开是
 * 评审建议 record 形态的既定事实，供测试构造专属 id（{@code DispatcherTest} 的
 * {@code test.dispatcher.*} 命名空间）。</p>
 */
public record HookEvent(String id) {

    /**
     * 规范构造器：id 非空 —— 原 {@code Dispatcher.register} 的
     * "event id must not be empty" 校验前移到类型构造期，非法 id 在创建处即失败。
     */
    public HookEvent {
        Objects.requireNonNull(id, "event id");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("event id must not be empty");
        }
    }

    /**
     * 与等值 String 互通的相等语义（见类 javadoc）：同型按 id 值比较；
     * 与 {@code String} 比较按 {@code id.equals(string)}。
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof HookEvent other) {
            return this.id.equals(other.id);
        }
        return obj instanceof String string && this.id.equals(string);
    }

    /** 与 {@code id.hashCode()} 一致 —— 保证 String 键 map 的哈希定位互通（见类 javadoc）。 */
    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    /** 返回 id 本身：日志 / 报告 / 异常消息按原字符串打印（值不变契约，见类 javadoc）。 */
    @Override
    public String toString() {
        return this.id;
    }
}

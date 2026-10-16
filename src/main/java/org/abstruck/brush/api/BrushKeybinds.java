package org.abstruck.brush.api;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import org.abstruck.brush.keybind.KeybindRegistry;

/**
 * 键位注册（L2）：让 mod 的按键出现在原版「控制」界面、参与 options.txt 持久化、可用
 * {@code KeyMapping#isDown()} 查询。
 *
 * <p><b>与 Fabric / NeoForge 同口径</b>（两家均读其本地真实 jar 实证：Fabric
 * {@code KeyMappingHelper.registerKeyMapping} + {@code KeyMappingRegistryImpl.process}；
 * NeoForge {@code RegisterKeyMappingsEvent(Options).register/registerCategory}）：
 * 入参都是<b>一个 vanilla {@code KeyMapping} 实例</b>，加载器负责把它拼进
 * {@code Options.keyMappings}，之后一切交给 vanilla。</p>
 *
 * <h2>用法</h2>
 *
 * <pre>{@code
 * // 推荐在 mod 初始化期（早于 Options 构造）执行一次：
 * KeyMapping.Category category = BrushKeybinds.registerCategory(
 *         Identifier.fromNamespaceAndPath("mymod", "keys"));
 * KeyMapping toggle = BrushKeybinds.register(new KeyMapping(
 *         "key.mymod.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
 * // 之后在每帧 tick：
 * while (toggle.consumeClick()) { ... }
 * }</pre>
 *
 * <h2>为什么必须在初始化期登记</h2>
 *
 * <p>javap 实证：{@code Options.keyMappings} 是 {@code public final KeyMapping[]}，
 * 只在 {@code Options#<init>} 里赋值一次。构造完成后再登记<b>不可能生效</b> ——
 * 因此本 API 在那种情况下会**响亮抛异常**（{@code IllegalStateException}），
 * 而不是静默无效（CLAUDE.md §7.1 形态 B 那类"不报错的失效"是本工程明令避免的）。</p>
 *
 * <p><b>三运行时一致</b>：S1 由核心自己的注入点拼接；S2/S3 下我们的注入点与宿主
 * （Fabric 的 {@code OptionsMixin} / NeoForge 的补丁）**同时**拼接 —— 两者都是"把数组接长"，
 * 互不抹除（§7.2 可链式）。</p>
 *
 * <p><b>键码常量</b>：LWJGL **不在** mod 的编译类路径上（MC 不是 Gradle 依赖），
 * 故 mod 需自备 {@code GLFW_KEY_*} 数值（GLFW 文档：可打印键码 = US 布局 ASCII 值）。</p>
 */
public final class BrushKeybinds {

    private BrushKeybinds() {
    }

    /**
     * 登记一个键位映射（在 mod 初始化期调用）。
     *
     * @param mapping vanilla {@code KeyMapping} 实例
     * @return 同一个实例，便于 {@code KeyMapping x = BrushKeybinds.register(...)}
     * @throws NullPointerException     映射为 null
     * @throws IllegalArgumentException 同名已登记
     * @throws IllegalStateException    {@code Options} 已构造（登记不可能生效）
     */
    public static KeyMapping register(KeyMapping mapping) {
        return KeybindRegistry.INSTANCE.register(mapping);
    }

    /**
     * 登记一个按键分类（26.1.2 起分类是注册式 record；不注册则「控制」界面里无处安放）。
     *
     * @param categoryId 分类 id（如 {@code mymod:keys}）
     * @return 注册后的分类实例，交给 {@code KeyMapping} 构造器
     */
    public static KeyMapping.Category registerCategory(Identifier categoryId) {
        return KeybindRegistry.INSTANCE.registerCategory(categoryId);
    }

    /** 已登记的键位数量（自检与诊断用）。 */
    public static int registeredCount() {
        return KeybindRegistry.INSTANCE.registeredCount();
    }
}

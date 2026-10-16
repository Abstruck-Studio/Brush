package org.abstruck.brush.keybind;

import java.util.Objects;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * 键位注册的 MC 适配层（内部件；L2 门面见 {@code api.BrushKeybinds}）。
 *
 * <p>语义（顺序 / 幂等 / 重名拒绝 / 迟到响亮失败）全部在 <b>零 MC 引用</b>的
 * {@link KeybindSlots} 里，并有单测覆盖；本类只做 {@code KeyMapping} 的类型适配，
 * 其正确性由三运行时的实跑断言保证（mod 侧自检读真 {@code Options.keyMappings}）。</p>
 *
 * <p><b>为什么是"先收下、再拼接"</b>：javap 实证（26.1.2）——{@code Options.keyMappings} 是
 * {@code public final KeyMapping[]}，由 {@code Options#<init>} 内部经
 * {@code Stream.of(…, keyHotbarSlots, debugKeys).flatMap(…).toArray(…)} 建好后一次性
 * {@code putfield}；此后同一构造器还会调用 {@code load()}（经 {@code processOptions} <b>读</b>该表）。
 * 因此既不能事后追加，也不能在构造结束后补写 —— 只能在<b>产生该数组的表达式</b>处接长
 * （Fabric / NeoForge 同样是"收下再拼接"，见 {@code OptionsMixin} 与 docs/29）。</p>
 */
public final class KeybindRegistry {

    /** 进程内单例（L2 门面与库模组的注入点共用）。 */
    public static final KeybindRegistry INSTANCE = new KeybindRegistry();

    /** 通用槽位（零 MC 引用，语义在此并被单测钉住）。 */
    private final KeybindSlots<KeyMapping> slots = new KeybindSlots<>();

    private KeybindRegistry() {
    }

    /**
     * 登记一个键位映射。
     *
     * @param mapping vanilla {@code KeyMapping} 实例
     * @return 同一个实例
     * @throws NullPointerException     映射为 null
     * @throws IllegalArgumentException 同名已登记
     * @throws IllegalStateException    已发生过拼接（登记不可能生效）
     */
    public KeyMapping register(KeyMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        return slots.register(mapping, mapping.getName());
    }

    /**
     * 登记一个按键分类（26.1.2 起分类是<b>注册式</b> record，不再是自由字符串）。
     *
     * @param id 分类 id
     * @return 注册后的分类实例（交给 {@code KeyMapping} 构造器）
     */
    public KeyMapping.Category registerCategory(Identifier id) {
        Objects.requireNonNull(id, "category id");
        return KeyMapping.Category.register(id);
    }

    /**
     * 把已登记的映射接在 vanilla 数组之后（供库模组的 {@code Options} 注入点调用）。见
     * {@link KeybindSlots#splice(Object[])} 的幂等与零开销语义。
     */
    public KeyMapping[] splice(KeyMapping[] vanilla) {
        return slots.splice(vanilla);
    }

    /** 已登记的键位数量。 */
    public int registeredCount() {
        return slots.registeredCount();
    }

    /** 拼接是否已发生（自检与诊断用）。 */
    public boolean spliced() {
        return slots.spliced();
    }
}

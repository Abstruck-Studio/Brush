package org.abstruck.brush.keybind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 「先登记、后在指定时机拼接」的通用槽位（<b>零 MC 引用</b>，故可被 {@code src/test} 直接单测）。
 *
 * <p>本类承载键位注册的**全部语义**，而 {@link KeybindRegistry} 只做 MC 类型的薄适配：
 * 这样"顺序 / 幂等 / 重名拒绝 / 迟到响亮失败"这些容易写错、又不会让任何构建变红的行为，
 * 能被钉在单测里（本仓 {@code src/test} 看不到 MC 类 —— 见 {@code build.gradle} 的
 * {@code compileOnly files(vanillaJar)}，它只喂 main 与 selftest 源集）。</p>
 *
 * @param <T> 槽位元素类型（键位场景下 = vanilla {@code KeyMapping}）
 */
public final class KeybindSlots<T> {

    /** 已登记待拼接的元素（保持登记顺序，拼接后追加在宿主数组之后）。 */
    private final List<T> pending = new CopyOnWriteArrayList<>();

    /** 已登记的名字（防重名）。 */
    private final Set<String> names = ConcurrentHashMap.newKeySet();

    /** 是否已发生过一次拼接（此后不再接受登记）。 */
    private volatile boolean spliced;

    /**
     * 登记一个元素。
     *
     * @param element 元素（不得为 null）
     * @param name    该元素的唯一名（键位场景下 = {@code KeyMapping.getName()}）
     * @return 同一个元素实例（便于链式书写）
     * @throws NullPointerException     元素或名字为 null
     * @throws IllegalArgumentException 同名已登记
     * @throws IllegalStateException    已发生过拼接（此时再登记不可能生效）
     */
    public T register(T element, String name) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        if (name == null) {
            throw new NullPointerException("name");
        }
        if (spliced) {
            throw new IllegalStateException("keybind '" + name
                    + "' registered too late: the key mapping table has already been built, so it could"
                    + " never appear in it. Register during mod initialization"
                    + " (the client registration window).");
        }
        if (!names.add(name)) {
            throw new IllegalArgumentException("keybind already registered: " + name);
        }
        pending.add(element);
        return element;
    }

    /**
     * 把已登记的元素接在 {@code existing} 之后。
     *
     * <p><b>幂等</b>：按<b>身份</b>已在 {@code existing} 中的元素不会重复加入 ——
     * 于是即便宿主表被构建多次也不会出现重复条目。同时这也让本方法可以安全地被
     * <b>多个</b>拼接方（S2/S3 下宿主自己也会拼）叠加调用。</p>
     *
     * <p><b>零开销</b>：无登记时<b>原样返回入参</b>（不复制）。</p>
     *
     * @param existing 宿主/已有的数组（不得为 null）
     * @return 拼接后的数组（可能是入参本身）
     */
    public T[] splice(T[] existing) {
        if (existing == null) {
            throw new NullPointerException("existing");
        }
        spliced = true;
        if (pending.isEmpty()) {
            return existing;
        }
        List<T> extra = new ArrayList<>(pending.size());
        for (T candidate : pending) {
            boolean alreadyThere = false;
            for (T present : existing) {
                if (present == candidate) {
                    alreadyThere = true;
                    break;
                }
            }
            if (!alreadyThere) {
                extra.add(candidate);
            }
        }
        if (extra.isEmpty()) {
            return existing;
        }
        @SuppressWarnings("unchecked")
        T[] merged = (T[]) java.lang.reflect.Array.newInstance(
                existing.getClass().getComponentType(), existing.length + extra.size());
        System.arraycopy(existing, 0, merged, 0, existing.length);
        for (int i = 0; i < extra.size(); i++) {
            merged[existing.length + i] = extra.get(i);
        }
        return merged;
    }

    /** 已登记的元素数量。 */
    public int registeredCount() {
        return pending.size();
    }

    /** 拼接是否已发生（自检与诊断用）。 */
    public boolean spliced() {
        return spliced;
    }
}

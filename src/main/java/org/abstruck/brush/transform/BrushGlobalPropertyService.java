package org.abstruck.brush.transform;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brush 自带的全局属性服务（Mixin 的 blackboard）。
 *
 * <p><b>为什么必须自实现</b>：sponge-mixin jar 只注册了两个 {@code IGlobalPropertyService}
 * 提供者 —— {@code ...service.mojang.Blackboard}（依赖 LaunchWrapper 的 {@code Launch.classLoader}）
 * 与 {@code ...service.modlauncher.Blackboard}（依赖 ModLauncher），两者在 Brush 的类路径上都会
 * 因缺类而实例化失败；而 {@code MixinService.initPropertyService()} 对"无可用提供者"是<b>抛错</b>
 * （{@code ServiceNotAvailableError}），不是返回默认值。同时 {@code MixinBootstrap.init()} 在
 * 第一行就会经 {@code GlobalProperties} 访问该服务，所以它在 standalone 管线里是<b>必需件</b>。</p>
 *
 * <p>实现语义对齐 ModLauncher blackboard：按名称存取任意对象，惰性解析 key。
 * 线程安全（ConcurrentHashMap）：类变换是多线程进行的（BrushClassLoader 已注册
 * parallel capable），mixin 与 MixinExtras 都可能在变换期读写属性。</p>
 *
 * <p><b>注意</b>：MixinExtras 的 {@code Blackboard.put} 会先存入 {@code new Object[1]} 再对其
 * 元素赋值（"值容器"惯用法），因此本实现必须存储<b>对象引用本身</b>，不得复制值。</p>
 */
public final class BrushGlobalPropertyService implements IGlobalPropertyService {

    private final Map<String, Object> properties = new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        return new Key(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) this.properties.get(key.toString());
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        this.properties.put(key.toString(), value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object value = this.properties.get(key.toString());
        return value == null ? defaultValue : (T) value;
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = this.properties.get(key.toString());
        return value == null ? defaultValue : String.valueOf(value);
    }

    /**
     * {@link IPropertyKey} 是空标记接口；以名称实现等价语义。
     */
    private static final class Key implements IPropertyKey {

        private final String name;

        Key(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Key other && this.name.equals(other.name);
        }

        @Override
        public int hashCode() {
            return this.name.hashCode();
        }
    }
}

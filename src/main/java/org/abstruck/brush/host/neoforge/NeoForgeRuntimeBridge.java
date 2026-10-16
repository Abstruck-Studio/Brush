package org.abstruck.brush.host.neoforge;

import net.neoforged.fml.loading.FMLLoader;
import org.abstruck.brush.mod.RuntimeBridge;
import org.abstruck.brush.mod.RuntimeKind;
import org.abstruck.brush.transform.MixinConfigRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

/**
 * S3（NeoForge 宿主）运行时接面。
 *
 * <p><b>暴露机制</b>：取游戏加载器（{@code FMLLoader.getCurrent().getCurrentClassLoader()}，
 * 即 {@code net.neoforged.fml.classloading.transformation.TransformingClassLoader}），
 * 把其 {@code fallbackClassLoader} 字段（"未命中任何模块的包"的回退目标）包裹为
 * {@link ModJarFallbackLoader}（parent = 原 fallback，moduleLoader = 游戏加载器本身 ——
 * mod 类定义的符号引用经模块兜底解析，机理见该类 javadoc）—— mod 类由此
 * 可加载、mod 资源由此可读。字段读写走反射（{@code fallbackClassLoader} 为私有；FML 类型
 * 本身的引用与既有 {@code BrushNeoForge} 同款 —— 编译期 compileOnly、运行期来自 FML 模块）。
 *
 * <p><b>mixin 配置注册</b>：{@code FMLMixinService.getResourceAsStream} 在其自有
 * {@code mixinConfigContents} 未命中时回退到 {@code Thread.currentThread().getContextClassLoader()}
 * —— 因此注册在 <b>TcCL 窗口</b>内进行：窗口期 TcCL = {@link ModJarFallbackLoader}
 * （读得到 mod jar 里的配置），注册完恢复原 TcCL。配置解析（prepare）由窗口内的
 * {@link #forceProcessorReselect()} 当场触发（FML 自身的惰性选择在 S3 时机下已关门，
 * 见该方法 javadoc），后续不再重读该资源。
 *
 * <p><b>帧计算字节读取</b>：FML 的 {@code TransformerClassWriter} 在做
 * 帧合并时按"类名 → 层级"回读字节（{@code computeHierarchy}）；而
 * {@code ModuleClassLoader.getMaybeTransformedClassBytes} 读字节只有两个来源：模块包走
 * {@code packageLookup}，其余包走 <b>{@code parentLoaders} 映射</b>（包名 → ClassLoader，
 * 对映射到的加载器做 {@code getResourceAsStream(path)}）。未映射的包读到空字节 → {@code CNFE}
 * → 再退到 {@code locateParentClass}（父加载器同样没有 mod jar）→
 * {@code FATAL "Failed to find class"} 并中止。因此本类暴露 jar 时把 jar 内<b>全部包名</b>注册进
 * {@code parentLoaders}（值 = wrapper：父优先未命中后自 jar 取流；{@code putIfAbsent} 不覆盖
 * FML 既有条目）。仅影响"未映射包"的字节回读；类加载路径不变（仍走 fallbackClassLoader）。
 * brush 自身是正规 FML 模块（包走 packageLookup），不需要此映射 —— 这正是宿主生态 mod 与
 * brush 加载的 mod 走不同读取通道的分界。
 *
 * <p><b>与 S2 的差异</b>：S3 的 mixin 类字节读取通路在变换期由 FML 自己的
 * bytecode provider 主导，其 TcCL 回退行为取决于变换发生时的线程 —— T3 自检以"mod 自带 mixin
 * 命中"为断言闭环实证；若失败按 §13 排查（本桥接不预置任何未经证据的补救层）。
 * mod mixin 类本身由本 wrapper 定义后的符号引用（{@code net.minecraft.*} 等）经
 * {@link ModJarFallbackLoader} 的模块兜底解析 —— 与 mod 入口类的解析走同一条通路。
 */
public final class NeoForgeRuntimeBridge implements RuntimeBridge {

    private static final Logger LOGGER = LogManager.getLogger(NeoForgeRuntimeBridge.class);

    // 懒初始化字段（P1-8）：volatile + init() 整体同步 —— 未同步时另一线程可能
    // 看到 initialized=true 但 wrapper / parentLoaders 仍为 null（部分初始化状态可见）。
    private volatile ModJarFallbackLoader wrapper;

    /** 游戏加载器 {@code parentLoaders}（帧计算字节读取的包 → 读取器映射，见类 javadoc）。 */
    private volatile Map<String, ClassLoader> parentLoaders;

    private volatile boolean initialized;

    @Override
    public RuntimeKind kind() {
        return RuntimeKind.NEOFORGE;
    }

    @Override
    public void exposeModJar(Path jar) throws IOException {
        init();
        wrapper.addJar(jar);
        int packages = mapPackagesForFrameComputation(jar);
        LOGGER.info("[brush]   neoforge bridge: exposed " + jar.getFileName()
                + " via fallback wrapper (total " + wrapper.jars().size() + " mod jar(s), "
                + packages + " package(s) mapped for frame computation)");
    }

    /**
     * 把 jar 内全部包名注册进游戏加载器的 {@code parentLoaders}（帧计算字节读取通道，
     * 机理见类 javadoc）。{@code putIfAbsent}：不覆盖 FML 既有条目。
     *
     * @return 本次新增的包数
     */
    private int mapPackagesForFrameComputation(Path jar) throws IOException {
        int added = 0;
        for (String pkg : ModJarFallbackLoader.packagesIn(jar)) {
            if (parentLoaders.putIfAbsent(pkg, wrapper) == null) {
                added++;
            }
        }
        return added;
    }

    @Override
    public void registerModMixinConfig(String configResource) throws IOException {
        init();
        ClassLoader originalTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(wrapper);
        try {
            MixinConfigRegistry.registerAndVerify(configResource);
            forceProcessorReselect();
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalTccl);
        }
        LOGGER.info("[brush]   neoforge bridge: registered " + configResource
                + " (TcCL window over exposed mod jars)");
    }

    /**
     * 强制 FML 的 mixin 处理器重新选择配置 —— <b>S3 专有的时机补救</b>（S1/S2 无需，见下）。
     *
     * <p><b>为什么必需</b>：S3 下我们最早的可运行时机晚于首批 mixin 应用，
     * FML 的处理器唯一一轮 {@code Selecting config ...} 已经结束（transformedCount 已
     * &gt; 0），此后再无选择，配置永不 prepare/apply —— 远端表现是 example mod 的
     * accessor 强转 {@code ClassCastException}。
     *
     * <p><b>机理</b>：{@code MixinProcessor.checkSelect}（net.fabricmc
     * sponge-mixin 0.17.4+mixin.0.8.7 —— 与 FML 11.0.15 随附的为同一构件）仅在
     * “当前环境 ≠ 目标环境”或“待选数 &gt; 0 且 transformedCount == 0”时才 select；S3 下我们
     * 最早的可运行时机（Bootstrap.bootStrap 锚点）晚于首批 mixin 应用（sodium / brush 自身
     * 的 BootstrapMixin），transformedCount 已非 0，门永久关闭。S1 注册时 transformedCount
     * 仍为 0；S2 走 preLaunch，早于任何变换 —— 两者天然不需要本方法。
     *
     * <p><b>反射链</b>：{@code MixinService.getService()} →（S3 下为
     * {@code net.neoforged.fml.loading.mixin.FMLMixinService}）公开方法
     * {@code getMixinTransformer()}（其值链上无包装层）；{@code MixinTransformer} 持有
     * {@code processor} 字段（MixinProcessor），私有 {@code select(MixinEnvironment)} 即门控入口。
     * 任一步失败 = 可读硬失败（与 fallbackClassLoader 同一策略：机制按 FML 11.0.15 字节码
     * 写死，漂移即硬失败）。
     *
     * <p><b>为何必须在 TcCL 窗口内</b>：select 会当场 prepare 新配置（配置内容经
     * {@code FMLMixinService.getResourceAsStream} 的 TcCL 回退读取，见类 javadoc），
     * 窗口关闭后再 prepare 就读不到 mod jar 里的配置资源。副作用：prepare 失败会同步抛出，
     * 注册期的校验因此比惰性选择更强（静默失效窗口更小）。
     */
    private static void forceProcessorReselect() throws IOException {
        Object service = MixinService.getService();
        Object transformer;
        try {
            Method getTransformer = service.getClass().getMethod("getMixinTransformer");
            transformer = getTransformer.invoke(service);
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IOException("neoforge bridge: cannot obtain FML mixin transformer from "
                    + service.getClass().getName() + " (expected a public getMixinTransformer();"
                    + " taken from FML 11.0.15): " + e, e);
        }
        if (transformer == null) {
            throw new IOException("neoforge bridge: FML mixin transformer is null (the mixin service"
                    + " has not been initialized yet; reselect cannot run)");
        }
        Field processorField = findField(transformer.getClass(), "processor");
        if (processorField == null) {
            throw new IOException("neoforge bridge: " + transformer.getClass().getName()
                    + " declares no processor field in its hierarchy (taken from sponge-mixin"
                    + " 0.17.4 bytecode; unsupported mixin build?)");
        }
        Object processor;
        try {
            processorField.setAccessible(true);
            processor = processorField.get(transformer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("neoforge bridge: cannot read processor field of "
                    + transformer.getClass().getName() + ": " + e, e);
        }
        try {
            Method select = processor.getClass().getDeclaredMethod("select", MixinEnvironment.class);
            select.setAccessible(true);
            select.invoke(processor, MixinEnvironment.getCurrentEnvironment());
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("neoforge bridge: cannot force mixin reselect on "
                    + processor.getClass().getName() + " (expected a private"
                    + " select(MixinEnvironment); taken from sponge-mixin 0.17.4 bytecode): " + e, e);
        }
    }

    /**
     * 包裹游戏加载器的 {@code fallbackClassLoader}，并取出 {@code parentLoaders}
     * （帧计算字节读取的包 → 读取器映射；两者机理见类 javadoc）。字段缺失 = 可读硬失败：
     * 机制按 FML 11.0.15 字节码写死，漂移即硬失败（不静默降级；宿主升级后重跑矩阵）。
     */
    private synchronized void init() throws IOException {
        if (initialized) {
            return;
        }
        FMLLoader fml = FMLLoader.getCurrent();
        if (fml == null) {
            throw new IOException("neoforge bridge: FMLLoader.getCurrent() is null"
                    + " (the bridge was used outside FML startup)");
        }
        ClassLoader gameLoader = fml.getCurrentClassLoader();
        if (gameLoader == null) {
            throw new IOException("neoforge bridge: FMLLoader.getCurrentClassLoader() is null"
                    + " (invoked before the game class loader exists?)");
        }
        Field fallbackField = findField(gameLoader.getClass(), "fallbackClassLoader");
        if (fallbackField == null) {
            throw new IOException("neoforge bridge: class " + gameLoader.getClass().getName()
                    + " declares no fallbackClassLoader field in its hierarchy (taken from FML 11.0.15"
                    + " bytecode; unsupported loader version?)");
        }
        fallbackField.setAccessible(true);
        Object original;
        try {
            original = fallbackField.get(gameLoader);
        } catch (IllegalAccessException e) {
            throw new IOException("neoforge bridge: cannot read fallbackClassLoader of "
                    + gameLoader.getClass().getName() + ": " + e, e);
        }
        if (original instanceof ModJarFallbackLoader existing) {
            wrapper = existing;   // 幂等（本进程单例桥接，防御性保留）
        } else if (original == null) {
            throw new IOException("neoforge bridge: fallbackClassLoader of "
                    + gameLoader.getClass().getName() + " is null; unexpected FML state, refusing to patch");
        } else {
            wrapper = new ModJarFallbackLoader((ClassLoader) original, gameLoader);
            try {
                fallbackField.set(gameLoader, wrapper);
            } catch (IllegalAccessException e) {
                throw new IOException("neoforge bridge: cannot install the fallback wrapper on "
                        + gameLoader.getClass().getName() + ": " + e, e);
            }
        }
        Field parentLoadersField = findField(gameLoader.getClass(), "parentLoaders");
        if (parentLoadersField == null) {
            throw new IOException("neoforge bridge: class " + gameLoader.getClass().getName()
                    + " declares no parentLoaders field in its hierarchy (taken from FML 11.0.15"
                    + " ModuleClassLoader bytecode; unsupported loader version?)");
        }
        parentLoadersField.setAccessible(true);
        try {
            @SuppressWarnings("unchecked")
            Map<String, ClassLoader> map = (Map<String, ClassLoader>) parentLoadersField.get(gameLoader);
            parentLoaders = map;
        } catch (IllegalAccessException e) {
            throw new IOException("neoforge bridge: cannot read parentLoaders of "
                    + gameLoader.getClass().getName() + ": " + e, e);
        }
        if (parentLoaders == null) {
            throw new IOException("neoforge bridge: parentLoaders of "
                    + gameLoader.getClass().getName() + " is null; unexpected FML state");
        }
        initialized = true;
        LOGGER.info("[brush] neoforge bridge initialized: game loader=" + gameLoader.getClass().getName()
                + ", fallback wrapped (parent " + wrapper.getParent() + "), frame parent-packages map"
                + " ready (" + parentLoaders.size() + " pre-existing entries), TcCL now "
                + Thread.currentThread().getContextClassLoader().getClass().getName());
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续沿父类链查找
            }
        }
        return null;
    }
}

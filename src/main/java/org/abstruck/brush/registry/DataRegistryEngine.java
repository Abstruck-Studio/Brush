package org.abstruck.brush.registry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.RegistryValidator;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceManagerRegistryLoadTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;

import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Brush 数据包注册表引擎（G1，docs/21）—— mod 自定义数据包注册表的装载实现。
 *
 * <p>承载 {@code api.BrushDataRegistries} 登记的（键 → codec）清单，按 vanilla 数据包语义装载：
 * 条目文件 {@code data/<数据包命名空间>/<键路径>/*.json}（vanilla 机制布局；宿主命名空间布局
 * {@code data/<数据包命名空间>/<注册表命名空间>/<键路径>/*.json} 由 {@link BridgingResourceManager}
 * 桥接，两布局均可读、同名以 vanilla 机制布局优先，见其 javadoc）→ 条目 id
 * {@code <数据包命名空间>:<文件名>}（ns 来自<b>数据包目录</b>，与注册表键的 ns 无关）；
 * 多包按包序合并（后加载覆盖）。
 * 装载触发点 = 集成服务器构造完成（{@code data.loaded}）与数据重载完成（{@code data.reload}），
 * 两个 L1 锚点的 handler 调用本引擎（见 {@code MinecraftServerMixin}）。</p>
 *
 * <p><b>为什么是 Brush 自有的 RegistryAccess</b>：26.1.2 已删除 vanilla 服务器侧"数据包注册表可重载"
 * 路径（javap 实证：4 参 {@code RegistryDataLoader.load(ResourceManager,...)} 仅被 {@code WorldLoader}
 * 以 {@code WORLDGEN_REGISTRIES} / {@code DIMENSION_REGISTRIES} 调用 —— worldgen 层序列化进 level.dat、
 * 不可扩展（卸载 mod 即坏档）；{@code /reload}（{@code MinecraftServer.reloadResources} →
 * {@code ReloadableServerRegistries.reload}）只重载 loot 注册表）。因此方案 A 无法原样实现，
 * 经用户批准改为"注册表装在 Brush 自有 RegistryAccess 中"（docs/21 决议记录）。</p>
 *
 * <p><b>装载机制全部复用 vanilla 公开 API（零新增注入点）</b>（javap 实证清单）：
 * {@code RegistryDataLoader$RegistryData} 公开 record 构造器
 * {@code (ResourceKey, Codec, RegistryValidator)} + {@code RegistryValidator.none()}；
 * {@code ResourceManagerRegistryLoadTask} 公开构造器
 * {@code (RegistryData, Lifecycle, Map, ResourceManager)}（内部完成 scan + decode + registerElements +
 * tags）+ 继承自 {@code RegistryLoadTask} 的公开 {@code createRegistryInfo()} /
 * {@code freezeRegistry(Map)} / {@code validateRegistry(Map)}；{@code RegistryOps$RegistryInfoLookup}
 * （<b>泛型方法接口</b> —— 须匿名类实现，lambda 不可）；{@code Lifecycle.experimental()}（DFU）；
 * {@code RegistryAccess$ImmutableRegistryAccess} 公开构造器 + {@code freeze()}。
 * 两个注入点（{@code MinecraftServer#<init>} RETURN / {@code reloadResources} 包装）只承担
 * <b>时机</b>（何时装载），不承担装载逻辑本身。</p>
 *
 * <p><b>装载算法</b>（{@link #reload(ResourceManager)}）：① 快照登记清单，空则短路；
 * ② 按登记序为每项建 {@code ResourceManagerRegistryLoadTask}（共享一张 loadingErrors 表）；
 * ③ 跨注册表查找器 = 稀疏查表（{@code tasks.get(key)}，与 vanilla 同构；支持被登记表之间的
 * holder 引用）；④ 逐任务 {@code load(lookup, Runnable::run)} 后 {@code allOf(...).join()}
 * （同线程 executor：决定性、重入安全、冷路径）；⑤ 冻结阶段（登记序）—— 任一
 * {@code freezeRegistry} 失败即逐键 ERROR + 保留旧视图 + 返回 false；⑥ 校验阶段
 * （必须全部冻结后进行 —— 跨表引用在 freeze 期绑定）—— 任一 {@code validateRegistry} 为空即同上；
 * ⑦ 全部通过 → {@code ImmutableRegistryAccess.freeze()} 原子替换当前视图。</p>
 *
 * <p><b>错误语义（与 vanilla 的差异，docs/21 登记）</b>：数据错误不中止世界加载 ——
 * 逐键 ERROR 日志 + 旧表保留（首次装载失败 = 空视图，mod 经
 * {@code BrushRegistry.dataRegistry(key)} 空判降级）。理由：Brush 表独立于 vanilla 数据，
 * 不应因一条坏 JSON 让整个存档不可进入（vanilla 对 worldgen 层是致命错误，因为那是存档数据本身）。</p>
 *
 * <p><b>键身份（实现者注意）</b>：{@code ResourceKey} 在 26.1.2 <b>没有</b> equals/hashCode 覆写
 * （javap 实证）—— 身份相等。查找器与本类的错误表均以键<b>实例</b>为键：mod 必须以同一键实例
 * （静态常量）登记并在 codec 中引用自己的注册表；不同实例即便标识符相同也不会命中
 * （重复登记按标识符拒绝，见 {@link #register}）。</p>
 *
 * <p><b>异步异常偏离（§3.5，有意）</b>：{@link #onReloadCompleted} 运行在
 * {@code CompletableFuture.whenComplete} 回调线程内 —— 该上下文里<b>任何</b>重抛的异常都会被
 * CompletableFuture 静默吞掉（无日志、无传播，违反"不静默"原则的最坏形态）。因此该方法是
 * 本工程唯一对"fail-fast 重抛"的有意偏离点：整体 catch(RuntimeException | Error) + ERROR 日志
 * （附事件 id 与说明）。同步路径（{@link #onServerConstructed}）不偏离 —— 异常照常上抛。</p>
 *
 * <p><b>线程模型</b>：登记（{@link #register}）在内容注册窗口线程（锁内 check+add，幂等拒绝重复键）；
 * 重载不加锁 —— 登记清单为 {@code CopyOnWriteArrayList}（无锁快照），视图为 volatile
 * 原子替换（并发重载 = 冗余工作、各自产出完整一致视图后 last-writer-wins，不会出现半态）。
 * 读取（{@link #lookup}）任意线程、无锁。</p>
 *
 * <p><b>类加载</b>：本类引用 MC 类型 —— 首次被引用必然晚于 bootstrap（{@code MinecraftServerMixin}
 * 的 handler 在服务端构造期执行；{@code api} 门面只在注册 / 读取调用期触达）—— 不进入引导期
 * 类加载链（与 {@code ClientRegistrationBuffer} 的"零 MC"纪律不冲突：那个类被引导期代码引用，
 * 本类不被）。</p>
 */
public final class DataRegistryEngine {

    /** 游戏侧 log4j（MC 上下文，落 run/logs/latest.log）。 */
    private static final Logger LOGGER = LogManager.getLogger(DataRegistryEngine.class);

    /** 登记 / 重复键检查的互斥锁（重载不持本锁 —— 见类 javadoc 线程模型）。 */
    private static final Object MUTEX = new Object();

    /** 登记清单（登记序；CopyOnWriteArrayList —— 重载方无锁快照）。 */
    private static final List<RegistryDataLoader.RegistryData<?>> REGISTRATIONS =
            new CopyOnWriteArrayList<>();

    /** 最近一次成功装载的视图；null = 从未装载（读取方空判）。 */
    private static volatile RegistryAccess.Frozen CURRENT;

    private DataRegistryEngine() {
    }

    /**
     * 登记一个数据包注册表（调用方 = {@code api.BrushDataRegistries} 门面 —— 那里做 mod 上下文
     * 守卫与归账；本方法只做清单维护）。
     *
     * @param key   注册表键（须与 codec 中引用该注册表的键为<b>同一实例</b>，见类 javadoc 键身份）
     * @param codec 条目 codec
     * @return true = 已登记；false = 标识符重复（已有同标识符注册表 —— 由门面转为显式拒绝）
     */
    public static <T> boolean register(ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(codec, "codec");
        RegistryDataLoader.RegistryData<T> data =
                new RegistryDataLoader.RegistryData<>(key, codec, RegistryValidator.none());
        synchronized (MUTEX) {
            for (RegistryDataLoader.RegistryData<?> existing : REGISTRATIONS) {
                // 按标识符判重（覆盖"不同实例、同标识符"的重复登记；键本身为身份相等，不能依赖 equals）。
                // identifier() = 键自身位置（注册表 id）；registry() 是"所属注册表"—— 注册表键的父恒为
                // root，registry() 恒为 minecraft:root，用作判重会恒真（javap + T1 实测双重确认）。
                if (existing.key().identifier().equals(key.identifier())) {
                    return false;
                }
            }
            REGISTRATIONS.add(data);
        }
        return true;
    }

    /**
     * 读取当前视图中的注册表；未装载 / 未知键 → 空。
     * public：{@code api.BrushRegistry.dataRegistry} 门面与 selftest 探针读取用（只读、无副作用）。
     */
    public static <T> Optional<Registry<T>> lookup(ResourceKey<? extends Registry<T>> key) {
        Objects.requireNonNull(key, "key");
        RegistryAccess.Frozen current = CURRENT;
        return current == null ? Optional.empty() : current.lookup(key);
    }

    /** 已登记的注册表数（selftest 探针的分支依据：0 = 无登记，跳过而非失败）。 */
    public static int registrationCount() {
        return REGISTRATIONS.size();
    }

    /**
     * 最近一次成功装载的只读视图（null = 从未装载）。selftest 探针遍历 / 逐表读条目做断言用；
     * mod 侧读取入口是 {@code api.BrushRegistry.dataRegistry(key)}（本方法只是其探针面 ——
     * 探针无键实例，遍历视图即可覆盖全部已登记表）。
     * public：与 {@link #reload} 同类（自检源集直接调用；只读、无副作用）。
     */
    public static RegistryAccess.Frozen currentAccess() {
        return CURRENT;
    }

    /**
     * {@code data.loaded} 锚点的引擎入口（{@code MinecraftServer#<init>} RETURN，构造线程）：
     * 先装载，再派发事件 —— 事件监听器读到的是最新视图。
     *
     * <p>派发在 {@code finally} 中（先例 {@code ModResourcePacks.attach}）：装载失败（返回 false，
     * 已逐键记 ERROR）事件照发 —— 监听器看到的是旧表 / 空表并自行降级；装载<b>异常</b>
     * （意外 bug）在 finally 派发后原样上抛（同步路径不偏离 fail-fast，见类 javadoc）。</p>
     */
    public static void onServerConstructed(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        try {
            reload(server.getResourceManager());
        } finally {
            Dispatcher.fire(HookEvents.DATA_LOADED, server);
        }
    }

    /**
     * {@code data.reload} 锚点的引擎入口（{@code reloadResources} 的 {@code whenComplete} 回调，
     * 异步完成线程）：vanilla 重载失败 → 跳过装载、载荷带原异常；成功 → 重载后派发。
     * Brush 表重载失败（返回 false）→ 载荷带说明"旧表保留"的 ISE。
     *
     * <p><b>本方法整体 catch + ERROR 是有意偏离 fail-fast（§3.5）</b>—— 原因见类 javadoc
     * "异步异常偏离"节：CompletableFuture 回调里重抛 = 异常被静默吞掉，记录并继续比静默好。</p>
     */
    public static void onReloadCompleted(MinecraftServer server, Throwable error) {
        try {
            Optional<Throwable> payload;
            if (error != null) {
                payload = Optional.of(error);
            } else if (reload(server.getResourceManager())) {
                payload = Optional.empty();
            } else {
                payload = Optional.of(new IllegalStateException(
                        "Brush datapack registry reload failed; the previous tables are kept"
                                + " (per-registry causes in the ERROR lines above)"));
            }
            Dispatcher.fire(HookEvents.DATA_RELOADED, server, payload);
        } catch (RuntimeException | Error t) {
            LOGGER.error("[brush] hook " + HookEvents.DATA_RELOADED + " failed in the asynchronous"
                    + " whenComplete context; exception logged instead of rethrown because"
                    + " CompletableFuture would silently swallow it (deliberate deviation from"
                    + " fail-fast; see DataRegistryEngine javadoc)", t);
        }
    }

    /**
     * 按当前登记清单装载全部注册表（算法见类 javadoc）。
     *
     * @return true = 全部装载成功（视图已原子替换；无登记时短路为 true、视图不变）；
     *         false = 某项失败（逐键 ERROR 已记录；旧视图保留）
     * public：selftest 探针直接调用（以 {@code MultiPackResourceManager} 构造独立数据资源栈，
     * 与真实数据流走同一代码路径 —— 无服务器也可测）。
     */
    public static boolean reload(ResourceManager manager) {
        Objects.requireNonNull(manager, "manager");
        List<RegistryDataLoader.RegistryData<?>> snapshot = List.copyOf(REGISTRATIONS);
        if (snapshot.isEmpty()) {
            return true;    // 短路：不建任务、不换视图（CURRENT 保持 null / 原值）
        }
        // 宿主目录布局桥接（docs/21 §3/§5）：Fabric（fabric-registry-sync-v0 的 RegistriesMixin）与
        // NeoForge（CommonHooks.prefixNamespace 补丁）对非 minecraft 注册表前置命名空间，vanilla 机制
        // 是裸路径——桥接使两种布局在三运行时下均可读（同名以 vanilla 机制布局优先）。
        ResourceManager effective = BridgingResourceManager.wrapIfNeeded(manager, snapshot);
        if (effective != manager) {
            LOGGER.info("[brush] datapack registry dir bridging enabled (" + snapshot.size()
                    + " registry(ies)): both 'data/<pack-ns>/<path>/' (vanilla mechanism) and"
                    + " 'data/<pack-ns>/<registry-ns>/<path>/' (host-namespaced) are read;"
                    + " vanilla-mechanism files win on same-id collisions");
        }
        Map<ResourceKey<?>, Exception> loadingErrors = new LinkedHashMap<>();
        Map<ResourceKey<?>, ResourceManagerRegistryLoadTask<?>> tasks = new LinkedHashMap<>();
        for (RegistryDataLoader.RegistryData<?> data : snapshot) {
            tasks.put(data.key(), newTask(data, loadingErrors, effective));
        }
        // 跨注册表查找器（稀疏，与 vanilla 同构）：只应答本次装载集中的键 ——
        // 被登记表之间的 holder 引用在解码期经此解析。须匿名类：接口方法是泛型方法，lambda 不可。
        RegistryOps.RegistryInfoLookup registryInfoLookup = new RegistryOps.RegistryInfoLookup() {
            @Override
            @SuppressWarnings("unchecked") // 唯一强转点：通配捕获 T 无法静态表达（键已限定到本表）
            public <T> Optional<RegistryOps.RegistryInfo<T>> lookup(
                    ResourceKey<? extends Registry<? extends T>> key) {
                ResourceManagerRegistryLoadTask<?> task = tasks.get(key);
                return task == null
                        ? Optional.empty()
                        : Optional.of((RegistryOps.RegistryInfo<T>) task.createRegistryInfo());
            }
        };
        try {
            CompletableFuture<?>[] loading = tasks.values().stream()
                    .map(task -> task.load(registryInfoLookup, Runnable::run))
                    .toArray(CompletableFuture[]::new);
            // 同线程 executor（Runnable::run）：join 即全部完成；决定性、重入安全、冷路径。
            CompletableFuture.allOf(loading).join();
        } catch (RuntimeException e) {
            LOGGER.error("[brush] datapack registry load failed; the previous tables (or none,"
                    + " before the first successful load) are kept: " + e, e);
            return false;
        }
        // 冻结阶段（登记序 —— 顺序即"后登记引用先登记"的确定性依据）
        for (Map.Entry<ResourceKey<?>, ResourceManagerRegistryLoadTask<?>> entry : tasks.entrySet()) {
            if (!entry.getValue().freezeRegistry(loadingErrors)) {
                logFailure("freeze", entry.getKey(), loadingErrors);
                return false;
            }
        }
        // 校验阶段：必须全部冻结后进行（跨表引用在 freeze 期解析 —— 先校验后冻结会误报未绑定）；
        // 汇总各表冻结后的只读视图，供最终 RegistryAccess 构建。
        List<Registry<?>> registries = new ArrayList<>(tasks.size());
        for (Map.Entry<ResourceKey<?>, ResourceManagerRegistryLoadTask<?>> entry : tasks.entrySet()) {
            Optional<? extends Registry<?>> validated = entry.getValue().validateRegistry(loadingErrors);
            if (validated.isEmpty()) {
                logFailure("validate", entry.getKey(), loadingErrors);
                return false;
            }
            registries.add(validated.get());
        }
        CURRENT = new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
        LOGGER.info("[brush] datapack registries loaded: " + registries.size() + " registry(ies)");
        return true;
    }

    /** 泛型捕获辅助：{@code RegistryData<?>} 无法直接构造带类型参数的任务，经此方法捕获 T。 */
    private static <T> ResourceManagerRegistryLoadTask<T> newTask(
            RegistryDataLoader.RegistryData<T> data,
            Map<ResourceKey<?>, Exception> loadingErrors,
            ResourceManager manager) {
        return new ResourceManagerRegistryLoadTask<>(
                data, Lifecycle.experimental(), loadingErrors, manager);
    }

    /**
     * 失败逐键日志（错误表由 {@code freezeRegistry} / {@code validateRegistry} 以
     * <b>注册表键</b>为键写入 —— javap 实证：{@code errors.put(registry.key(), exception)}；
     * 该键与 {@code data.key()} 为同一实例（任务内部以 {@code data.key()} 建注册表））。
     */
    private static void logFailure(String phase, ResourceKey<?> key,
                                   Map<ResourceKey<?>, Exception> errors) {
        Exception cause = errors.get(key);
        LOGGER.error("[brush] datapack registry '" + key + "' failed to " + phase
                + " (data error); the previous tables (or none, before the first successful"
                + " load) are kept" + (cause == null ? "" : ": " + cause.getMessage()), cause);
    }
}

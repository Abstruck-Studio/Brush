package org.abstruck.brush.api;

import java.util.Objects;

import net.minecraft.resources.Identifier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Brush 网络门面（L2）—— mod 注册通道与发送载荷的唯一入口。
 *
 * <p><b>用法</b>（mod 入口构造期即可注册；发送需已连接服务器）：
 * <pre>{@code
 * BrushNetwork.register(Identifier.fromNamespaceAndPath("mymod", "hello"));   // 默认上限
 * BrushEvents.NETWORK_PAYLOAD.register((channel, data) -> { ... });           // 接收
 * // 已连接时（例如 CLIENT_TICK 内检查 getConnection() != null 后）：
 * BrushNetwork.send(Identifier.fromNamespaceAndPath("mymod", "hello"), bytes);
 * }</pre>
 *
 * <p><b>实现位置（2026-10-15，docs/26 §7.7）</b>：网络**引擎**（载荷类型 / 通道注册表 /
 * 去重 / 回包校验 / S3 载荷转注册桥）已整体搬进**库模组** {@code brush-api}
 * （{@code org.abstruck.brush.net.*} 与 {@code api.impl.host.*}）。本类只是一层**门面**，
 * 因为 {@code org.abstruck.brush.api} 是**核心模块拥有的包**：宿主（FML）加载的代码
 * —— 例如自检夹具 jar —— 只会去该模块里找这个包下的类，**找不到也不会回退**到我们的 mod 加载器
 * （机理见 {@code host.neoforge.ModJarFallbackLoader} 的类 javadoc；2026-10-15 实机回归即
 * {@code NoClassDefFoundError: org/abstruck/brush/api/BrushNetwork}）。于是：
 * <ul>
 *   <li>引擎与实现全在库模组；核心除本门面外**零网络类型引用**（无 core → mod 依赖）；</li>
 *   <li>实现经 {@link #install(Impl)} 装入（库模组入口安装；门面另有**惰性解析**兜底，
 *       使装载顺序不影响可用性 —— 依赖的是"库模组产物可见"，而不是"谁的 onInitialize 先跑"）；</li>
 *   <li>因此**作者侧编译面不变**：用 {@code BrushNetwork} 只需核心产物 / 契约 jar；
 *       只有直接用引擎内部件（{@code BrushChannels} 等）才需要库模组产物。</li>
 * </ul>
 *
 * <p><b>机制</b>：注册落进引擎的通道注册表（{@code Identifier →} 载荷 codec）；
 * 编解码经 vanilla 全部自定义载荷共享的单点 {@code CustomPacketPayload$1#findCodec}
 * 的 RETURN 包装接通（三运行时 × 双向 × config/play）；接收经
 * {@link BrushEvents#NETWORK_PAYLOAD} 投递。三运行时同一语义：全部逻辑只依赖 vanilla
 * 的官方载荷机制本身。</p>
 *
 * <p><b>三平台一致性</b>：本 API 在 S1/S2/S3 的行为完全一致 —— 不依赖任何宿主网络 API
 * （fabric-api 对 {@code findCodec} 调用点的包装与我们 RETURN 包装位置不同，可共存；
 * NeoForge 的 {@code NetworkRegistry.getCodec} 在其 miss 分支内，未注册 id 仍走我们的包装优先级；
 * S3 另由库模组把通道转登记进 NeoForge 载荷表）。</p>
 *
 * <p><b>端到端限制</b>见 {@link NetworkPayloadEvent} javadoc（专有服务器不在范围）。</p>
 */
public final class BrushNetwork {

    private static final Logger LOGGER = LogManager.getLogger("Brush");

    /** 引擎实现类名（库模组内；门面经此做**惰性解析**，避免依赖 mod 装载顺序）。 */
    private static final String ENGINE_IMPL = "org.abstruck.brush.net.BrushNetworkImpl";

    /**
     * 引擎实现（由库模组提供；核心只认这个接口，**不引用任何 mod 类型** ⇒ 方向合法）。
     */
    public interface Impl {

        /** 默认单载荷上限（引擎侧常量，镜像 vanilla 自定义载荷上限）。 */
        int defaultMaxSize();

        /** 注册通道（上限校验在引擎侧）。 */
        void register(Identifier channel, int maxSize);

        /** 经当前连接发送（未连接 / 未注册 / 超长 / 宿主判定不可发送 = 响亮失败）。 */
        void send(Identifier channel, byte[] data);
    }

    private static volatile Impl impl;

    private BrushNetwork() {
    }

    /**
     * 装入引擎实现（由**库模组入口**在初始化期调用；幂等）。
     *
     * @param implementation 引擎实现；非空
     */
    public static void install(Impl implementation) {
        impl = Objects.requireNonNull(implementation, "implementation");
        LOGGER.info("[brush] network engine installed by " + implementation.getClass().getName());
    }

    /** 引擎实现是否已装入（诊断 / 自检用；未装入通常意味着库模组缺席）。 */
    public static boolean isInstalled() {
        return impl != null;
    }

    /**
     * 注册通道（默认上限）。可在任意线程（含 mod 入口构造期 —— 注册是纯映射写入，
     * 不触碰 MC 类型值）。
     *
     * <p><b>必须在「主入口点」登记</b>（{@code entrypoints.main} 的 {@code onInitialize()}，
     * 不要放在 {@code entrypoints.client}）：S3（NeoForge）只给**一次**载荷登记机会
     * （其 {@code RegisterPayloadHandlersEvent}），宿主适配器在那一刻把当时已知的通道桥接进
     * NeoForge 注册表；而客户端入口点比它**晚**。在那里登记的通道在 S3 下**发不出去**
     * ——该情形会由引擎的发送守卫**响亮失败**并给出补救指引（S1/S2 无此限制）。
     * 证据与全案：{@code docs/29 §6.5}。</p>
     *
     * @param channel 通道 id
     */
    public static void register(Identifier channel) {
        Objects.requireNonNull(channel, "channel");
        Impl engine = require();
        engine.register(channel, engine.defaultMaxSize());
    }

    /**
     * 注册通道（自定义单载荷上限）。
     *
     * @param channel 通道 id
     * @param maxSize 单载荷上限
     * @throws IllegalArgumentException 通道已注册 / 上限越界（引擎侧判定）
     */
    public static void register(Identifier channel, int maxSize) {
        Objects.requireNonNull(channel, "channel");
        require().register(channel, maxSize);
    }

    /**
     * 经当前连接发送载荷（客户端→服务器方向）。必须已注册该通道且已连接服务器。
     *
     * <p><b>调用时机</b>：需在主线程调用；无连接时抛 {@link IllegalStateException} ——
     * 响亮失败，但 mod 应自行在发送前检查
     * {@code Minecraft.getInstance().getConnection() != null}，把"未连接"当作正常状态处理。</p>
     *
     * @param channel 已注册的通道 id
     * @param data    载荷字节（长度 ≤ 注册上限）
     * @throws IllegalStateException    通道未注册 / 未连接服务器 / 宿主判定不可发送 / 引擎缺席
     * @throws IllegalArgumentException 载荷超长
     */
    public static void send(Identifier channel, byte[] data) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(data, "data");
        require().send(channel, data);
    }

    /**
     * 取引擎实现：已装入直接用；否则做一次**惰性解析**（按类名 + 候选加载器），
     * 使"库模组已安装但入口尚未跑"也不影响可用性。
     *
     * @throws IllegalStateException 库模组产物不可见（给出可读归因与修法）
     */
    private static Impl require() {
        Impl current = impl;
        if (current != null) {
            return current;
        }
        synchronized (BrushNetwork.class) {
            current = impl;
            if (current != null) {
                return current;
            }
            current = resolveReflectively();
            impl = current;
            return current;
        }
    }

    /** 惰性解析（候选加载器：线程上下文 → 本类加载器 → 系统）。 */
    private static Impl resolveReflectively() {
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                BrushNetwork.class.getClassLoader(),
                ClassLoader.getSystemClassLoader(),
        };
        for (ClassLoader loader : candidates) {
            if (loader == null) {
                continue;
            }
            try {
                Class<?> type = Class.forName(ENGINE_IMPL, true, loader);
                Object instance = type.getDeclaredConstructor().newInstance();
                if (instance instanceof Impl resolved) {
                    LOGGER.info("[brush] network engine resolved lazily via " + loader
                            + " (" + ENGINE_IMPL + ")");
                    return resolved;
                }
                throw new IllegalStateException("[brush] " + ENGINE_IMPL
                        + " does not implement " + Impl.class.getName());
            } catch (ClassNotFoundException ignored) {
                // 该加载器看不到引擎 ⇒ 试下一个
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("[brush] network engine instantiation failed: "
                        + ENGINE_IMPL, e);
            }
        }
        throw new IllegalStateException("Brush networking is unavailable: the library mod 'brush-api'"
                + " is not installed in brush-mods/ -- since 2026-10-15 the network engine lives there"
                + "(docs/26 section 7.7). Install brush-api.jar and declare it in your mod's"
                + " brush.mod.json depends.");
    }
}

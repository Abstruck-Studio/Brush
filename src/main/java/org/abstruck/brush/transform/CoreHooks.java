package org.abstruck.brush.transform;

import org.abstruck.brush.registry.ClientRegistrationBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Brush 核心 hook 的监听器注册。
 *
 * <p>当前职责：把"注入确实命中并触发"的证据写进启动日志，并维护每事件的触发计数
 * （{@link #hitCounts()} —— 自检报告的数据源，CLAUDE.md §14.1）。
 * L2 语义层（§6）已在派发表之上建立（{@code org.abstruck.brush.api.BrushEvents}），
 * 本类不承载任何 mod 可见 API。</p>
 *
 * <p><b>生命周期事件源</b>：{@link HookEvents#CLIENT_STOPPING} 不是注入点 ——
 * 由本类安装的 JVM shutdown hook 投递（关闭时刻无可复用注入点；shutdown hook 覆盖面
 * 严格大于单锚点、三运行时一致，理由见 docs/14 §4.3；零 footprint）。其计数<b>刻意不进 {@link #HITS}</b>
 * （划分依据：期望集 = 退出点前必须已触发的事件，stopping 在退出点之后才触发），
 * 单独经 {@link #stoppingHits()} 供自检报告列出。</p>
 *
 * <p><b>信息项</b>：{@link HookEvents#CREATIVE_TAB_OUTPUT} 的触发计数同样<b>不进 {@link #HITS}</b>
 * （划分依据：期望集 = 退出点前必须已触发的事件；自检退出点前是否发生过标签页构建不作断言），
 * 单独经 {@link #creativeTabHits()} 供自检报告列出 —— 报告时若有订阅还会做一次实弹探针
 * （真实构建 + 投递/派发计数断言，见 {@code Selftest} javadoc）。</p>
 *
 * <p>可见性：public 供宿主适配（{@code org.abstruck.brush.host.*}）跨包调用 {@link #install()}
 * —— 与 {@link #install()} 的 public 同一理由（§9.3：宿主侧可调核心，核心不反向依赖宿主）。</p>
 */
public final class CoreHooks {

    /** 游戏侧 log4j（MC 上下文，落 run/logs/latest.log；见 docs/08-日志系统.md）。 */
    private static final Logger LOGGER = LogManager.getLogger(CoreHooks.class);

    /** tick 日志节流：第 1 次 + 之后每这么多 hit 打一行（热路径，避免每帧刷屏与 I/O 开销）。 */
    private static final long TICK_LOG_EVERY = 1000L;

    /** 创造栏日志节流：第 1 次 + 之后每这么多 hit 打一行（可突发：重建时多标签页连续构建）。 */
    private static final long CREATIVE_TAB_LOG_EVERY = 64L;

    /**
     * 事件 id → 触发计数（注册序，LinkedHashMap）。键仅在 {@link #install()} 内写入；
     * 计数值由 AtomicLong 承担（监听器在游戏线程执行）；读写经 {@code synchronized (HITS)}
     * 建立可见性（自检在另一线程读快照）。
     */
    private static final Map<String, AtomicLong> HITS = new LinkedHashMap<>();

    /**
     * {@link #install()} 的完成标志（P1-7 幂等守卫）：仅当<b>全部</b>注册成功后才置位。
     * 原判据 {@code HITS.isEmpty()} 无法区分"半注册"与"已完成" —— 中途失败会留下非空 HITS，
     * 使后续调用静默短路在残缺状态上。中途失败时 install() 回滚已注册监听器与计数表并原样抛出，
     * 本标志保持未置位，下次调用整体重试。
     */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    /** 客户端是否已真正启动（{@link HookEvents#CLIENT_RUN} 已触发）—— stopping 派发的门槛。 */
    private static volatile boolean clientStarted;

    /** {@link HookEvents#CLIENT_STOPPING} 触发计数（延后集：不进 {@link #HITS}，见类 javadoc）。 */
    private static final AtomicLong STOPPING_HITS = new AtomicLong();

    /** {@link HookEvents#CREATIVE_TAB_OUTPUT} 触发计数（信息项：不进 {@link #HITS}，见类 javadoc）。 */
    private static final AtomicLong CREATIVE_TAB_HITS = new AtomicLong();

    /**
     * 信息项事件 → 触发计数（注册序）。键仅在 {@link #install()} 内写入。
     * 成员：{@link HookEvents#HUD_EXTRACT} / {@link HookEvents#INPUT_KEY_PRESS} /
     * {@link HookEvents#INPUT_MOUSE_BUTTON} / {@link HookEvents#INPUT_MOUSE_SCROLL} /
     * {@link HookEvents#NETWORK_PAYLOAD} / {@link HookEvents#DATA_LOADED} /
     * {@link HookEvents#DATA_RELOADED} —— 触发时机与自检退出点的先后<b>不在契约内</b>
     * （HUD 在首帧渲染后 / 输入依赖真实交互 / 网络依赖服务器投递 / 数据锚点在进入世界后，
     * 退出点前均为 0），
     * 同 {@code screen.init} 口径不进 {@link #HITS}（期望集），由
     * {@link #infoHitCounts()} 单独供给自检报告与探针。
     */
    private static final Map<String, AtomicLong> INFO_HITS = new LinkedHashMap<>();

    /** HUD 日志节流：第 1 次 + 之后每这么多 hit 打一行（每帧 1 次，同 tick 的论证）。 */
    private static final long HUD_LOG_EVERY = 1000L;

    /** 输入日志节流：第 1 次 + 之后每这么多 hit 打一行（按键重复可连发）。 */
    private static final long INPUT_LOG_EVERY = 512L;

    /** 网络日志节流：第 1 次 + 之后每这么多 hit 打一行（载荷可突发）。 */
    private static final long NETWORK_LOG_EVERY = 64L;

    /** 数据锚点日志节流：冷路径（每服务器构造 1 次 / 每次重载 1 次）—— 每次触发都打印（间隔 1）。 */
    private static final long DATA_LOG_EVERY = 1L;

    private CoreHooks() {
    }

    /**
     * 注册核心监听器。调用方：S1 由 {@link TransformBootstrap#install()} 在管线就绪前调用；
     * S2/S3 由宿主适配（{@code org.abstruck.brush.host.*}）在宿主完成 vanilla 改造后、首个
     * run/tick 之前调用 —— public 仅为此（§9.3：宿主侧可调核心，核心不反向依赖宿主）。
     * 幂等（P1-7）：仅当上一次调用<b>全部成功</b>后，重复调用才短路返回 —— 第二次注册会产生双份监听
     * （双倍计数），且 {@link #HITS} 中旧计数器失去引用、计数与实际触发脱节。
     * 中途失败（{@link RuntimeException} / {@link Error}）时已注册的监听器全部注销、计数表清空后原样抛出
     * —— {@link #INSTALLED} 保持未置位，下次调用整体重试，不会在半注册状态上静默短路。
     *
     * <p><b>时机要求（{@link HookEvents#BOOTSTRAP_EARLY} / {@link HookEvents#REGISTRIES_BOOTSTRAP}
     * / {@link HookEvents#CLIENT_REGISTRATION}）</b>：
     * 本方法必须在三者触发之前返回 —— 各 hook 每进程只触发 1 次，注册若晚于触发则监听器缺席、
     * 计数为 0，自检如实报失败（退出码位 2，不降严格度）。顺序由 {@link EarlyBoot} 结构性保证：
     * 锚点 handler 先经本方法安装、再派发 {@code bootstrap.early}；内容注册窗口与客户端注册窗口
     * （同一 vanilla 方法 {@code BuiltInRegistries.bootStrap} 的 HEAD / TAIL，位于锚点 HEAD 之后）
     * 必然晚于三者。各宿主锚点的时序见 docs/12 / docs/13 / docs/15。
     */
    public static void install() {
        synchronized (INSTALLED) {
            if (INSTALLED.get()) {
                return;
            }
            List<Dispatcher.Registration> registered = new ArrayList<>();
            try {
                // 最早锚点（"起点化"）：每进程仅一次、先于其余一切事件 —— 注册序最先、每次都打印（证据行）。
                registered.add(register(HookEvents.BOOTSTRAP_EARLY, hits ->
                        LOGGER.info("[brush] hook fired: " + HookEvents.BOOTSTRAP_EARLY
                                + " (hit #" + hits + ")")));
                // 内容注册窗口：窗口每进程仅存在一次，故每次都打印（证据行）。
                registered.add(register(HookEvents.REGISTRIES_BOOTSTRAP, hits ->
                        LOGGER.info("[brush] hook fired: " + HookEvents.REGISTRIES_BOOTSTRAP
                                + " (hit #" + hits + ")")));
                // 客户端注册窗口（E3）：与内容注册同一 vanilla 方法（HEAD/TAIL），窗口每进程仅一次，
                // 故每次都打印（证据行；flush 先于本行 —— 见 BuiltInRegistriesMixin TAIL handler）。
                registered.add(register(HookEvents.CLIENT_REGISTRATION, hits ->
                        LOGGER.info("[brush] hook fired: " + HookEvents.CLIENT_REGISTRATION
                                + " (hit #" + hits + ")")));
                // 客户端注册延迟落地（S3 修正，2026-10-12）：NeoForge 下 bootStrap TAIL 早于
                // 宿主内容注册（ClientModLoader.begin），属性 Supplier 求值必须推迟到首个
                // packs.registered（Minecraft.<init> 内、晚于 begin、早于渲染调度器构建 ——
                // javap 偏移与 T3 实测见 BuiltInRegistriesMixin / ClientRegistrationBuffer javadoc）。
                // TAIL 之前 / 已 flush 时 deferredFlushDue()=false —— S1/S2 及 S2 的
                // "首个仓库先于 TAIL"序下均自然 no-op。不走 register()：内部机制，不进自检
                // 期望集（同 CREATIVE_TAB_OUTPUT 先例）。
                registered.add(Dispatcher.register(HookEvents.PACKS_REGISTERED, args -> {
                    if (ClientRegistrationBuffer.deferredFlushDue()) {
                        LOGGER.info("[brush] client registration flush landing at the first"
                                + " packs.registered (deferred: the host content registration"
                                + " completes after the bootStrap TAIL; NeoForge)");
                        ClientRegistrationBuffer.flush();
                    }
                }));
                // 资源包仓库构建：每实例 1 次、次数少（客户端主仓库 + 世界创建界面数据包仓库），
                // 每次都打印（证据行）—— 资源接入的关键证据是随后 ModResourcePacks 的细节行。
                registered.add(register(HookEvents.PACKS_REGISTERED, hits ->
                        LOGGER.info("[brush] hook fired: " + HookEvents.PACKS_REGISTERED
                                + " (hit #" + hits + ")")));
                registered.add(register(HookEvents.CLIENT_RUN, hits -> {
                    clientStarted = true;
                    LOGGER.info("[brush] hook fired: " + HookEvents.CLIENT_RUN + " (hit #" + hits + ")");
                }));
                registered.add(register(HookEvents.CLIENT_TICK, hits -> {
                    if (hits == 1 || hits % TICK_LOG_EVERY == 0) {
                        LOGGER.info("[brush] hook fired: " + HookEvents.CLIENT_TICK + " (hit #" + hits + ")");
                    }
                }));
                // 信息项：创造栏输出计数 + 节流证据日志。同样不走 register()（那会进 HITS = 期望集）。
                registered.add(Dispatcher.register(HookEvents.CREATIVE_TAB_OUTPUT, args -> {
                    long hits = CREATIVE_TAB_HITS.incrementAndGet();
                    if (hits == 1 || hits % CREATIVE_TAB_LOG_EVERY == 0) {
                        LOGGER.info("[brush] hook fired: " + HookEvents.CREATIVE_TAB_OUTPUT
                                + " (hit #" + hits + ")");
                    }
                }));
                // 延后集：stopping 计数 + 证据日志。不走 register()（那会进 HITS = 期望集）。
                // 证据行走 RawStdout（fd 1 直写）：触发时游戏侧 log4j 已关闭（自检退出先经
                // LogManager.shutdown()），且 System.out 已被 MC 换成 log4j 承接的流 —— 经
                // System.out 的证据行会被静默吞掉；fd 直写在关闭钩子线程中同样送达（见 docs/14 §4）。
                registered.add(Dispatcher.register(HookEvents.CLIENT_STOPPING, args -> {
                    long hits = STOPPING_HITS.incrementAndGet();
                    RawStdout.println("[brush] hook fired: " + HookEvents.CLIENT_STOPPING + " (hit #" + hits
                            + ", deferred lifecycle event, JVM shutdown thread)");
                }));
                // 信息项：HUD 提取 / 输入三键 / 网络投递 —— 计数 + 节流证据日志。
                // 不走 register()（那会进 HITS = 期望集；划分依据见 INFO_HITS javadoc）。
                registered.add(registerInfo(HookEvents.HUD_EXTRACT, HUD_LOG_EVERY));
                registered.add(registerInfo(HookEvents.INPUT_KEY_PRESS, INPUT_LOG_EVERY));
                registered.add(registerInfo(HookEvents.INPUT_MOUSE_BUTTON, INPUT_LOG_EVERY));
                registered.add(registerInfo(HookEvents.INPUT_MOUSE_SCROLL, INPUT_LOG_EVERY));
                registered.add(registerInfo(HookEvents.NETWORK_PAYLOAD, NETWORK_LOG_EVERY));
                // 信息项：数据锚点（G1）—— 集成服务器构造 / 数据重载。冷路径，间隔 1 = 每次都打印
                // （证据行）；退出点前恒 0（T1/T2/T3 均在世界加载前退出），不进期望集
                // （划分依据见 INFO_HITS javadoc）。
                registered.add(registerInfo(HookEvents.DATA_LOADED, DATA_LOG_EVERY));
                registered.add(registerInfo(HookEvents.DATA_RELOADED, DATA_LOG_EVERY));
                // 信息项：崩溃报告段（缺口 #2）—— 冷路径、退出点前恒 0，故不进期望集。
                // 与其余信息项不同：它有实际副作用（把 Brush 段追加进报告正文），
                // 所以不能用 registerInfo（那个只计数 + 打日志），见 registerInfoCrashReport。
                registered.add(registerInfoCrashReport());
                installShutdownHook();
            } catch (RuntimeException | Error e) {
                // 回滚半注册状态（P1-7）：注销本次已注册的监听器 + 清空计数表 ——
                // INSTALLED 保持未置位，下次调用整体重试（不建立在半注册之上）。
                for (Dispatcher.Registration reg : registered) {
                    reg.unregister();
                }
                synchronized (HITS) {
                    HITS.clear();
                }
                synchronized (INFO_HITS) {
                    INFO_HITS.clear();
                }
                throw e;
            }
            INSTALLED.set(true);
        }
    }

    /**
     * 注册一个"信息项计数 + 节流日志"监听器（划分口径见 {@link #INFO_HITS} javadoc）。
     * 与 {@link #register} 同构，但计入 {@link #INFO_HITS} 而非 {@link #HITS}。
     * 返回 {@link Dispatcher.Registration} 供 {@link #install()} 失败回滚（P1-7）。
     */
    private static Dispatcher.Registration registerInfo(HookEvent event, long logEvery) {
        AtomicLong counter = new AtomicLong();
        synchronized (INFO_HITS) {
            INFO_HITS.put(event.id(), counter);
        }
        return Dispatcher.register(event, args -> {
            long hits = counter.incrementAndGet();
            if (hits == 1 || hits % logEvery == 0) {
                LOGGER.info("[brush] hook fired: " + event + " (hit #" + hits + ")");
            }
        });
    }

    /**
     * 崩溃报告段注入（docs/25 §3.2，缺口 #2）：把 {@link BrushCrashReport#section()} 追加进
     * vanilla 正在组装的报告正文。
     *
     * <p><b>计数归信息集</b>（{@link #INFO_HITS}）：只有真崩溃才会触发，自检退出点前恒 0 ——
     * 进期望集会让 {@code injection.audit} 在三场里误报缺失。</p>
     *
     * <p><b>只许追加</b>：handler 不清空 / 不重排正文既有内容（否则是与其它 mod 的独占式冲突，
     * §7.2）。载荷非 {@code StringBuilder} 时静默不追加（只计数）—— 崩溃路径上不抛异常，
     * 避免吞掉原始崩溃证据。</p>
     */
    private static Dispatcher.Registration registerInfoCrashReport() {
        AtomicLong counter = new AtomicLong();
        synchronized (INFO_HITS) {
            INFO_HITS.put(HookEvents.CRASH_REPORT_DETAILS.id(), counter);
        }
        return Dispatcher.register(HookEvents.CRASH_REPORT_DETAILS, args -> {
            long hits = counter.incrementAndGet();
            if (hits == 1) {
                LOGGER.info("[brush] hook fired: " + HookEvents.CRASH_REPORT_DETAILS
                        + " (hit #" + hits + ") -- appending the Brush section to the crash report");
            }
            if (args.length > 0 && args[0] instanceof StringBuilder builder) {
                builder.append(BrushCrashReport.section());
            }
        });
    }

    /**
     * JVM shutdown hook 投递 {@link HookEvents#CLIENT_STOPPING}（非注入事件源，见 docs/14）。
     * 仅在客户端确实启动过时派发（启动失败中止的进程不产生"关闭"事件）；
     * 恰注册一次 —— 由 {@link #install()} 的幂等守卫保证。
     */
    private static void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (clientStarted) {
                Dispatcher.fire(HookEvents.CLIENT_STOPPING);
            }
        }, "brush-client-stopping"));
    }

    /**
     * 注册一个"计数 + 日志"监听器。
     * 计数从 1 开始（自增后传入 {@code onHit}）。
     * 返回 {@link Dispatcher.Registration} 供 {@link #install()} 失败回滚（P1-7）。
     */
    private static Dispatcher.Registration register(HookEvent event, LongConsumer onHit) {
        AtomicLong counter = new AtomicLong();
        synchronized (HITS) {
            HITS.put(event.id(), counter);
        }
        return Dispatcher.register(event, args -> onHit.accept(counter.incrementAndGet()));
    }

    /**
     * 自检用：事件 → 触发次数快照，按注册序。
     *
     * <p>断言集的隐含约定：<b>本 map 的键即"在自检退出点之前必须已触发"的期望集</b>
     * （划分规则见 {@code org.abstruck.brush.selftest.Selftest} 类 javadoc 的"期望集"一节）；退出点
     * 之后才会触发的新 hook 加入本表时，须同步调整该类的期望集划分。
     *
     * <p>public：自检源集（{@code src/selftest}，独立包 {@code org.abstruck.brush.selftest}）
     * 跨包读取 —— 只读快照，无副作用；与 {@link InjectionLedger} 同类（public 只读诊断面）。</p>
     */
    public static Map<String, Long> hitCounts() {
        synchronized (HITS) {
            Map<String, Long> snapshot = new LinkedHashMap<>();
            HITS.forEach((event, counter) -> snapshot.put(event, counter.get()));
            return Collections.unmodifiableMap(snapshot);
        }
    }

    /**
     * 自检用（延后集）：{@link HookEvents#CLIENT_STOPPING} 触发计数快照。
     * 刻意不在 {@link #hitCounts()} 的期望集内（见类 javadoc），报告单独列出。
     * public：自检源集跨包读取（同 {@link #hitCounts()}）。
     */
    public static long stoppingHits() {
        return STOPPING_HITS.get();
    }

    /**
     * 自检用（信息项）：{@link HookEvents#CREATIVE_TAB_OUTPUT} 触发计数快照。
     * 刻意不在 {@link #hitCounts()} 的期望集内（见类 javadoc），报告单独列出。
     * public：自检源集跨包读取（同 {@link #hitCounts()}）。
     */
    public static long creativeTabHits() {
        return CREATIVE_TAB_HITS.get();
    }

    /**
     * 自检用（信息项）：信息事件 → 触发次数快照，按注册序。
     * 刻意不在 {@link #hitCounts()} 的期望集内（见 {@link #INFO_HITS} javadoc），
     * 由自检报告的信息行与探针（{@code SelftestNetworkProbe} /
     * {@code SelftestInputHudProbe}）消费。
     * public：自检源集跨包读取（同 {@link #hitCounts()}）。
     */
    public static Map<String, Long> infoHitCounts() {
        synchronized (INFO_HITS) {
            Map<String, Long> snapshot = new LinkedHashMap<>();
            INFO_HITS.forEach((event, counter) -> snapshot.put(event, counter.get()));
            return Collections.unmodifiableMap(snapshot);
        }
    }
}

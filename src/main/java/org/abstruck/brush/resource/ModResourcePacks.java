package org.abstruck.brush.resource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import org.abstruck.brush.BrushProperties;
import org.abstruck.brush.mod.ModBootstrap;
import org.abstruck.brush.transform.Dispatcher;
import org.abstruck.brush.transform.HookEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组资源包门面：把已加载 brush mod jar 内的资源与数据接入游戏的两类仓库 ——
 * 客户端资源仓库（{@code assets/**} → 模型 / 材质 / 语言）与服务端数据仓库
 * （{@code data/**} → 配方 / 进度 / 战利品表；E1，2026-10-12）。
 *
 * <p><b>调用链</b>：{@code PackRepositoryMixin}（注入点 {@code PackRepository#<init>} RETURN）
 * → {@link #attach(Set)} → 按仓库类型分流追加来源（客户端仓库 → {@code ModRepositorySource}，
 * omnibus 包 {@link #PACK_ID}；服务端仓库 → {@code ModServerDataSource}，omnibus 包
 * {@link #SERVER_PACK_ID}），随后派发 {@link HookEvents#PACKS_REGISTERED}。顺序链与共存论证
 * 见 {@link #PACK_ID} javadoc；读取语义镜像 vanilla {@code FilePackResources}
 * （{@link ModPackResources}）。
 *
 * <p><b>为什么在 attach 内派发事件而不是在 mixin handler 内</b>：注入点织入恰 1 条指令
 * （{@code ModResourcePacks.attach} 静态调用）；attach 是"注入点命中"的语义实现 ——
 * 与 {@code EarlyBoot.onBootstrap()} 同一形态（handler 体内的工作不计入织入指令数）。
 *
 * <p><b>仓库分流（javap 实证，26.1.2）</b>：客户端资源仓库 = {@code Minecraft} 构造器经
 * {@code [ClientPackSource, FolderRepositorySource]} 建造；服务端数据仓库（世界加载 /
 * 世界创建）= {@code ServerPacksSource.createPackRepository(...)} 建造（含
 * {@code ServerPacksSource}，不含 {@code ClientPackSource}）—— 两类形状互斥，按来源集合
 * 成员判定即可精确分流；两类之外的仓库（理论上不存在于 vanilla）原样返回。事件照发
 * （注入点照常命中）。
 *
 * <p><b>核心资源包挂载</b>：standalone（S1）下同一注入点再加挂
 * {@link BrushCorePackResources}（id {@code brush_core}）—— 把 Brush 自身的类路径资源树
 * 接入资源栈（S2/S3 由宿主挂载 brush 制品资产，按 {@code brush.standalone} 分流、不重复挂）。
 * 0 新注入点：仍是 1 条 {@code attach} 调用，handler 体内的工作不计入织入指令数。
 */
public final class ModResourcePacks {

    /** 我们资源包的 id（选中列表 / 自检报告 / 日志中的稳定标识）。 */
    public static final String PACK_ID = "brush_mods";

    /** 我们数据包的 id（E1；SERVER_DATA 仓库的独立 omnibus 包）。 */
    public static final String SERVER_PACK_ID = "brush_mod_data";

    private static final Logger LOGGER = LogManager.getLogger(ModResourcePacks.class);

    /** attach 是否真正接入过客户端仓库（自检审计用）。 */
    private static volatile boolean attached;

    /** 核心资源包是否接入过客户端仓库（standalone 自检审计用）。 */
    private static volatile boolean coreAttached;

    /** 接入的 jar 数（attach 时写；自检报告用）。 */
    private static volatile int attachedJars;

    /** 数据包是否接入过服务端仓库（实机探针断言用；selftest 退出点早于任何服务端仓库构建）。 */
    private static volatile boolean serverAttached;

    /** 数据包接入的 jar 数（attach 时写）。 */
    private static volatile int serverAttachedJars;

    /** 数据包是否已接入服务端仓库（E1；实机探针在入世界后断言）。 */
    public static boolean serverAttached() {
        return serverAttached;
    }

    /** 数据包接入的 jar 数（0 = 未接入）。 */
    public static int serverAttachedJars() {
        return serverAttachedJars;
    }

    private ModResourcePacks() {
    }

    /**
     * 资源包仓库构建钩子（注入点的 handler 语义）：按仓库类型为客户端仓库追加资源包来源、
     * 为服务端仓库追加数据包来源（E1），并派发 {@link HookEvents#PACKS_REGISTERED}。
     * 返回替换后的来源集合（无法识别 / 无需修改 / 已接入时原样返回）。
     *
     * <p>客户端仓库两档来源：① 核心资源包（仅 standalone —— S2/S3 由宿主挂载 brush 制品资产）；
     * ② 模组资源包（有已加载 mod 时）。服务端仓库一档：模组数据包（有已加载 mod 时）。
     * 各档幂等（来源类型存在即跳过）。
     *
     * <p><b>派发语义在 {@code finally}（评审 P2-7 显式化）</b>：{@link HookEvents#PACKS_REGISTERED}
     * 的派发条件是"<b>注入点命中</b>"（仓库构建发生），不是"挂包成功"—— 事件契约 = 与注入点的
     * 触发语义一一对应（每注入点一条 {@code Dispatcher.fire}，docs/07），因此即便本方法中途以
     * 异常退出（真实缺陷路径；正常流程不抛），事件仍会派发。顺序保证：全部状态旗标
     * （{@link #attached} / {@link #serverAttached} 等）与归档日志行都在 {@code try} 内、
     * <b>派发之前</b>写入 —— 监听器读到的是本次构建已确定的终态。已知取舍：若 {@code try} 内
     * 与派发都抛异常，派发异常会覆盖原异常（Java {@code finally} 语义）；选"事件必发"优先，
     * 且该路径在正常构建里不可达。监听器自身异常由 {@link Dispatcher} 包装上抛（docs/07），
     * 不静默吞。</p>
     *
     * <p>本方法不触碰磁盘（jar 在资源被实际读取时才打开；核心包索引为类路径小文件），
     * 因此在 {@code Minecraft} 构造期调用零重 I/O。
     */
    public static Set<RepositorySource> attach(Set<RepositorySource> sources) {
        try {
            boolean clientRepository = sources.stream().anyMatch(ClientPackSource.class::isInstance);
            boolean serverRepository = sources.stream().anyMatch(ServerPacksSource.class::isInstance);

            if (!clientRepository && !serverRepository) {
                LOGGER.info("[brush] pack repository built (unrecognized): " + sources.size()
                        + " source(s); neither client resource packs nor server data packs apply");
                return sources;
            }

            LinkedHashSet<RepositorySource> extended = null;

            if (clientRepository) {
                // ① 核心资源包：standalone 分流守卫与 BrushMixinService.isValid() 同一属性。
                // 索引缺失 = 构建接线断裂：不挂包并如实告警（自检审计同步置失败），禁止静默降级。
                if (Boolean.getBoolean(BrushProperties.STANDALONE)) {
                    if (sources.stream().anyMatch(BrushCorePackResources::isCoreSource)) {
                        LOGGER.info("[brush] pack repository built (client): core resource pack already"
                                + " present; left unchanged");
                    } else if (BrushCorePackResources.index() == null) {
                        LOGGER.error("[brush] core resource pack NOT attached: resource index '"
                                + BrushCorePackResources.INDEX_RESOURCE + "' missing from classpath"
                                + " (build task generateBrushResourceIndex)");
                    } else {
                        extended = new LinkedHashSet<>(sources);
                        extended.add(BrushCorePackResources.source(BrushCorePackResources.index()));
                        coreAttached = true;
                        LOGGER.info("[brush] pack repository built (client): attached core resource pack '"
                                + BrushCorePackResources.PACK_ID + "' (standalone; "
                                + BrushCorePackResources.index().size() + " classpath file(s))");
                    }
                } else {
                    LOGGER.info("[brush] pack repository built (client): host runtime (brush.standalone"
                            + " absent); core resource pack is mounted by the host mod manager");
                }

                // ② 模组资源包。
                List<ModBootstrap.LoadedMod> mods = ModBootstrap.loadedMods();
                if (sources.stream().anyMatch(ModRepositorySource.class::isInstance)) {
                    LOGGER.info("[brush] pack repository built (client): mod resource pack already present;"
                            + " left unchanged");
                } else if (mods.isEmpty()) {
                    LOGGER.info("[brush] pack repository built (client): no brush mods loaded;"
                            + " no mod resource pack attached");
                } else {
                    if (extended == null) {
                        extended = new LinkedHashSet<>(sources);
                    }
                    extended.add(new ModRepositorySource(mods));
                    attached = true;
                    attachedJars = jarList(mods).size();
                    LOGGER.info("[brush] pack repository built (client): attached resource pack '" + PACK_ID
                            + "' (" + attachedJars + " jar(s): " + modIds(mods) + ")");
                }
            }

            if (serverRepository) {
                // ③ 模组数据包（E1）：SERVER_DATA 仓库（集成服务器 / 世界创建）。
                List<ModBootstrap.LoadedMod> mods = ModBootstrap.loadedMods();
                if (sources.stream().anyMatch(ModServerDataSource.class::isInstance)) {
                    LOGGER.info("[brush] pack repository built (server): mod data pack already present;"
                            + " left unchanged");
                } else if (mods.isEmpty()) {
                    LOGGER.info("[brush] pack repository built (server): no brush mods loaded;"
                            + " no mod data pack attached");
                } else {
                    if (extended == null) {
                        extended = new LinkedHashSet<>(sources);
                    }
                    extended.add(new ModServerDataSource(mods));
                    serverAttached = true;
                    serverAttachedJars = jarList(mods).size();
                    LOGGER.info("[brush] pack repository built (server): attached data pack '"
                            + SERVER_PACK_ID + "' (" + serverAttachedJars + " jar(s): "
                            + modIds(mods) + ")");
                }
            }

            return extended == null ? sources : extended;
        } finally {
            // 注入点命中即派发（无论是否修改了来源集合）—— 与注入点的触发语义一一对应。
            Dispatcher.fire(HookEvents.PACKS_REGISTERED);
        }
    }

    // ------------------------------------------------------------------
    // 自检审计（报告行 + 退出码位 32，见 Selftest）
    // ------------------------------------------------------------------

    /** 自检审计结果：{@code summary} = 报告行文本；{@code healthy=false} 时置退出码位 32。 */
    public record Audit(String summary, boolean healthy) { }

    /** 审计片段（核心包与 mod 包各自成段；healthy 由调用方汇总）。 */
    private record Segment(String text, boolean healthy) { }

    /**
     * 自检审计（在 tick#1 退出点执行）：验证"brush 的资源（核心 + 已加载 mod）确实接入了
     * 游戏资源栈"。
     *
     * <p><b>判定项（确定性，不依赖初始资源 reload 是否完成）</b>：
     * ① standalone 下核心资源包必须已接入（{@link #coreAttached}）且样例资产可经包接口读出；
     * 有 mod 时模组资源包必须已接入（{@link #attached}）；
     * ② 模组包必须出现在 {@code PackRepository.getSelectedIds()} —— {@code Minecraft} 构造器在
     * {@code PackRepository.<init>} 之后调用 {@code reload()}，退出点时该状态必然已就绪；
     * ③ 模组包必须能读出自己 jar 内的一个样例资源（确定性抽样：字典序首个 namespace 的首个文件条目）；
     * ③b SERVER_DATA 自证读取（E1）：数据包能枚举 {@code data/} namespace 并读出样例条目
     * —— <b>附着状态不进判定</b>：退出点早于任何服务端仓库构建（集成服务器 / 世界创建屏），
     * 该行必然为 not-yet；活的接入断言在实机探针（入世界后，{@link #serverAttached()}）。
     *
     * <p><b>信息项（不参与判定）</b>：{@code getResourceStack} 的实时结果 —— 初始资源 reload
     * 在 tick#1 退出点通常尚未完成，空结果属预期，
     * 如实打印并标注（不把预期行为伪装成断言，也不因它误报失败）。
     */
    public static Audit audit() {
        StringBuilder sb = new StringBuilder("mod.resources: ");
        boolean healthy = true;

        // 核心资源包段（standalone 专属；S2/S3 宿主自挂载 brush 资产，不进本审计）。
        if (Boolean.getBoolean(BrushProperties.STANDALONE)) {
            Segment core = coreAudit();
            sb.append(core.text()).append("; ");
            healthy &= core.healthy();
        }

        // 模组资源包段。
        List<ModBootstrap.LoadedMod> mods = ModBootstrap.loadedMods();
        if (mods.isEmpty()) {
            sb.append("pack '").append(PACK_ID).append("': n/a (no brush mods loaded;"
                    + " no mod resource pack attached)");
            return new Audit(sb.toString(), healthy);
        }

        sb.append("pack '").append(PACK_ID).append("':");
        if (!attached) {
            sb.append(" attached=NO -- a client pack repository was expected to be built before the exit point");
            return new Audit(sb.toString(), false);
        }
        sb.append(" attached=yes; jars=").append(attachedJars);

        // ② 仓库选中状态（确定性）
        try {
            PackRepository repository = Minecraft.getInstance().getResourcePackRepository();
            boolean selected = repository.getSelectedIds().contains(PACK_ID);
            healthy &= selected;
            sb.append("; repo.selected=").append(selected ? "yes" : "NO");
        } catch (RuntimeException e) {
            healthy = false;
            sb.append("; repo.selected=unknown (").append(e).append(")");
        }

        // ③ 自证读取：不依赖初始 reload 的独立通道
        List<Path> jars = jarList(mods);
        Identifier sample = null;
        try (ModPackResources pack = new ModPackResources(location(), jars, PackType.CLIENT_RESOURCES)) {
            Set<String> namespaces = new TreeSet<>(pack.getNamespaces(PackType.CLIENT_RESOURCES));
            sb.append("; namespaces=").append(namespaces);
            sample = findSample(jars, namespaces, "assets/");
            if (sample == null) {
                sb.append("; sample=none (jars ship no assets/ entries)");
            } else {
                IoSupplier<InputStream> supplier = pack.getResource(PackType.CLIENT_RESOURCES, sample);
                if (supplier == null) {
                    healthy = false;
                    sb.append("; sample=").append(sample).append(" readable=NO");
                } else {
                    try (InputStream in = supplier.get()) {
                        sb.append("; sample=").append(sample).append(" readable=yes (")
                                .append(in.readAllBytes().length).append(" bytes)");
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            healthy = false;
            sb.append("; pack self-read FAILED: ").append(e);
        }

        // ③b SERVER_DATA 段（E1）：自证读取（与 attach 状态独立 —— 退出点早于任何服务端仓库构建，
        // 附着状态此行必然 not-yet，如实打印；活的接入由实机探针在入世界后断言）。
        sb.append("; pack '").append(SERVER_PACK_ID).append("': attached=")
                .append(serverAttached ? "yes" : "not-yet (no server-side repository built before"
                        + " this exit point; live-verified after world join by RealityProbe)");
        try (ModPackResources serverPack = new ModPackResources(serverLocation(), jars, PackType.SERVER_DATA)) {
            Set<String> dataNamespaces = new TreeSet<>(serverPack.getNamespaces(PackType.SERVER_DATA));
            sb.append("; data.namespaces=").append(dataNamespaces);
            Identifier dataSample = findSample(jars, dataNamespaces, "data/");
            if (dataSample == null) {
                sb.append("; data.sample=none (jars ship no data/ entries)");
            } else {
                IoSupplier<InputStream> supplier = serverPack.getResource(PackType.SERVER_DATA, dataSample);
                if (supplier == null) {
                    healthy = false;
                    sb.append("; data.sample=").append(dataSample).append(" readable=NO");
                } else {
                    try (InputStream in = supplier.get()) {
                        sb.append("; data.sample=").append(dataSample).append(" readable=yes (")
                                .append(in.readAllBytes().length).append(" bytes)");
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            healthy = false;
            sb.append("; server-data self-read FAILED: ").append(e);
        }

        // 信息项：游戏资源栈的实时结果（见方法 javadoc 的"信息项"说明）
        if (sample != null) {
            try {
                List<Resource> stack = Minecraft.getInstance().getResourceManager().getResourceStack(sample);
                sb.append("; resource.manager.stack=").append(stack.size())
                        .append(" (informational: initial reload is usually still in flight"
                                + " at this exit point)");
            } catch (RuntimeException e) {
                sb.append("; resource.manager.stack=unknown (").append(e).append(")");
            }
        }
        return new Audit(sb.toString(), healthy);
    }

    /**
     * 核心资源包审计段：standalone 下的确定性断言 —— 构建期清单在类路径、包已接入、
     * 默认图标与至少一个侧栏 sprite 可经包接口读出（自证读取，不依赖初始 reload 进度；
     * 资源缺席 = 用户可见功能失败，必须置位而非静默）。
     */
    private static Segment coreAudit() {
        StringBuilder sb = new StringBuilder("core.pack '")
                .append(BrushCorePackResources.PACK_ID).append("':");
        List<String> entries = BrushCorePackResources.index();
        if (entries == null) {
            sb.append(" attached=NO (resource index '").append(BrushCorePackResources.INDEX_RESOURCE)
                    .append("' missing from classpath)");
            return new Segment(sb.toString(), false);
        }
        if (!coreAttached) {
            sb.append(" attached=NO -- a client pack repository was expected to be built before"
                    + " the exit point");
            return new Segment(sb.toString(), false);
        }
        sb.append(" attached=yes");
        boolean healthy = true;
        try (BrushCorePackResources pack = new BrushCorePackResources(
                BrushCorePackResources.locationInfo(), entries)) {
            sb.append("; namespaces=").append(new TreeSet<>(pack.getNamespaces(PackType.CLIENT_RESOURCES)));
            Identifier icon = Identifier.fromNamespaceAndPath("brush", "textures/gui/icon.png");
            healthy &= appendRead(sb, pack, "; icon=", icon);
            // 侧栏 sprite 抽样：清单已排序 → 首个 sprites/ 条目即确定样本；同时报总数
            // （侧栏共 8 张 tab_left_*；数量只作信息，样本可读性才是断言）。
            String spritesDir = "assets/brush/textures/gui/sprites/";
            Identifier spriteSample = null;
            int spriteCount = 0;
            for (String entry : entries) {
                if (entry.startsWith(spritesDir)) {
                    spriteCount++;
                    if (spriteSample == null) {
                        spriteSample = Identifier.tryBuild("brush",
                                entry.substring("assets/brush/".length()));
                    }
                }
            }
            sb.append("; sprites=").append(spriteCount);
            if (spriteSample == null) {
                sb.append("; sample=none (no textures/gui/sprites entries listed)");
                healthy = false;
            } else {
                healthy &= appendRead(sb, pack, "; sample=", spriteSample);
            }
        } catch (RuntimeException e) {
            healthy = false;
            sb.append("; core self-read FAILED: ").append(e);
        }
        return new Segment(sb.toString(), healthy);
    }

    /** 经包接口读一个资源并追加 {@code <label><id> readable=yes (N bytes)}；读不出 → NO 并 false。 */
    private static boolean appendRead(StringBuilder sb, BrushCorePackResources pack,
                                      String label, Identifier id) {
        sb.append(label).append(id);
        IoSupplier<InputStream> supplier = pack.getResource(PackType.CLIENT_RESOURCES, id);
        if (supplier == null) {
            sb.append(" readable=NO");
            return false;
        }
        try (InputStream in = supplier.get()) {
            sb.append(" readable=yes (").append(in.readAllBytes().length).append(" bytes)");
            return true;
        } catch (IOException e) {
            sb.append(" readable=NO (").append(e).append(")");
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 共享构件
    // ------------------------------------------------------------------

    /** 资源包位置信息（attach 与自检共用的单一构造点）。 */
    static PackLocationInfo location() {
        return new PackLocationInfo(PACK_ID, Component.literal("Brush mod resources"),
                PackSource.BUILT_IN, Optional.empty());
    }

    /** 数据包位置信息（E1；attach 与自检共用的单一构造点）。 */
    static PackLocationInfo serverLocation() {
        return new PackLocationInfo(SERVER_PACK_ID, Component.literal("Brush mod data"),
                PackSource.BUILT_IN, Optional.empty());
    }

    /**
     * 实机探针用（E1）：动态发现已加载 mod 的首个 SERVER_DATA 样例
     * （字典序首个 namespace 的首个 {@code data/} 条目）；无 mod / 无 data/ 条目 → null。
     * 供 {@code RealityProbe} 在入世界后经集成服务器资源栈做活读断言。
     */
    public static Identifier findServerSample() {
        List<ModBootstrap.LoadedMod> mods = ModBootstrap.loadedMods();
        if (mods.isEmpty()) {
            return null;
        }
        List<Path> jars = jarList(mods);
        try (ModPackResources pack = new ModPackResources(serverLocation(), jars, PackType.SERVER_DATA)) {
            Set<String> namespaces = new TreeSet<>(pack.getNamespaces(PackType.SERVER_DATA));
            return findSample(jars, namespaces, "data/");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[brush] cannot discover a server-data sample: " + e);
            return null;
        }
    }

    /**
     * 打开一个独立的 SERVER_DATA 模组数据包句柄（selftest 探针用：自建
     * {@code MultiPackResourceManager} 直接驱动数据包注册表装载 —— 与真实数据流同一包资源、
     * 同一装载代码路径，无服务器也可测）。实参严格复用 {@link #audit()} 的 server 段构造
     * （{@link #serverLocation()} + {@code jarList(loadedMods())}）。
     * 调用方负责释放：经资源管理器 close 或直接 close 均可
     * （{@code MultiPackResourceManager.close()} 逐包释放 —— javap 实证；
     * {@code ModPackResources.close()} 幂等，双重释放安全）。
     */
    public static PackResources openServerDataPack() throws IOException {
        return new ModPackResources(serverLocation(), jarList(ModBootstrap.loadedMods()),
                PackType.SERVER_DATA);
    }

    /** mod jar 去重列表（保持 {@code loadedMods()} 的加载顺序；供资源包与自检共用）。 */
    static List<Path> jarList(List<ModBootstrap.LoadedMod> mods) {
        Set<Path> distinct = new LinkedHashSet<>();
        for (ModBootstrap.LoadedMod mod : mods) {
            distinct.add(mod.jar());
        }
        return List.copyOf(distinct);
    }

    /** 日志用的 mod id 清单（去重、保序）。 */
    private static String modIds(List<ModBootstrap.LoadedMod> mods) {
        Set<String> ids = new LinkedHashSet<>();
        for (ModBootstrap.LoadedMod mod : mods) {
            ids.add(mod.id());
        }
        return String.join(",", ids);
    }

    /**
     * 确定性样例：按字典序取首个 namespace，再取首个 jar 中前缀 {@code <typePrefix><ns>/} 的
     * 首个文件条目（CLIENT_RESOURCES 传 {@code "assets/"}、SERVER_DATA 传 {@code "data/"}）。
     * mod jar 只含代码（无对应资源树）时返回 null —— 合法状态，非失败。
     */
    private static Identifier findSample(List<Path> jars, Set<String> namespaces,
                                         String typePrefix) throws IOException {
        for (String namespace : namespaces) {
            String prefix = typePrefix + namespace + "/";
            for (Path jar : jars) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                            continue;
                        }
                        Identifier id = Identifier.tryBuild(namespace,
                                entry.getName().substring(prefix.length()));
                        if (id != null) {
                            return id;
                        }
                    }
                }
            }
        }
        return null;
    }
}

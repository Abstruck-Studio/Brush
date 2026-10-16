package org.abstruck.brush.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 数据包注册表目录布局桥接（G1，docs/21 §3/§5）—— 包装服务器资源管理器，使已登记注册表的元素扫描
 * 同时接受两种目录布局，条目 id 不变。
 *
 * <p><b>为什么需要</b>（实测证据，docs/21 §6）：非 minecraft 命名空间的数据包注册表目录在两宿主
 * 生态下被<b>前置注册表命名空间</b>，vanilla 机制（S1）则是裸路径：
 * <ul>
 *   <li>S1（vanilla 机制）：{@code example:demo_material} → {@code data/<包ns>/demo_material/}；</li>
 *   <li>S2（fabric-api）：{@code fabric-registry-sync-v0} 7.1.1 的 {@code RegistriesMixin} 对
 *       {@code Registries.elementsDirPath}/{@code tagsDirPath} 做 {@code @ModifyReturnValue}，
 *       非 minecraft 命名空间返回 {@code ns + "/" + path}；</li>
 *   <li>S3（NeoForge）：补丁版客户端 jar 将 {@code Registries.registryDirPath} 改为
 *       {@code CommonHooks.prefixNamespace(id)} —— 与上一行逐句同构（仅 minecraft 跳过前置）。</li>
 * </ul>
 * 引擎的装载任务复用 vanilla {@code ResourceManagerRegistryLoadTask}，其扫描前缀取自宿主实况
 * （{@code FileToIdConverter.registry} → {@code Registries.elementsDirPath}）——<b>不桥接时，两种
 * 布局的文件不能在对方运行时读到</b>（T2 红现场：0 条目）。
 *
 * <p><b>桥接语义</b>（固定规则，不随运行时漂移）：
 * <ol>
 *   <li>拦截键 = 装载任务实际请求的前缀（宿主实况 {@code elementsDirPath}）；其余前缀原样放行；</li>
 *   <li>对每个已登记注册表，层集合 = ｛宿主前缀（若与裸路径不同）、命名空间限定层 {@code ns + "/" + 裸路径}
 *       （命名空间非 minecraft 时——与两宿主的前置条件逐字对齐）、vanilla 机制裸路径层｝去重；</li>
 *   <li>每层独立扫描，命中 id 改写为<b>请求前缀形状</b>（{@code withPath} 前缀替换）——
 *       任务内 {@code FileToIdConverter.fileToId} 按请求前缀做 substring 剥离，因此条目 id
 *       （{@code <包ns>:<条目名>}，ns 来自数据包目录）在两种布局下完全一致；</li>
 *   <li><b>vanilla 机制层置末扫描 = 同名条目以它优先</b>——三运行时同一规则（§12.4 语义一致）。</li>
 * </ol>
 *
 * <p><b>不做（如实登记，docs/21 §5）</b>：标签目录（{@code tagsDirPath} 同样被两宿主前置，但本轮
 * 无标签夹具，登记标签的 mod 须按宿主布局放置，见 §5）；{@code getResource} / {@code getResourceStack} /
 * {@code listResourceStacks} 不做别名（元素装载链只用 {@code listResources(String, Predicate)}，
 * javap 实证 {@code ResourceManagerRegistryLoadTask} 经 {@code listMatchingResources} 扫描）。
 *
 * <p><b>线程 / 类加载</b>：构造后本类全部字段只读，任意线程安全；委托对象为调用方提供的资源管理器。
 * 本类引用 MC 类型（{@code Registries} / {@code Identifier} 等），与 {@code DataRegistryEngine}
 * 同包同纪律——只在装载调用期触达，不进入引导期类加载链。
 */
public final class BridgingResourceManager implements ResourceManager {

    /** 任务实际请求的前缀（宿主实况）→ 依次扫描的布局层（末层 = vanilla 机制层，同名优先）。 */
    private final Map<String, List<String>> layersByRequestedPrefix;

    private final ResourceManager delegate;

    private BridgingResourceManager(ResourceManager delegate,
                                    Map<String, List<String>> layersByRequestedPrefix) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.layersByRequestedPrefix = Map.copyOf(layersByRequestedPrefix);
    }

    /**
     * 桥接工厂：为每个已登记注册表计算布局层；无任何注册表需要桥接（全部为 minecraft 命名空间
     * 且宿主语义与 vanilla 机制一致）时原样返回 {@code manager}（零包装、零行为差异）。
     *
     * @param manager       装载用的资源管理器（调用方持有；本类只读它）
     * @param registrations 本次装载的登记快照（{@link DataRegistryEngine#reload} 传入）
     */
    public static ResourceManager wrapIfNeeded(ResourceManager manager,
                                               List<RegistryDataLoader.RegistryData<?>> registrations) {
        Objects.requireNonNull(manager, "manager");
        Map<String, List<String>> layersByRequested = new LinkedHashMap<>();
        for (RegistryDataLoader.RegistryData<?> data : registrations) {
            ResourceKey<? extends Registry<?>> key = data.key();
            Identifier id = key.identifier();
            String plain = id.getPath();                    // vanilla 机制布局（S1 实况）
            String host = Registries.elementsDirPath(key);  // 宿主实况（fabric / neoforge 变换已生效）
            List<String> layers = new ArrayList<>();
            if (!host.equals(plain)) {
                layers.add(host);                           // 宿主原生层
            }
            if (!id.getNamespace().equals("minecraft")) {   // 与两宿主的前置条件逐字对齐（它们只跳过 minecraft）
                String namespaced = id.getNamespace() + "/" + plain;
                if (!namespaced.equals(host) && !namespaced.equals(plain)) {
                    layers.add(namespaced);                 // 命名空间限定层（跨运行时接受对方布局）
                }
            }
            layers.add(plain);                              // vanilla 机制层置末：同名条目以它优先（固定规则）
            if (layers.size() > 1) {
                layersByRequested.put(host, List.copyOf(layers));
            }
        }
        return layersByRequested.isEmpty()
                ? manager
                : new BridgingResourceManager(manager, layersByRequested);
    }

    @Override
    public Map<Identifier, Resource> listResources(String path,
                                                   Predicate<Identifier> allowedPathPredicate) {
        List<String> layers = layersByRequestedPrefix.get(path);
        if (layers == null) {
            return delegate.listResources(path, allowedPathPredicate);  // 非已登记注册表的扫描：原样放行
        }
        Map<Identifier, Resource> merged = new LinkedHashMap<>();
        for (String layer : layers) {
            delegate.listResources(layer, allowedPathPredicate).forEach((id, resource) -> {
                String idPath = id.getPath();
                if (idPath.startsWith(layer + "/")) {
                    // 改写为请求前缀形状：任务内 fileToId 按请求前缀剥离，条目 id 两布局下一致（类 javadoc 第 3 条）。
                    merged.put(id.withPath(path + idPath.substring(layer.length())), resource);
                }
                // 理论不可达的防御分支：层扫描结果必以该层前缀开头；不匹配则跳过（不静默放行错 id）。
            });
        }
        return merged;
    }

    @Override
    public Optional<Resource> getResource(Identifier id) {
        return delegate.getResource(id);
    }

    @Override
    public Set<String> getNamespaces() {
        return delegate.getNamespaces();
    }

    @Override
    public List<Resource> getResourceStack(Identifier id) {
        return delegate.getResourceStack(id);
    }

    @Override
    public Map<Identifier, List<Resource>> listResourceStacks(String path,
                                                              Predicate<Identifier> allowedPathPredicate) {
        return delegate.listResourceStacks(path, allowedPathPredicate);
    }

    @Override
    public Stream<PackResources> listPacks() {
        return delegate.listPacks();
    }
}

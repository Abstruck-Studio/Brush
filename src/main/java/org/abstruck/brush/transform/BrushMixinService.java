package org.abstruck.brush.transform;

import org.abstruck.brush.BrushProperties;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.util.IConsumer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brush 的 Mixin 宿主服务（CLAUDE.md §8.5 / docs/06）。
 *
 * <p>sponge-mixin 的 jar 只注册 LaunchWrapper / ModLauncher 两个 {@code IMixinService}，
 * 自研类加载器宿主必须自实现本接口（Fabric 的 Knot 走的同一条路）。基类
 * {@link MixinServiceAbstract} 已给出大部分默认实现；本类补齐剩余抽象方法（12 个），
 * 每个方法的取舍依据见各方法注释。</p>
 *
 * <p><b>类加载器归属</b>：本类被 {@link BrushClassLoader} 加载（游戏侧），与 MC、mixin 库、
 * MixinExtras 同处一个类空间 —— 这是硬约束：注入点两侧的类必须共享类型身份（docs/06 §5）。
 * 服务实例由 {@code ServiceLoader} 无参构造（ServiceLoader 要求），
 * 因此加载器引用取自 {@code getClass().getClassLoader()}。</p>
 */
public final class BrushMixinService extends MixinServiceAbstract
        implements IClassProvider, IClassBytecodeProvider, IClassTracker, ITransformerProvider {

    private final ClassLoader loader;

    private final Set<String> invalidClasses = ConcurrentHashMap.newKeySet();

    /** {@code MixinEnvironment.init()} 经 {@code wire()} 下发的阶段回调（mixin 0.8.7 临时接线机制）。 */
    private volatile IConsumer<Phase> phaseConsumer;

    /** 经工厂创建的活动 transformer（全局唯一，见 {@link #createTransformer()}）。 */
    private volatile IMixinTransformer transformer;

    public BrushMixinService() {
        // 本类由 BrushClassLoader 加载，取到的即是游戏侧类加载器
        this.loader = this.getClass().getClassLoader();
    }

    // ---------------------------------------------------------------------
    // IMixinService —— 基本标识与有效性
    // ---------------------------------------------------------------------

    @Override
    public String getName() {
        return "Brush";
    }

    /**
     * 仅在 Brush standalone 管线内有效。
     *
     * <p>守卫理由（docs/06）：S2/S3 叠加模式下，宿主的 Mixin 也会做
     * {@code ServiceLoader<IMixinService>} 查找；若它能看到我们的注册文件，
     * 我们的服务绝不能被宿主选中 —— 标记属性 {@link BrushProperties#STANDALONE}
     * 只由 {@link TransformBootstrap#install()} 在 standalone 启动时设置，
     * 宿主引导路径不会设置它，因此宿主会跳过本服务。
     */
    @Override
    public boolean isValid() {
        return "true".equals(System.getProperty(BrushProperties.STANDALONE));
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return this;
    }

    @Override
    public IClassTracker getClassTracker() {
        return this;
    }

    /**
     * 返回 {@link BrushAuditTrail#INSTANCE}（mod mixin 命中台账的 S1 数据源，见该类 javadoc）。
     *
     * <p>mixin 核心在本服务上取该值两次：{@code MixinProcessor} 构造器（0.17.4 :233）与
     * {@code MixinApplicatorStandard} 构造器（:204）；两处及所有回调点均有非空守卫 ——
     * 提供非空实现是受支持路径（各官方宿主中仅 LaunchWrapper 返回 null），
     * 记录行为是被动的，不改变任何变换决策。S2/S3 下本服务不被选中（§8.5 守卫），
     * 宿主服务返回其自己的 trail（或无），我们的记录保持空集。</p>
     */
    @Override
    public IMixinAuditTrail getAuditTrail() {
        return BrushAuditTrail.INSTANCE;
    }

    /** 与两个官方宿主一致：{@code IFeatureValidator.ALLOW_ALL}。 */
    @Override
    public IFeatureValidator getFeatureValidator() {
        return IFeatureValidator.ALLOW_ALL;
    }

    /** 与两个官方宿主一致：{@code IAdviceProvider.GENERIC}。 */
    @Override
    public IAdviceProvider getAdviceProvider() {
        return IAdviceProvider.GENERIC;
    }

    /** 无平台 agent：容器发现由我们显式驱动（按 brush.mod.json 注册配置），不依赖 agent 扫描。 */
    @Override
    public Collection<String> getPlatformAgents() {
        return Collections.emptyList();
    }

    /** 主容器：虚拟句柄（{@code ContainerHandleVirtual}）。 */
    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual("brush");
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return this.loader.getResourceAsStream(name);
    }

    /**
     * mixin 日志缺省的替代（详见 {@link BrushMixinLoggerAdapter}）：基类默认
     * {@code createLogger} 返回 {@code LoggerAdapterDefault}（"No Logging"，全部消息丢弃），
     * 改为路由到 log4j —— 与 S2 的 Knot 行为对齐，且是 §7.3 overwrite 告警与
     * §14 自检报告"无 Mixin 冲突日志"的日志来源。基类 {@code getLogger} 按名缓存，
     * 本方法每名至多调用一次。
     */
    @Override
    protected ILogger createLogger(String name) {
        return new BrushMixinLoggerAdapter(name);
    }

    // ---------------------------------------------------------------------
    // IClassProvider
    // ---------------------------------------------------------------------

    // 0.17.4 将该方法标记为 @Deprecated，但接口仍是抽象方法，宿主必须实现；
    // 官方宿主（MixinServiceLaunchWrapper）同样实现它。抑制告警不等于掩盖失败（CLAUDE.md §3.5）。
    @Override
    @SuppressWarnings("deprecation")
    public URL[] getClassPath() {
        if (this.loader instanceof URLClassLoader urlClassLoader) {
            return urlClassLoader.getURLs();
        }
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, this.loader);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, this.loader);
    }

    /** 无 agent 概念；与 findClass 同语义（否则调用方无处可取）。 */
    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, this.loader);
    }

    // ---------------------------------------------------------------------
    // IClassBytecodeProvider
    // ---------------------------------------------------------------------

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return this.getClassNode(name, true, ClassReader.EXPAND_FRAMES);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return this.getClassNode(name, runTransformers, ClassReader.EXPAND_FRAMES);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int flags)
            throws ClassNotFoundException, IOException {
        byte[] bytes = this.getClassBytes(name, runTransformers);
        ClassNode classNode = new ClassNode();
        new ClassReader(bytes).accept(classNode, flags);
        return classNode;
    }

    /**
     * 读取类字节（{@code IClassBytecodeProvider} 的实现入口）。
     *
     * <p><b>{@code runTransformers} 的语义</b>：它要求跑的是"<b>除 mixin 自身以外</b>的委托
     * transformer 链"，而非"全部变换" —— 官方宿主先读原始字节，仅当 {@code runTransformers=true}
     * 时喂给委托 transformer 列表（该列表显式排除 mixin transformer 自身）；ModLauncher 宿主
     * 同理。我们的管线里<b>只有</b> mixin transformer（{@link #getDelegatedTransformers()} 为空），
     * 因此正确行为 = <b>原样返回原始字节</b>。</p>
     *
     * <p><b>为什么必须如此</b>：本方法唯一以 {@code runTransformers=true} 被调用的场景是 mixin
     * 内部读目标类元数据（{@code ClassInfo.forName}），发生于 PREPARE 阶段；此时把目标类喂回
     * 仍在 prepare 的管线会触发 0.17.4 的重入防御（{@code ReEntrantTransformerError}），该异常
     * 被 {@code ClassInfo.forName} 的 catch 吞成一条 WARN 并缓存 null，最终表现为
     * {@code InvalidMixinException: @Mixin target ... was not found} 硬失败。</p>
     */
    public byte[] getClassBytes(String className, boolean runTransformers) throws ClassNotFoundException, IOException {
        String binaryName = className.replace('/', '.');
        byte[] bytes = this.readClassBytes(binaryName);
        if (bytes == null) {
            throw new ClassNotFoundException("The specified class '" + binaryName + "' was not found");
        }
        // runTransformers 语义见 javadoc：委托列表为空 -> 无变换可跑 -> 返回原始字节
        return bytes;
    }

    private byte[] readClassBytes(String binaryName) throws IOException {
        try (InputStream in = this.loader.getResourceAsStream(binaryName.replace('.', '/') + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    // ---------------------------------------------------------------------
    // IClassTracker
    // ---------------------------------------------------------------------

    @Override
    public void registerInvalidClass(String className) {
        this.invalidClasses.add(className.replace('/', '.'));
    }

    /**
     * 恒 {@code false}（load-time-only 管线的正确取值）。
     *
     * <p>mixin 核心对它的唯一使用是"若目标类已加载则拒绝应用注入"。我们的变换发生在类首次
     * 定义之前（类定义时字节流刚好经过管线），被变换的类此刻尚未 load，诚实答案是 false；
     * 且实例内不支持热加配置，不存在"对已加载类应用注入"的场景。返回 true 反而会误伤正常注入。
     */
    @Override
    public boolean isClassLoaded(String className) {
        return false;
    }

    /** 无 transformer 排除清单（我们不委托任何外部 transformer，见 ITransformerProvider 一组）。 */
    @Override
    public String getClassRestrictions(String className) {
        return "";
    }

    // ---------------------------------------------------------------------
    // ITransformerProvider —— 无委托 transformer（变换直接由管线承担）
    // ---------------------------------------------------------------------

    @Override
    public Collection<ITransformer> getTransformers() {
        return Collections.emptyList();
    }

    @Override
    public Collection<ITransformer> getDelegatedTransformers() {
        return Collections.emptyList();
    }

    @Override
    public void addTransformerExclusion(String name) {
        // 空实现：我们没有可排除的委托 transformer 列表
    }

    // ---------------------------------------------------------------------
    // 阶段接线（mixin 0.8.7 的临时机制，MixinServiceAbstract.wire 默认只转发给 agent）
    // ---------------------------------------------------------------------

    /**
     * 接收 {@code MixinEnvironment.init()} 下发的阶段回调并保存。
     * 默认实现（{@code MixinServiceAbstract.wire}）只把回调转发给平台 agent ——
     * 我们无 agent，必须自行保存，否则阶段永远停在初始 PREINIT、mixin 不会生效。
     * 与 {@code MixinServiceModLauncher.wire} 行为一致 —— 该实现同样带
     * {@code @SuppressWarnings("deprecation")}：mixin 把整套接线机制标为 deprecated
     * （"TEMP WIRING"），但这是宿主推进阶段的唯一通道，方法本身是必需件。
     */
    @Override
    @SuppressWarnings("deprecation")
    public void wire(Phase phase, IConsumer<Phase> phaseConsumer) {
        super.wire(phase, phaseConsumer);
        this.phaseConsumer = phaseConsumer;
    }

    // ---------------------------------------------------------------------
    // Brush 内部接口（供 TransformBootstrap 使用，不属 IMixinService 契约）
    // ---------------------------------------------------------------------

    /**
     * 经 mixin 内部通道创建活动 transformer（全局唯一实例）。
     *
     * <p>{@code MixinBootstrap.start()} 会把 {@code MixinTransformer.Factory} 经
     * {@code service.offer(...)} 交给服务；从基类的内部表中取出工厂。
     * 工厂本身不去重（每次 create 都是新实例），因此本方法自行缓存：
     * 单实例是环境约定（构造器会把自身注册为 {@code MixinEnvironment} 的活动 transformer）。
     */
    IMixinTransformer createTransformer() {
        IMixinTransformer existing = this.transformer;
        if (existing != null) {
            return existing;
        }
        IMixinTransformerFactory factory = this.getInternal(IMixinTransformerFactory.class);
        if (factory == null) {
            throw new IllegalStateException(
                    "MixinTransformer factory not offered by MixinBootstrap - was MixinBootstrap.init() called first?");
        }
        IMixinTransformer created = factory.createTransformer();
        this.transformer = created;
        return created;
    }

    /** 经保存的阶段回调推进到 {@code DEFAULT}（唯一的合法推进通道，gotoPhase 为包私有）。 */
    void gotoDefaultPhase() {
        IConsumer<Phase> consumer = this.phaseConsumer;
        if (consumer == null) {
            throw new IllegalStateException("phase consumer not wired - MixinEnvironment.init() did not run");
        }
        consumer.accept(Phase.DEFAULT);
    }

    /** 供日志/自检读取当前 transformer（可能为 null，未创建时）。 */
    IMixinTransformer transformer() {
        return this.transformer;
    }

    /** 无效类清单只增不改，供自检输出。public：自检源集（独立包 {@code org.abstruck.brush.selftest}）跨包读取。 */
    public List<String> invalidClassList() {
        return List.copyOf(this.invalidClasses);
    }
}

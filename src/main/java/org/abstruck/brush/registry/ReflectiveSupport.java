package org.abstruck.brush.registry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 反射 / {@code MethodHandles} 机制支撑层（E2）—— <b>零 MC 引用</b>，
 * 与 {@code registry.ReflectiveRegistries}（MC 面向的调用面）成对：本类只提供机制，
 * 不知道任何 vanilla 类型，因此可在无游戏类路径的测试 JVM 中直测
 * （{@code src/test/java/org/abstruck/brush/registry/ReflectiveSupportTest.java}
 * 用"包私有 SAM + 私有构造器 + static final Map"三形态夹具全链路验证）。
 *
 * <p><b>为什么需要它</b>：26.1.2 的若干注册辅助面（{@code MenuType$MenuSupplier} /
 * {@code MenuScreens$ScreenConstructor} / {@code BlockEntityType$BlockEntitySupplier}）
 * 是<b>包私有接口</b> —— 连 {@code @Invoker} 都编译不过（mod 侧无法书写其类型）。
 * 本类的 {@link #instantiateSam} 在运行时生成实现实例，签名描述<b>运行时推导</b>
 * （{@link #describeSam}：{@code getMethods()} 中唯一的 abstract 方法）—— 不内嵌
 * 依赖手抄的签名，vanilla 小改（如默认方法增删）不破坏。</p>
 *
 * <p><b>SAM 生成的双路径（三运行时语义一致，产物都是 samInterface 的真实实例）</b>：
 * <ol>
 *   <li><b>LM 路径</b>（S1/S2）：{@code privateLookupIn} + {@link LambdaMetafactory}。
 *       LM 要求 full-privilege caller（JDK 规则证据行号原文 =
 *       <b>docs/22-实测快照与外部证据.md §2</b>，P2-4 整改迁出）；S1/S2 下
 *       SAM 与我们在同一个无名模块，{@code privateLookupIn} 保留 MODULE 位，满足；</li>
 *   <li><b>代理路径</b>（回退）：{@code java.lang.reflect.Proxy}。跨模块目标下
 *       {@code privateLookupIn} <b>必然</b>清掉 MODULE 位（JDK 规则证据 =
 *       docs/22-实测快照与外部证据.md §2）—— S3（NeoForge JPMS：SAM 在命名模块
 *       {@code minecraft}、我们在自动模块）下 LM 在 JDK 规则层面<b>不可达</b>
 *       （T3 首跑崩溃实测原文 = docs/22-实测快照与外部证据.md §2）。
 *       {@code Proxy} 恰有个专门规则："proxy class 非 public 地定义进非 public 接口
 *       所在的包与模块"（{@code Proxy} javadoc"选择代理类和模块"节；实现细节
 *       = {@code ProxyBuilder} 内部 {@code addOpens} + 经该模块的类加载器 define）。</li>
 * </ol>
 * 决策用公开 API（{@code Lookup#hasFullPrivilegeAccess()}）判定，不做异常消息匹配；
 * 每次回退记一条 INFO 日志（selftest 日志即机制证据）。</p>
 *
 * <p><b>失败语义</b>：一切失败路径（类外成员不可达 / 无此方法字段 / 目标不是单抽象方法接口 /
 * 字段不是 Map / 字段非 static …）一律 {@link IllegalStateException}，
 * 附 <b>javap 复现提示</b>（26.1.2 未混淆，官方名即可直接反编译核对）。不吞、不降级。</p>
 *
 * <p><b>线程模型</b>：全部调用发生在注册窗口 / 客户端注册 flush（单线程启动期），
 * 无同步设计；反射成员查询的开销只出现在控制流一次性的注册调用上。<b>非热路径</b>。</p>
 */
public final class ReflectiveSupport {

    private static final Logger LOGGER = LogManager.getLogger(ReflectiveSupport.class);

    /** 无参 SAM 的代理调度用（本代三个 SAM 均多参；防御通用性）。 */
    private static final Object[] EMPTY_ARGS = new Object[0];

    private ReflectiveSupport() {
    }

    /**
     * 单抽象方法描述（运行时推导结果）：方法名 + <b>擦除后</b>的 MethodType。
     *
     * @param name 抽象方法名（如 {@code create}）
     * @param type 擦除后的方法类型（参数与返回均已是 Class），供 {@link LambdaMetafactory} 的
     *             {@code samMethodType} / {@code instantiatedMethodType} 使用
     */
    record Sam(String name, MethodType type) { }

    /**
     * 在 {@code samInterface}（可为包私有）上生成一个实现实例，实现方法委托给
     * {@code implementation}，并把 {@code capture} 作为实现句柄的前导参数绑定
     * （即 {@code implementation} 的形如 {@code (捕获, SAM 参数...) 返回}）。
     *
     * <p><b>双路径</b>：优先 LM（{@code privateLookupIn} + {@link LambdaMetafactory}，
     * 与 javac 为捕获式 lambda 生成的 invokedynamic 同构：工厂类型 =
     * {@code (captureType)samInterface}，SAM 类型 = {@link #describeSam} 的擦除形）；
     * 当调用方 lookup 拿不到 full-privilege（{@code hasFullPrivilegeAccess()} 为 false，
     * 即跨模块宿主，S3）时自动回退 {@link #instantiateSamViaProxy}，每次回退记一条
     * INFO 日志。两路径共用同一实现句柄形态（下方可达性要求适用于 LM 路径）。</p>
     *
     * <p>实现句柄由调用方以 {@code MethodHandles.lookup().findStatic(...)} 获得
     * —— <b>实现方法必须 public static，且宿主类必须 public</b>。
     * 实证（单测首轮红）：LM 以 {@code caller.revealDirect(impl)} 做可达性检查，
     * caller = {@code privateLookupIn} 结果 —— 它<b>不继承</b>原 lookup 对第三方类的
     * 私有访问，包私有宿主类会被拒绝（{@code LambdaConversionException:
     * ... is not direct or cannot be cracked}；JDK 源码 {@code AbstractValidatingLambdaMetafactory}
     * 第 138-141 行 + {@code Lookup.revealDirect} 的 checkAccess）。</p>
     *
     * @param samInterface 目标（包私有）SAM 接口的 Class 对象
     * @param captureType  捕获参数的静态类型（= 实现句柄首参类型）
     * @param implementation 实现句柄（direct 或 bound 均可；须与 factoryType+SAM 拼接后的擦除形一致）
     * @param capture      捕获实例（通常为 mod 提供的公开函数式接口实现）
     * @return 生成的 SAM 实例（调用方以 Object 持有 —— 类型本身往往不可命名）
     */
    public static Object instantiateSam(Class<?> samInterface, Class<?> captureType,
                                        MethodHandle implementation, Object capture) {
        Objects.requireNonNull(samInterface, "samInterface");
        Objects.requireNonNull(captureType, "captureType");
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(capture, "capture");
        Sam sam = describeSam(samInterface);

        // 路径选择（决策 = 公开 API looker.hasFullPrivilegeAccess，不做异常消息匹配；
        // 两条路径产物对调用方完全同义，跨模块原因与 JDK 规则见类 javadoc 双路径节）。
        MethodHandles.Lookup lookup = null;
        String lmBlocked;
        try {
            lookup = MethodHandles.privateLookupIn(samInterface, MethodHandles.lookup());
            lmBlocked = lookup.hasFullPrivilegeAccess() ? null
                    : "privateLookupIn dropped the MODULE access bit (cross-module target: the SAM"
                    + " lives in a different module than Brush -- e.g. NeoForge's named 'minecraft'"
                    + " module vs our automatic module -- and LambdaMetafactory requires a"
                    + " full-privilege caller)";
        } catch (IllegalAccessException e) {
            lmBlocked = "privateLookupIn was refused for " + samInterface.getName()
                    + " (" + e.getMessage() + " -- target module does not open the package to us?)";
        }

        if (lmBlocked != null) {
            LOGGER.info("[brush] registry: LambdaMetafactory unavailable for " + samInterface.getName()
                    + " -- " + lmBlocked + "; using a java.lang.reflect.Proxy bridge instead"
                    + " (the JDK defines the proxy class into the interface's own package and module)");
            return instantiateSamViaProxy(samInterface, implementation, capture, lookup, lmBlocked);
        }

        MethodType factoryType = MethodType.methodType(samInterface, captureType);
        CallSite site;
        try {
            site = LambdaMetafactory.metafactory(lookup, sam.name(), factoryType, sam.type(),
                    implementation, sam.type());
        } catch (LambdaConversionException e) {
            throw failure("LambdaMetafactory rejected the conversion for " + samInterface.getName()
                    + " (SAM " + sam.name() + sam.type() + ") -- implementation handle type: "
                    + implementation.type() + ". Expected implementation type: "
                    + factoryType.parameterType(0) + " + " + sam.type()
                    + " (capture + SAM parameters), public static", e);
        }
        try {
            return site.getTarget().invoke(capture);
        } catch (Throwable t) {
            throw failure("SAM factory invocation failed for " + samInterface.getName(), t);
        }
    }

    /**
     * 回退路径：{@code java.lang.reflect.Proxy} 动态代理（{@link #instantiateSam} 在 LM
     * 不可达时调用；触发条件与 JDK 规则证据见类 javadoc 双路径节，生产实测 = T3/S3）。
     *
     * <p><b>为什么代理是等义的</b>：{@code Proxy} 对"至少一个接口非 public"有专门规则 ——
     * proxy class 非 public 地定义在非 public 接口<b>所在的包与模块</b>（{@code Proxy}
     * javadoc"选择代理类和模块"节；实现 {@code ProxyBuilder}：对命名模块内部
     * {@code addOpens} + 经该模块的类加载器 define）。产物是一个真实实现了
     * {@code samInterface} 的实例，vanilla 侧 {@code invokeinterface} 派发到本 handler，
     * 与 LM 产物对 vanilla 无可观察差异；Object 三方法本地实现（identity 语义）。</p>
     *
     * <p><b>默认方法的派发（实证修正，单测首轮红）</b>：不能用
     * {@link InvocationHandler#invokeDefault} —— 它内部以 {@code Reflection.checkAccess}
     * 对<b>调用方类</b>（= 本类）做访问检查（{@code Proxy.invokeDefault} 第 1166 行），
     * 而包私有接口的成员对本类（不同包）不可达，三运行时一律
     * {@code IllegalAccessException}（单测实测）。改经 {@code lookup.findSpecial
     * (samInterface, name, type, samInterface)} 取句柄：{@code checkSpecialCaller}
     * （{@code MethodHandles.java} 第 3792-3798 行）对 {@code specialCaller ==
     * lookupClass} 且 PRIVATE 模式放行，而 {@code privateLookupIn} 结果恰好保持
     * PRIVATE（跨模块只掉 MODULE）；{@code invokespecial} 语义直接落到接口自己的
     * default 实现，绕过代理类生成的覆写方法（不会派回 handler 无限递归）。
     * 26.1.2 的 {@code ScreenConstructor#fromPacket} 即此类。</p>
     *
     * <p><b>调度</b>：SAM 抽象方法经 {@code implementation.bindTo(capture)} 句柄 +
     * {@code invokeWithArguments}（装箱路径）。三个注册面均为低频率调用点
     * （菜单开启 / 屏幕创建 / 方块实体实例化），<b>非热路径</b>。若到场的抽象方法
     * 与句柄形状不符，{@code invokeWithArguments} 抛 {@code WrongMethodTypeException}
     * 原样穿透给调用方（响亮失败，不吞）。</p>
     *
     * <p>包私有可见性（test 源集直测含 default 方法派发）：
     * {@code org.abstruck.brush.registry.ReflectiveSupportTest}。</p>
     *
     * @param lookup {@link #instantiateSam} 的 {@code privateLookupIn} 结果
     *               （仅 {@code privateLookupIn} 本身被拒时为 null —— 彼时 default
     *               方法派发不可用，到调用现场响亮失败）
     */
    static Object instantiateSamViaProxy(Class<?> samInterface, MethodHandle implementation,
                                         Object capture, MethodHandles.Lookup lookup, String reason) {
        MethodHandle bound = implementation.bindTo(capture);
        Map<Method, MethodHandle> defaultHandles = new ConcurrentHashMap<>();
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Brush SAM bridge for " + samInterface.getName();
                    default -> throw new IllegalStateException("unexpected Object method: " + method);
                };
            }
            if (method.isDefault()) {
                MethodHandle defaultImpl = defaultHandles.computeIfAbsent(method,
                        m -> resolveDefaultHandle(lookup, samInterface, m, reason));
                return invokeWithArgs(defaultImpl, proxy, args);
            }
            return invokeWithArgs(bound, null, args);
        };
        try {
            return Proxy.newProxyInstance(samInterface.getClassLoader(),
                    new Class<?>[]{samInterface}, handler);
        } catch (IllegalArgumentException | InternalError e) {
            // IllegalArgumentException = Proxy 文档的 "not possible"（接口可见性 / 加载器约束）；
            // InternalError = JDK 模块一致性检查（ProxyBuilder.ProxyClassContext）。
            throw failure("Proxy bridge failed for " + samInterface.getName() + " (" + reason + "): "
                    + e.getMessage(), e);
        }
    }

    /**
     * 解析包私有接口 default 方法的 {@code invokespecial} 句柄（见
     * {@link #instantiateSamViaProxy} 默认方法派发节）；句柄类型 = {@code (samInterface,
     * 参数...)返回}，调用时 receiver 传代理实例（实现类 = samInterface 的子类型）。
     */
    private static MethodHandle resolveDefaultHandle(MethodHandles.Lookup lookup, Class<?> samInterface,
                                                     Method method, String reason) {
        if (lookup == null) {
            throw failure("cannot dispatch default method " + method + " of " + samInterface.getName()
                    + " on the proxy bridge: no private lookup is available (" + reason + ")", null);
        }
        try {
            return lookup.findSpecial(samInterface, method.getName(),
                    MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                    samInterface);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw failure("cannot resolve default method " + method + " of " + samInterface.getName()
                    + " for proxy dispatch (" + reason + ")", e);
        }
    }

    /** {@code invokeWithArguments} 变参形态（可选前导 receiver 用于 default 方法句柄）。 */
    private static Object invokeWithArgs(MethodHandle handle, Object receiver, Object[] args)
            throws Throwable {
        Object[] a = args == null ? EMPTY_ARGS : args;
        if (receiver == null) {
            return handle.invokeWithArguments(a);
        }
        Object[] full = new Object[a.length + 1];
        full[0] = receiver;
        System.arraycopy(a, 0, full, 1, a.length);
        return handle.invokeWithArguments(full);
    }

    /**
     * 运行时推导单抽象方法描述：{@code getMethods()} 中唯一的 {@code abstract} 方法
     * （默认 / static 方法不计 —— 如 26.1.2 的 {@code ScreenConstructor#fromPacket} 默认方法）。
     * 数量不为 1 即失败（附候选清单），不做"猜一个"的取舍。
     */
    static Sam describeSam(Class<?> samInterface) {
        if (!samInterface.isInterface()) {
            throw failure(samInterface.getName() + " is not an interface -- expected a single-abstract-"
                    + "method interface (SAM) target", null);
        }
        List<Method> abstractMethods = new ArrayList<>();
        for (Method method : samInterface.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers())) {
                abstractMethods.add(method);
            }
        }
        if (abstractMethods.size() != 1) {
            StringBuilder candidates = new StringBuilder();
            for (Method method : abstractMethods) {
                candidates.append("\n  ").append(method);
            }
            throw failure("expected exactly 1 abstract method on " + samInterface.getName()
                    + ", found " + abstractMethods.size() + ":" + candidates
                    + " -- if vanilla changed this interface shape, adapt ReflectiveSupport/ReflectiveRegistries",
                    null);
        }
        Method sam = abstractMethods.get(0);
        return new Sam(sam.getName(),
                MethodType.methodType(sam.getReturnType(), sam.getParameterTypes()));
    }

    /**
     * 反射实例化（private 构造器直调；如 {@code MenuType} / {@code BlockEntityType} 在
     * 26.1.2 均无公开构造器也无 Builder）。
     */
    public static <T> T newInstance(Class<T> type, Class<?>[] parameterTypes, Object... arguments) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        Constructor<T> constructor;
        try {
            constructor = type.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException e) {
            throw failure("no declared constructor " + type.getName() + sign(parameterTypes)
                    + " -- signature changed in this MC version?", e);
        }
        setAccessibleOrFail(constructor, type);
        try {
            return constructor.newInstance(arguments);
        } catch (InstantiationException | IllegalAccessException e) {
            throw failure("constructor " + type.getName() + sign(parameterTypes) + " not invocable", e);
        } catch (InvocationTargetException e) {
            throw failure("constructor " + type.getName() + sign(parameterTypes)
                    + " threw " + e.getCause(), e.getCause());
        }
    }

    /**
     * 反射调用 private static 方法（如 {@code SpawnPlacements#register}）。
     */
    public static Object invokeStatic(Class<?> owner, String name, Class<?>[] parameterTypes,
                                      Object... arguments) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        Method method;
        try {
            method = owner.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw failure("no declared method " + owner.getName() + "#"
                    + name + sign(parameterTypes) + " -- signature changed in this MC version?", e);
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            throw failure(owner.getName() + "#" + name + " is not static", null);
        }
        setAccessibleOrFail(method, owner);
        try {
            return method.invoke(null, arguments);
        } catch (IllegalAccessException e) {
            throw failure("method " + owner.getName() + "#" + name + " not invocable", e);
        } catch (InvocationTargetException e) {
            throw failure("method " + owner.getName() + "#" + name + " threw " + e.getCause(),
                    e.getCause());
        }
    }

    /**
     * <b>原地</b>变更一个 private static Map 字段所引用的<b>可变</b>映射：读字段
     * （自动触发类初始化 —— {@code Field.get} 对 static 字段含 clinit 语义）
     * → 直接在活对象上施加 {@code mutation}（异常原样穿透）。vanilla 自身对这几张表
     * 同样原地 {@code Map.put}（如 {@code MenuScreens.register}、{@code EntityRenderers.register}），
     * 本方法与其忠实一致。</p>
     *
     * <p><b>为什么原地而非复制写回</b>：26.1.2 的三张表 clinit 分别为
     * {@code MenuScreens.SCREENS} = {@code Maps.newHashMap()}、
     * {@code EntityRenderers.PROVIDERS} = fastutil {@code Object2ObjectOpenHashMap}、
     * {@code BlockEntityRenderers.PROVIDERS} = {@code Maps.newHashMap()}（javap 逐条核实）——
     * 均为<b>可变</b>映射，且读方（{@code createEntityRenderers} / {@code getConstructor}）
     * 在稍后一次性读取。原地 put 完全不写 static final 字段，天然绕开 JDK 25 对
     * static final 的写限制（机制对照与证据见 {@link #replaceStaticMap}）。</p>
     *
     * <p>适用范围：仅用于运行时值为<b>可变</b>映射的字段。运行时值为不可变映射
     * （如 {@code DefaultAttributes.SUPPLIERS} 的 clinit ImmutableMap）时用
     * {@link #replaceStaticMap}。</p>
     *
     * @param owner    字段所属类（vanilla 类，无名模块）
     * @param fieldName 字段名（如 {@code PROVIDERS} / {@code SCREENS}）
     * @param mutation 作用于活映射的变更动作（如 {@code map.put(k, v)}）
     */
    public static void mutateStaticMap(Class<?> owner, String fieldName,
                                       Consumer<Map<Object, Object>> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        mutation.accept(readMap(staticMapField(owner, fieldName)));
    }

    /**
     * 替换一个 private static Map 字段（读 → 复制进 {@link HashMap} → 施加 {@code mutation}
     * → 整体写回），供运行时值为<b>不可变</b>映射（clinit 构建的 ImmutableMap，无法原地变更）
     * 的字段使用。{@code mutation} 抛异常时字段<b>未</b>被写回（先变副本、后写字段）。
     *
     * <p><b>为什么必须经 {@code sun.misc.Unsafe}（JDK 25 实证 + 源码定位）</b>：
     * <ul>
     *   <li>{@code Field.set}：JDK 17+ javadoc 规定写 final 字段要求
     *       {@code "the field is non-static"} —— static final 抛
     *       {@code IllegalAccessException("Can not set static final ... field")}
     *       （本仓库单测首轮实测红，见 {@code ReflectiveSupportTest}）；</li>
     *   <li>{@code VarHandle}：{@code MethodHandles.Lookup.getFieldVarHandleCommon}
     *       （JDK 25 源码 3927-3946 行）明确 "A VarHandle does not support updates to
     *       final fields"，产物为只读句柄（实证：{@code UnsupportedOperationException: set}）；
     *       唯一放行路径要求 TRUSTED lookup（{@code VarHandles.makeFieldHandle} 的
     *       {@code isWriteAllowedOnFinalFields} 条件 {@code allowedModes == TRUSTED}），
     *       应用代码不可获得；</li>
     *   <li>{@code sun.misc.Unsafe#staticFieldBase/Offset + putObject}：同 JDK 实证可用的
     *       唯一机制。</li>
     * </ul>
     * 调用 {@code staticFieldOffset/staticFieldBase} 会触发 JDK 24+ 的一次性 terminus
     * 警告（JEP 498，stderr，属预期噪音，selftest 日志可见）。若 JVM 以
     * {@code --sun-misc-unsafe-memory-access=deny} 运行，调用<b>响亮失败</b>
     * （包装为带修复指引的 ISE，不静默）。</p>
     *
     * @param owner    字段所属类（vanilla 类，无名模块）
     * @param fieldName 字段名（如 {@code SUPPLIERS}）
     * @param mutation 在副本上的变更动作；可借此做重复项检查（抛异常即整体放弃）
     */
    public static void replaceStaticMap(Class<?> owner, String fieldName,
                                        Consumer<Map<Object, Object>> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        Field field = staticMapField(owner, fieldName);
        HashMap<Object, Object> copy = new HashMap<>(readMap(field));
        mutation.accept(copy);
        writeStaticField(field, copy);
    }

    /** 字段解析 + static 校验 + setAccessible（读取路径共用）。 */
    private static Field staticMapField(Class<?> owner, String fieldName) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(fieldName, "fieldName");
        Field field;
        try {
            field = owner.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            throw failure("no field " + owner.getName() + "#" + fieldName
                    + " -- field renamed in this MC version?", e);
        }
        if (!Modifier.isStatic(field.getModifiers())) {
            throw failure(owner.getName() + "#" + fieldName + " is not static", null);
        }
        setAccessibleOrFail(field, owner);
        return field;
    }

    /** 读取并校验字段当前值为 Map（{@code Field.get} 对 static 字段含 clinit 语义）。 */
    private static Map<Object, Object> readMap(Field field) {
        Object current;
        try {
            current = field.get(null);
        } catch (IllegalAccessException e) {
            throw failure("field " + field.getDeclaringClass().getName() + "#" + field.getName()
                    + " not readable", e);
        }
        if (!(current instanceof Map)) {
            throw failure("field " + field.getDeclaringClass().getName() + "#" + field.getName()
                    + " does not hold a Map (found: " + (current == null ? "null"
                    : current.getClass().getName()) + ")", null);
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> map = (Map<Object, Object>) current;
        return map;
    }

    /** 经 sun.misc.Unsafe 写 static 字段（static final 唯一可行路径，见 {@link #replaceStaticMap}）。 */
    private static void writeStaticField(Field field, Object value) {
        if (UnsafeAccess.FAILURE != null) {
            throw failure("sun.misc.Unsafe is unavailable on this JVM (jdk.unsupported module missing?),"
                    + " but it is the only mechanism able to replace the static final field "
                    + field.getDeclaringClass().getName() + "#" + field.getName()
                    + " on this JDK (see replaceStaticMap javadoc)", UnsafeAccess.FAILURE);
        }
        try {
            long offset = (long) UnsafeAccess.STATIC_FIELD_OFFSET.invoke(UnsafeAccess.INSTANCE, field);
            Object base = UnsafeAccess.STATIC_FIELD_BASE.invoke(UnsafeAccess.INSTANCE, field);
            UnsafeAccess.PUT_OBJECT.invoke(UnsafeAccess.INSTANCE, base, offset, value);
        } catch (InvocationTargetException e) {
            throw failure("Unsafe static write refused for " + field.getDeclaringClass().getName()
                    + "#" + field.getName() + " (JVM running with --sun-misc-unsafe-memory-access=deny?)",
                    e.getCause());
        } catch (IllegalAccessException e) {
            throw failure("Unsafe method not invocable for " + field.getDeclaringClass().getName()
                    + "#" + field.getName(), e);
        }
    }

    /**
     * 惰性持有的 {@code sun.misc.Unsafe} 机械句柄（首次 {@link #replaceStaticMap} 时初始化）。
     * 惰性 + 失败捕获：仅原地路径（{@link #mutateStaticMap}）的使用者不会触碰本类，
     * 缺 jdk.unsupported 的 JVM 也不会在无关路径上一并失败。
     */
    private static final class UnsafeAccess {
        static final Object INSTANCE;
        static final Method STATIC_FIELD_OFFSET;
        static final Method STATIC_FIELD_BASE;
        static final Method PUT_OBJECT;
        static final Throwable FAILURE;

        static {
            Object instance = null;
            Method offset = null;
            Method base = null;
            Method put = null;
            Throwable failure = null;
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                instance = theUnsafe.get(null);
                offset = unsafeClass.getMethod("staticFieldOffset", Field.class);
                base = unsafeClass.getMethod("staticFieldBase", Field.class);
                put = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            } catch (Throwable t) {
                failure = t;
            }
            INSTANCE = instance;
            STATIC_FIELD_OFFSET = offset;
            STATIC_FIELD_BASE = base;
            PUT_OBJECT = put;
            FAILURE = failure;
        }

        private UnsafeAccess() {
        }
    }

    private static void setAccessibleOrFail(AccessibleObject member, Class<?> owner) {
        try {
            member.setAccessible(true);
        } catch (RuntimeException e) {
            throw failure("cannot open " + member + " on " + owner.getName()
                    + " (strong encapsulation? vanilla classes are expected in the unnamed module)",
                    e);
        }
    }

    /**
     * 统一失败构造。javap 复现提示保留操作指引，但 jar 文件名占位符化
     * （原"26.1.2.jar"钉死形态的原文 = <b>docs/22-实测快照与外部证据.md §3</b>，
     * P2-4 整改迁出：版本升级后钉死文件名会误导）。
     */
    static IllegalStateException failure(String what, Throwable cause) {
        return new IllegalStateException("[brush] reflective registry support failed: " + what
                + " -- reproduce/verify with: javap -p -classpath <vanilla client jar> <owner>"
                + " (unobfuscated: official names are the runtime names; evidence snapshots: docs/22)", cause);
    }

    private static String sign(Class<?>[] parameterTypes) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parameterTypes[i].getName());
        }
        return sb.append(')').toString();
    }
}

package org.abstruck.brush.registry;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacementType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.abstruck.brush.api.BrushRegistration;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;

/**
 * vanilla 反射注册面（E2，内部）—— <b>反射的唯一集中点</b>（评审 P0-2 的处置）：
 * 所有"vanilla 没有公开 API 的注册位"都在本类里落地，mod 永不接触本类
 * （对外一律走 {@code api.BrushRegistry}，见 CLAUDE.md §6 的 L1/L2 分层）。
 *
 * <p><b>覆盖面（26.1.2，全部经 javap 逐条核实；证据表见 F 阶段文档）</b>：
 * <ul>
 *   <li>{@code MenuType$MenuSupplier} / {@code MenuScreens$ScreenConstructor} /
 *       {@code BlockEntityType$BlockEntitySupplier} —— 包私有 SAM，经
 *       {@link ReflectiveSupport#instantiateSam} 运行时生成实现（S1/S2 走 LM；
 *       跨模块宿主 S3 自动回退 {@code java.lang.reflect.Proxy} —— 决策、JDK 规则证据
 *       与实测见 {@code ReflectiveSupport} 类 javadoc 双路径节；单测 =
 *       {@code ReflectiveSupportTest}）；</li>
 *   <li>{@code MenuType(MenuSupplier, FeatureFlagSet)} /
 *       {@code BlockEntityType(BlockEntitySupplier, Set)} —— package-private 构造器直构
 *       （26.1.2 无公开构造器、无 Builder）；</li>
 *   <li>{@code MenuScreens.SCREENS} / {@code EntityRenderers.PROVIDERS} /
 *       {@code BlockEntityRenderers.PROVIDERS} —— private static Map 字段，clinit
 *       构造为<b>可变</b>映射（{@code Maps.newHashMap()} / fastutil
 *       {@code Object2ObjectOpenHashMap}，javap 核实），经
 *       {@link ReflectiveSupport#mutateStaticMap} <b>原地</b>变更（同 vanilla 自身的
 *       register 行为）；</li>
 *   <li>{@code DefaultAttributes.SUPPLIERS} —— clinit ImmutableMap（不可原地变更），
 *       经 {@link ReflectiveSupport#replaceStaticMap} 复制-替换（static final 写回走
 *       Unsafe，机制与证据见该方法 javadoc）。</li>
 *   <li>{@code SpawnPlacements#register} —— private static，反射直调。</li>
 * </ul>
 *
 * <p><b>与 vanilla 语义的一致性（逐点核实，非推断）</b>：
 * <ul>
 *   <li>{@code MenuScreens.register} 对重复注册抛 {@code IllegalStateException}
 *       （javap：{@code Map.put} 返回非 null 即抛；注意 vanilla 是 put-后-检查，
 *       冲突时新值已落入）—— 本类 {@code registerMenuScreen} 改为检查-后-put：
 *       冲突抛出时映射<b>零扰动</b>（比 vanilla 原版更强的原子性，语义仍为 ISE）；</li>
 *   <li>{@code EntityRenderers/BlockEntityRenderers.register} 为裸 {@code Map.put}
 *       （无重复检查，重复=静默覆盖）—— 本类照抄；</li>
 *   <li>{@code EntityType.Builder#build(ResourceKey)} <b>不自注册</b>
 *       （javap：构造后直接 areturn）—— 本类补 {@code Registry.register}；</li>
 *   <li>{@code DefaultAttributes.SUPPLIERS} 存的是<b>已构建</b>的
 *       {@code AttributeSupplier} —— 本类对 {@code builder.build()} 之后的产物做写入。</li>
 * </ul>
 *
 * <p><b>类加载纪律（与 {@code api.BrushRegistry} 相同）</b>：本类引用 MC 类型，
 * <b>只允许被注册窗口 / 客户端注册 flush 路径上的代码引用</b>（即 {@code BrushRegistry}
 * 方法体内）；不得在引导期路径（如 {@code EarlyBoot} 可达的类）的字段初始化器或方法体里
 * 触碰本类 —— 否则会把注册表 / 客户端类拖进引导期类加载链。类常量的 {@code forName}
 * 用 {@code initialize=false} 且限定本类加载器（不得用 TCCL —— 宿主下会跨加载器取到双份类）。</p>
 *
 * <p><b>失败语义</b>：一切失配（类 / 构造器 / 字段 / 方法不存在、签名变化、强封装拒绝）
 * → {@link IllegalStateException} 带 javap 复现提示（{@code ReflectiveSupport#failure} 模板）。</p>
 */
public final class ReflectiveRegistries {

    // ------------------------------------------------------------------
    // 目标形态（运行时解析；initialize=false —— 这些接口的 clinit 无意义，且避免引导期初始化）
    // ------------------------------------------------------------------

    private static final Class<?> MENU_SUPPLIER = resolve("net.minecraft.world.inventory.MenuType$MenuSupplier");
    private static final Class<?> SCREEN_CONSTRUCTOR =
            resolve("net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor");
    private static final Class<?> BLOCK_ENTITY_SUPPLIER =
            resolve("net.minecraft.world.level.block.entity.BlockEntityType$BlockEntitySupplier");

    // ------------------------------------------------------------------
    // SAM 桥（public static —— LM 路径对 impl 的可达性检查所要求；代理回退路径无此要求，
    // 保持同一可见性以免双路径形态分叉。签名 = (捕获 + SAM 参数擦除形) → SAM 返回擦除形）
    // ------------------------------------------------------------------

    private static final MethodHandle MENU_SUPPLIER_BRIDGE = bridge("menuSupplierBridge",
            MethodType.methodType(AbstractContainerMenu.class,
                    BrushRegistration.BrushMenuSupplier.class, int.class, Inventory.class));
    private static final MethodHandle SCREEN_CONSTRUCTOR_BRIDGE = bridge("screenFactoryBridge",
            MethodType.methodType(Screen.class, BrushRegistration.BrushScreenFactory.class,
                    AbstractContainerMenu.class, Inventory.class, Component.class));
    private static final MethodHandle BLOCK_ENTITY_SUPPLIER_BRIDGE = bridge("blockEntitySupplierBridge",
            MethodType.methodType(BlockEntity.class, BrushRegistration.BrushBlockEntitySupplier.class,
                    BlockPos.class, BlockState.class));

    private ReflectiveRegistries() {
    }

    private static Class<?> resolve(String name) {
        try {
            return Class.forName(name, false, ReflectiveRegistries.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw ReflectiveSupport.failure("target class missing: " + name
                    + " (expected in the " + ReflectiveRegistries.class.getClassLoader()
                    + " class loading chain; do NOT use TCCL)", e);
        }
    }

    private static MethodHandle bridge(String method, MethodType type) {
        try {
            return MethodHandles.lookup().findStatic(ReflectiveRegistries.class, method, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw ReflectiveSupport.failure("bridge method " + method + type + " not found"
                    + " -- the bridge must stay public static with the erased (capture + SAM) shape", e);
        }
    }

    // ------------------------------------------------------------------
    // SAM 桥实现体
    // ------------------------------------------------------------------

    /** 擦除桥：{@code (BrushMenuSupplier, int, Inventory) → AbstractContainerMenu}。 */
    public static AbstractContainerMenu menuSupplierBridge(
            BrushRegistration.BrushMenuSupplier<?> supplier, int containerId, Inventory inventory) {
        return supplier.create(containerId, inventory);
    }

    /**
     * 擦除桥：{@code (BrushScreenFactory, AbstractContainerMenu, Inventory, Component) → Screen}。
     * 泛型形与 SAM 擦除形恰好重合（返回擦除为 Screen），无未检查转换；
     * findStatic 以擦除 MethodType 取句柄（{@link ReflectiveSupport} 单测同款路径）。
     */
    public static <M extends AbstractContainerMenu, S extends Screen & MenuAccess<M>> Screen screenFactoryBridge(
            BrushRegistration.BrushScreenFactory<M, S> factory, M menu, Inventory inventory, Component title) {
        return factory.create(menu, inventory, title);
    }

    /** 擦除桥：{@code (BrushBlockEntitySupplier, BlockPos, BlockState) → BlockEntity}。 */
    public static BlockEntity blockEntitySupplierBridge(
            BrushRegistration.BrushBlockEntitySupplier<?> supplier, BlockPos pos, BlockState state) {
        return supplier.create(pos, state);
    }

    // ------------------------------------------------------------------
    // 服务端内容面（全部只允许在 CONTENT_REGISTRATION 窗口内经 BrushRegistry 调用）
    // ------------------------------------------------------------------

    /**
     * 实体类型：{@code builder.build(key)}（不自注册，javap 核实）→ 补
     * {@code Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type)}。
     */
    public static <T extends Entity> EntityType<T> registerEntityType(Identifier id,
                                                                      EntityType.Builder<T> builder) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
        EntityType<T> type = builder.build(key);
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type);
    }

    /**
     * 实体属性：{@code builder.build()} 后把<b>已构建</b>的 {@code AttributeSupplier}
     * 写入 {@code DefaultAttributes.SUPPLIERS}（clinit ImmutableMap → 复制-
     * {@link ReflectiveSupport#replaceStaticMap 整体替换}，static final 写回走 Unsafe）。
     * 26.1.2 无公开属性注册路径（javap：{@code EntityType} 无任何 attribute 成员、
     * {@code LivingEntity} 直接调 {@code DefaultAttributes.getSupplier}）—— 反射写入是唯一途径。
     */
    public static <T extends LivingEntity> void registerAttributes(EntityType<T> type,
                                                                   AttributeSupplier.Builder builder) {
        AttributeSupplier built = builder.build();
        ReflectiveSupport.replaceStaticMap(DefaultAttributes.class, "SUPPLIERS",
                map -> map.put(type, built));
    }

    /**
     * 方块实体类型：包私有构造器直构（{@code BlockEntitySupplier} SAM 由 {@link ReflectiveSupport#instantiateSam} 生成（双路径））+
     * 注册。返回值的泛型由调用方（注册键 / 工厂类型）共同保证 —— 构造时的 SAM 与
     * Set 均为运行时检查。
     */
    public static <T extends BlockEntity> BlockEntityType<T> registerBlockEntityType(Identifier id,
            BrushRegistration.BrushBlockEntitySupplier<T> supplier, Block... blocks) {
        Object sam = ReflectiveSupport.instantiateSam(BLOCK_ENTITY_SUPPLIER,
                BrushRegistration.BrushBlockEntitySupplier.class, BLOCK_ENTITY_SUPPLIER_BRIDGE, supplier);
        BlockEntityType<?> raw = ReflectiveSupport.newInstance(BlockEntityType.class,
                new Class<?>[]{BLOCK_ENTITY_SUPPLIER, Set.class}, sam, Set.of(blocks));
        @SuppressWarnings("unchecked") // 擦除桥：类型参数在构造时经 SAM 实例完成检查（见上方 javadoc）
        BlockEntityType<T> type = (BlockEntityType<T>) raw;
        return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, type);
    }

    /**
     * 菜单类型：包私有构造器直构（{@code MenuSupplier} SAM 由 {@link ReflectiveSupport#instantiateSam} 生成（双路径））+ 注册。
     * {@code requiredFeatures} 与 vanilla 构造器第二参同义（{@code FeatureFlagSet.of()} = 无要求）。
     */
    public static <M extends AbstractContainerMenu> MenuType<M> registerMenuType(Identifier id,
            BrushRegistration.BrushMenuSupplier<M> supplier, FeatureFlagSet requiredFeatures) {
        Object sam = ReflectiveSupport.instantiateSam(MENU_SUPPLIER,
                BrushRegistration.BrushMenuSupplier.class, MENU_SUPPLIER_BRIDGE, supplier);
        MenuType<?> raw = ReflectiveSupport.newInstance(MenuType.class,
                new Class<?>[]{MENU_SUPPLIER, FeatureFlagSet.class}, sam, requiredFeatures);
        @SuppressWarnings("unchecked") // 擦除桥：同上
        MenuType<M> type = (MenuType<M>) raw;
        return Registry.register(BuiltInRegistries.MENU, id, type);
    }

    /** 刷怪放置规则：{@code SpawnPlacements#register} 为 private static，反射直调。 */
    public static <T extends Mob> void registerSpawnPlacement(EntityType<T> type,
            SpawnPlacementType placement, Heightmap.Types heightmapType,
            SpawnPlacements.SpawnPredicate<T> predicate) {
        ReflectiveSupport.invokeStatic(SpawnPlacements.class, "register",
                new Class<?>[]{EntityType.class, SpawnPlacementType.class, Heightmap.Types.class,
                        SpawnPlacements.SpawnPredicate.class},
                type, placement, heightmapType, predicate);
    }

    // ------------------------------------------------------------------
    // 客户端面（只允许经 BrushRegistry 的缓冲路径调用 —— 执行时机 = 客户端注册 flush：
    // S1/S2 = bootStrap TAIL、S3 = 首个 packs.registered，或 flush 后立即
    // （Minecraft.getInstance()==null 守卫保证映射未构建），E3）
    // ------------------------------------------------------------------

    /** 实体渲染器：裸 {@code Map.put}（无重复检查，javap 核实 —— 同 vanilla）。 */
    public static <T extends Entity> void registerEntityRenderer(EntityType<? extends T> type,
                                                                 EntityRendererProvider<T> provider) {
        ReflectiveSupport.mutateStaticMap(EntityRenderers.class, "PROVIDERS",
                map -> map.put(type, provider));
    }

    /** 方块实体渲染器：裸 {@code Map.put}（无重复检查，javap 核实 —— 同 vanilla）。 */
    public static <T extends BlockEntity, S extends BlockEntityRenderState> void registerBlockEntityRenderer(
            BlockEntityType<? extends T> type, BlockEntityRendererProvider<T, S> provider) {
        ReflectiveSupport.mutateStaticMap(BlockEntityRenderers.class, "PROVIDERS",
                map -> map.put(type, provider));
    }

    /**
     * 菜单屏幕：{@code ScreenConstructor} SAM 由 {@link ReflectiveSupport#instantiateSam} 生成（双路径）；写入 {@code MenuScreens.SCREENS}
     * 时<b>保留 vanilla 的重复检查语义</b>（重复 → ISE），但采用检查-后-put：
     * 冲突抛出时映射零扰动（vanilla 是 put-后-检查，冲突时新值已落入 —— 我们更保守）。
     */
    public static <M extends AbstractContainerMenu, S extends Screen & MenuAccess<M>>
    void registerMenuScreen(MenuType<M> type, BrushRegistration.BrushScreenFactory<M, S> factory) {
        Object sam = ReflectiveSupport.instantiateSam(SCREEN_CONSTRUCTOR,
                BrushRegistration.BrushScreenFactory.class, SCREEN_CONSTRUCTOR_BRIDGE, factory);
        ReflectiveSupport.mutateStaticMap(MenuScreens.class, "SCREENS", map -> {
            if (map.containsKey(type)) {
                throw new IllegalStateException("[brush] a screen is already registered for " + type
                        + " -- vanilla MenuScreens.register performs the same duplicate check"
                        + " (javap-verified, 26.1.2); the existing entry was left untouched");
            }
            map.put(type, sam);
        });
    }
}

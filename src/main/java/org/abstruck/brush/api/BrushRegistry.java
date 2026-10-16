package org.abstruck.brush.api;

import com.mojang.serialization.Codec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnPlacementType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.abstruck.brush.content.ContentLedger;
import org.abstruck.brush.content.ModContext;
import org.abstruck.brush.registry.ClientRegistrationBuffer;
import org.abstruck.brush.registry.DataRegistryEngine;
import org.abstruck.brush.registry.ReflectiveRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 内容注册门面（L2）—— 泛型注册核心 + 物品 / 方块便捷形的唯一入口。
 *
 * <p><b>调用位置约束（唯一合法用法）</b>：只能在
 * {@link BrushEvents#CONTENT_REGISTRATION} 的事件监听器内调用 —— 这是 vanilla 注册表
 * <b>唯一的合法注册窗口</b>（更早触碰注册表类会被 vanilla 守卫抛 {@code "Not bootstrapped"}，
 * 更晚注册会抛 {@code "Registry is already frozen"}；窗口即 vanilla {@code Bootstrap} 流程
 * 内部的一段，外部代码不可达）。
 * <pre>{@code
 * BrushEvents.CONTENT_REGISTRATION.register(event -> {
 *     BrushRegistry.registerItem(Identifier.fromNamespaceAndPath("mymod", "wand"),
 *             new Item.Properties());
 * });
 * }</pre>
 *
 * <p><b>本类相对"mod 直调 vanilla {@code Registry.register}"的增量</b>：
 * <ol>
 *   <li><b>窗口守卫</b>：捕获 vanilla 守卫异常并<b>原样附带 Brush 指引</b>再抛
 *       （不吞、不改语义）—— mod 作者读到的是"应当在注册事件内注册"，
 *       而不是裸 {@code IllegalStateException}；无 mod 上下文的调用被显式拒绝；</li>
 *   <li><b>归账</b>：每次注册落进 {@link ContentLedger}（modid / 类别 / id），
 *       自检报告与退出码位 16 的证据来源；</li>
 *   <li><b>稳定面</b>：mod 只依赖 {@code api} 包 —— vanilla 侧的时序契约由本类承担，
 *       不写进 mod 代码。</li>
 * </ol>
 *
 * <p><b>为什么签名里出现 vanilla 类型</b>（{@code Item.Properties} / {@code Registry<T>} /
 * {@code BlockBehaviour.Properties}）：它们是 vanilla 的<b>公开内容模型</b>，且三运行时同名
 * —— 禁止的是"把 vanilla 内部结构固化成 API"，不是屏蔽内容模型本身。
 * 本类只承接<b>时序与归账</b>，不做类型转换层。
 *
 * <p><b>类加载纪律（实现者注意）</b>：本类引用 MC 类型，只能被"注册窗口内会执行的代码"引用
 * （监听器 lambda 体内的静态调用）——<b>不得</b>在引导期（如 {@code EarlyBoot} 路径上的类）的
 * 方法体或字段初始化器里引用本类，否则会把注册表类拖进引导期的类加载链。
 * 这是 {@code content} 包与 {@code api} 包各自"零 MC"的互补约束。
 *
 * <p>泛型核心（{@link #register}）是能力边界：任意 {@code Registry<T>} 类别零核心改动可注册；
 * 便捷形只是糖（实体 / 粒子 / 声音等类别逐类论证后开）。
 *
 * <p><b>E2 扩展（2026-10-12）：vanilla 无公开注册面的类别</b> —— 实体类型 / 实体属性 /
 * 刷怪放置 / 方块实体类型 / 菜单类型 / 实体与方块实体渲染器 / 菜单屏幕。这些在 26.1.2 里
 * 依赖包私有 SAM、私有构造器或私有 Map 字段（证据见 {@code registry.ReflectiveRegistries}
 * javadoc），实现集中在 {@code registry} 包（反射唯一集中点），本类只做窗口守卫 /
 * 归账 / 缓冲。语义分三组：
 * <ul>
 *   <li><b>内容组</b>（{@code registerEntityType} / {@code registerBlockEntityType} /
 *       {@code registerMenuType} / {@code registerSpawnPlacement}）：与 {@link #register}
 *       同一窗口契约 —— 只在 {@link BrushEvents#CONTENT_REGISTRATION} 监听器内调用
 *       （注册表 freeze 语义：更晚注册被 vanilla 拒绝）；</li>
 *   <li><b>延迟内容组</b>（{@code registerAttributes}）：参数为 {@code AttributeSupplier.Builder}
 *       的 <b>Supplier</b>（需求文档 P0-2 指定形态），经 {@code registry.ClientRegistrationBuffer}
 *       缓冲、在客户端注册 flush 延迟求值（S1/S2 = bootStrap TAIL；S3 = 首个
 *       {@code packs.registered}，见 {@code ClientRegistrationBuffer} "S3 延迟落地"节）
 *       —— 属性 Holder 在注册表 {@code freeze()} 时才绑定，在内容窗口现场求值必然失败
 *       （证据链见方法 javadoc）；</li>
 *   <li><b>客户端组</b>（{@code registerEntityRenderer} / {@code registerBlockEntityRenderer} /
 *       {@code registerMenuScreen}）：调用任意时机（{@code onInitialize} / 内容注册窗口均可），
 *       经同一缓冲在 {@link BrushEvents#CLIENT_REGISTRATION} 窗口统一落地；过晚调用
 *       （Minecraft 实例已存在 <b>且</b> bootStrap 窗口已关闭 ⇒ 渲染器 / 屏幕映射已构建
 *       或已读；S2/S3 下窗口内实例可能已存在，判定见 {@code registry.ClientRegistrationBuffer}
 *       窗口标记节）被显式拒绝并给出指引（不静默失效）。</li>
 * </ul>
 *
 * <p><b>G1 扩展（2026-10-13）：数据包注册表</b> —— 自定义数据包注册表的<b>登记</b>入口是
 * {@link BrushDataRegistries#register(ResourceKey, Codec)}（内容注册窗口或之后任意时刻可调用，
 * 迟登记下次装载生效），<b>读取</b>入口是本类 {@link #dataRegistry(ResourceKey)}。
 * 与上述全部注册组的本质差异：不经 vanilla 注册表、不受内容注册窗口约束 —— 装载由 Brush
 * 自有引擎（{@code registry.DataRegistryEngine}）在集成服务器构造完成 / 数据重载完成时执行，
 * 注册表装在 Brush 自有 RegistryAccess 中（对 vanilla 服务器注册表查询不可见）。
 * 语义与边界见 docs/21。</p>
 */
public final class BrushRegistry {

    private static final Logger LOGGER = LogManager.getLogger(BrushRegistry.class);

    private BrushRegistry() {
    }

    /**
     * 泛型注册核心：向任意 {@code Registry<T>} 注册一个值（窗口守卫 + 归账 + 清晰失败）。
     *
     * <p>调用契约（与便捷形相同）：必须在 {@link BrushEvents#CONTENT_REGISTRATION} 的监听器内调用。
     * 用于 {@code Registry.register(BuiltInRegistries.ITEM, ...)} 这类 vanilla 直调调用的等价替换；
     * 需要"注册 + 配对"的类别（如方块 → BlockItem）用对应便捷形（{@link #registerBlock}）。
     *
     * @param registry 目标注册表（如 {@code BuiltInRegistries.ITEM}）
     * @param id       条目 id（同一 id 进注册表；与值的自述 id 须一致，由调用方保证 ——
     *                 与 vanilla 直调同一契约）
     * @param value    条目值
     * @return 注册后的条目（vanilla {@code Registry.register} 的返回）
     * @throws IllegalStateException 无 mod 上下文，或 vanilla 窗口守卫拒绝
     */
    public static <T> T register(Registry<T> registry, Identifier id, T value) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
        return registerInternal(registry, id,
                () -> Registry.register(registry, ResourceKey.create(registry.key(), id), value));
    }

    /**
     * 注册物品（便捷形）：id 自述 + vanilla 配方（{@code Properties.setId} → 构造）。
     * 与 {@code new Item(properties)} 同一语义，{@code properties.setId} 由本方法完成。
     */
    public static Item registerItem(Identifier id, Item.Properties properties) {
        Objects.requireNonNull(properties, "properties");
        return registerItem(id, props -> new Item(props), properties);
    }

    /**
     * 注册物品（自定义子类便捷形）：{@code factory} 收到已 {@code setId} 的
     * {@code Item.Properties}。
     */
    public static Item registerItem(Identifier id, Function<Item.Properties, Item> factory) {
        return registerItem(id, factory, new Item.Properties());
    }

    private static Item registerItem(Identifier id, Function<Item.Properties, Item> factory,
                                     Item.Properties properties) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(properties, "properties");
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        Item item = Objects.requireNonNull(factory.apply(properties.setId(key)),
                "item factory returned null for " + id);
        return registerInternal(BuiltInRegistries.ITEM, id,
                () -> Registry.register(BuiltInRegistries.ITEM, key, item));
    }

    /**
     * 注册方块（便捷形）：注册 {@code Block} + <b>同名 BlockItem 配对</b>（item id 由 block id 派生，
     * 同 vanilla 配方：{@code blockIdToItemId} =
     * {@code ResourceKey.create(Registries.ITEM, blockKey.identifier())}）。
     *
     * <p>配对属性与 vanilla 一致的部分：{@code useBlockDescriptionPrefix()}（物品名走
     * {@code block.<ns>.<path>} 键）+ <b>{@code Item.BY_BLOCK} 登记</b>（构造后立即
     * {@code registerBlocks}，与 vanilla {@code Items.registerItem} 同序 ——
     * {@code Block.asItem()} / pick-block 链的前置；2026-10-12 修复轮补做）。
     * <b>刻意不设置</b>：
     * <ul>
     *   <li>{@code requiredFeatures} —— vanilla 从 {@code block.requiredFeatures()} 派生，该方法为
     *       {@code protected} 不可跨包访问；Brush 注册发生在引导窗口内（特征标志在此时不可配置），
     *       保持默认空集即可，无需复制该步；</li>
     *   <li>blockstate / 模型 / 纹理资产 —— 由 mod 自带 {@code assets/} 提供，门面不代劳
     *       （example 已随带，见 docs/16）。</li>
     * </ul>
     *
     * @param id         方块 id（同时决定 BlockItem 的 id）
     * @param factory    方块工厂（收到已 {@code setId} 的 {@code BlockBehaviour.Properties}，
     *                   与 vanilla {@code Blocks.register} 同构）
     * @param properties 方块属性（可直接用也常被 factory 消费 —— registry 条目、不改字段）
     * @return 注册后的方块
     * @throws IllegalStateException 无 mod 上下文，或 vanilla 窗口守卫拒绝
     * @see #registerBlockWithoutItem 「有方块无物品」（墙 / 火把 / 火等）的显式入口
     */
    public static Block registerBlock(Identifier id,
                                      Function<BlockBehaviour.Properties, Block> factory,
                                      BlockBehaviour.Properties properties) {
        return registerBlock(id, factory, properties,
                (block, itemProperties) -> new BlockItem(block, itemProperties));
    }

    /**
     * 注册方块（自定义配对物品变体，E2/评审 P2-5）：与 3 参重载同一语义，
     * 但配对物品由 {@code itemFactory} 生产 —— 支持 {@code BlockItem} 子类
     * （3 参重载等价于 {@code (block, props) -> new BlockItem(block, props)}）。
     *
     * <p>{@code itemFactory} 收到的 {@code Item.Properties} 已由本方法完成
     * {@code setId(itemKey)} / {@code useBlockDescriptionPrefix()} /
     * {@code requiredFeatures(FeatureFlagSet.of())}（与 3 参重载同一准备；
     * 工厂不应覆盖这些字段）；返回 null 即失败（可读异常）——
     * <b>不要</b>以"返回 null"表达"无物品"：那是一条失败路径，不是声明。
     * 「有方块无物品」（墙 / 火把 / 楼梯 / 火 / 传送门）用
     * {@link #registerBlockWithoutItem} 显式声明（评审 P2-5）。
     * 返回 {@code BlockItem}（含子类）时自动补 {@code Item.BY_BLOCK} 登记（同 vanilla
     * {@code Items.registerItem} 的 {@code instanceof BlockItem} 判定）；返回其他
     * {@code Item} 形态则无该映射（与 vanilla 语义一致）。
     *
     * @param id          方块 id（同时决定 BlockItem 的 id）
     * @param factory     方块工厂（同 3 参重载）
     * @param properties  方块属性（同 3 参重载）
     * @param itemFactory 配对物品工厂：{@code (已注册方块, 已准备的物品属性) -> 物品}
     * @return 注册后的方块
     */
    public static Block registerBlock(Identifier id,
                                      Function<BlockBehaviour.Properties, Block> factory,
                                      BlockBehaviour.Properties properties,
                                      BiFunction<Block, Item.Properties, Item> itemFactory) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(itemFactory, "itemFactory");
        Block registered = registerBlockOnly(id, factory, properties);

        // 同名 BlockItem 配对（配方 = vanilla Items.registerBlock，见 3 参重载 javadoc）
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        Identifier itemId = itemKey.identifier();
        Item.Properties itemProperties = new Item.Properties()
                .setId(itemKey)
                .useBlockDescriptionPrefix()
                .requiredFeatures(FeatureFlagSet.of());
        Item item = Objects.requireNonNull(itemFactory.apply(registered, itemProperties),
                "item factory returned null for " + itemId);
        // 方块→物品表（Item.BY_BLOCK）登记：vanilla 的唯一填充点在 Items.registerItem
        // （构造后、注册前对 BlockItem 调 registerBlocks —— 构造器本身不填，javap 核实）。
        // 漏做此步的实机症状：Block.asItem() 回退 Items.AIR（并缓存），
        // pick-block / new ItemStack(block) 全断（服务端 getCloneItemStack 得空栈即 return）。
        if (item instanceof BlockItem blockItem) {
            blockItem.registerBlocks(Item.BY_BLOCK, item);
        }
        registerInternal(BuiltInRegistries.ITEM, itemId,
                () -> Registry.register(BuiltInRegistries.ITEM, itemKey, item));
        return registered;
    }

    /**
     * 注册方块，<b>不配对任何物品</b>（评审 P2-5 整改："有方块无物品"的显式表达）。
     *
     * <p>墙 / 火把 / 楼梯 / 火 / 传送门这类 vanilla 本就"只有方块、没有对应物品"的内容
     * 用本方法表达 —— <b>不用 null 当哨兵</b>：4 参 {@link #registerBlock} 重载的
     * {@code itemFactory} 返回 null 仍是失败（既有语义不变），"无物品"是这里的一个
     * 具名入口，而不是某个参数的 magic value。</p>
     *
     * <p>与 3 参配对形的差异只有配对物品一步：不构造物品、不写 {@code Item.BY_BLOCK}
     * （{@code block.asItem()} 回退 {@code Items.AIR} —— 与 vanilla 无物品方块
     * （{@code wall_torch} / {@code fire} 等）同一行为）、不触碰 ITEM 注册表。
     * 窗口守卫 / 归账 / {@code finalizeBlockStates}（state 网络 id 与渲染缓存补做 ——
     * 漏做即 2026-10-12 实机 crash：放置方块的玩家被踢 + 遮挡查询 NPE，
     * 见 {@link #finalizeBlockStates} javadoc）与配对形<b>完全一致</b>；也因此
     * <b>不要</b>用通用形 {@code register(BuiltInRegistries.BLOCK, id, block)} 表达
     * 无物品方块 —— 那会漏掉 {@code finalizeBlockStates}（README §4.5 已同步更正）。</p>
     *
     * <p>调用契约同 3 参重载：必须在 {@link BrushEvents#CONTENT_REGISTRATION} 的监听器内调用。
     * 归账只记 {@code block} 类别一条（无 item 条目 —— 自检报告据此可见"有方块无物品"
     * 与"漏配物品"的区别：前者是本方法的登记，后者根本到不了归账）。</p>
     *
     * @param id         方块 id
     * @param factory    方块工厂（同 3 参重载）
     * @param properties 方块属性（同 3 参重载）
     * @return 注册后的方块
     * @throws IllegalStateException 无 mod 上下文，或 vanilla 窗口守卫拒绝
     */
    public static Block registerBlockWithoutItem(Identifier id,
                                                 Function<BlockBehaviour.Properties, Block> factory,
                                                 BlockBehaviour.Properties properties) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(properties, "properties");
        return registerBlockOnly(id, factory, properties);
    }

    /**
     * 方块 state 的后置初始化（E2 修复轮，2026-10-12 实机 crash 修复）。
     *
     * <p><b>为什么需要</b>：26.1.2 不在 {@code Block} 构造时分配 state 的网络 id 与渲染缓存，
     * 而是在 {@code Blocks} 类初始化末尾对<b>当时已注册的全部方块</b>集中完成 ——
     * 字节码核实（{@code 26.1.2.jar}，{@code Blocks.<clinit>} 尾部，指令 offset 40123–40198）：
     * <pre>{@code
     * for (Block b : BuiltInRegistries.BLOCK)
     *     for (BlockState s : b.getStateDefinition().getPossibleStates()) {
     *         Block.BLOCK_STATE_REGISTRY.add(s);   // 网络 id（getIdOrThrow 的映射）
     *         s.initCache();                       // 渲染/碰撞缓存（含 occlusionShapesByFace）
     *     }
     * }</pre>
     * 我们的注册发生在 {@code BuiltInRegistries.bootStrap} HEAD 窗口（措辞修正 2026-10-13；
     * {@code Blocks} 类初始化早于该窗口），错过集中点，必须对本次注册的方块补做同一步。
     *
     * <p><b>不补做的两个实机后果</b>（2026-10-12 22:18 实机坐实，两个症状同一根因）：
     * <ol>
     *   <li>服务器发 {@code ClientboundBlockUpdatePacket} 编码失败 ——
     *       {@code Can't find id for 'Block{example:ruby_block}' in map ... IdMapper}，
     *       放置方块的玩家被踢（loopback 连接亦触发）；</li>
     *   <li>邻居面遮挡查询 NPE —— {@code occlusionShapesByFace} 为 null
     *       （{@code Tesselating block in world} crash）。</li>
     * </ol>
     *
     * <p>顺序与 vanilla 一致（同一循环体内先 {@code add} 后 {@code initCache}）；
     * 只对本次注册的方块执行（vanilla 已就绪的不重复触达）。注册路径每个方块至多成功一次
     * （重复 id 在 {@code registerInternal} 即被拒绝），故无需 {@code IdMapper.add} 的幂等处理。
     * 同类模式（{@code Fluids} 对 {@code Fluid.FLUID_STATE_REGISTRY} 的集中填充）当前
     * 未涉及 —— 未来若提供流体注册 API 须照此补做。
     */
    private static void finalizeBlockStates(Block block) {
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            Block.BLOCK_STATE_REGISTRY.add(state);
            state.initCache();
        }
    }

    /**
     * 方块本体注册的单一实现点（3/4 参配对形与 {@link #registerBlockWithoutItem} 共用）：
     * {@code setId} → 工厂构造 → 窗口内注册 → {@code finalizeBlockStates}。
     * 入参校验（requireNonNull 次序与消息）由各公开重载自行完成，
     * 保证既有重载的失败行为逐字节不变。
     */
    private static Block registerBlockOnly(Identifier id,
                                           Function<BlockBehaviour.Properties, Block> factory,
                                           BlockBehaviour.Properties properties) {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        Block block = Objects.requireNonNull(factory.apply(properties.setId(blockKey)),
                "block factory returned null for " + id);
        Block registered = registerInternal(BuiltInRegistries.BLOCK, id,
                () -> Registry.register(BuiltInRegistries.BLOCK, blockKey, block));
        finalizeBlockStates(registered);
        return registered;
    }

    // ------------------------------------------------------------------
    // E2：vanilla 无公开注册面的类别（内容组 —— CONTENT_REGISTRATION 窗口内调用）
    // ------------------------------------------------------------------

    /**
     * 注册实体类型（内容组）：{@code builder.build(key)} 构造 + 注册
     * （vanilla 的 builder.build 不自注册 —— javap 核实，见 {@code ReflectiveRegistries}）。
     * 实体的属性 / 渲染器 / 刷怪放置分别用 {@link #registerAttributes} /
     * {@link #registerEntityRenderer} / {@link #registerSpawnPlacement} 配套注册。
     *
     * @param id      实体类型 id
     * @param builder vanilla {@code EntityType.Builder}（形如
     *                 {@code EntityType.Builder.of(MyEntity::new, MobCategory.CREATURE)}）
     * @return 注册后的实体类型（可继续用于属性 / 渲染器 / 刷怪注册）
     */
    public static <T extends Entity> EntityType<T> registerEntityType(Identifier id,
                                                                      EntityType.Builder<T> builder) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(builder, "builder");
        return registerInternal(BuiltInRegistries.ENTITY_TYPE, id,
                () -> ReflectiveRegistries.registerEntityType(id, builder));
    }

    /**
     * 注册实体属性（延迟内容组；登记类别 {@code entity_attribute}）——参数是
     * <b>{@code AttributeSupplier.Builder} 的 Supplier</b>（需求文档 P0-2 指定形态；
     * 典型写法 {@code () -> Cow.createAttributes()}，可继续链式定制：
     * {@code () -> Cow.createAttributes().add(Attributes.ARMOR, 4.0)}）。
     * 本方法<b>不在调用现场求值</b>：工厂经 {@code registry.ClientRegistrationBuffer}
     * 缓冲，在客户端注册 flush（{@link BrushEvents#CLIENT_REGISTRATION} 窗口的
     * 落地点：S1/S2 = bootStrap TAIL；S3 = 首个 {@code packs.registered}）统一执行
     * —— 提交时机与客户端组相同（任意内容窗口；过晚拒绝）。
     *
     * <p><b>为什么必须延迟求值（26.1.2 javap 实证 + T1 首跑实测）</b>：构建
     * {@code AttributeSupplier.Builder}（{@code Cow.createAttributes()} →
     * {@code AttributeSupplier$Builder.add} → {@code AttributeInstance.<init>}）会
     * <b>立即解析</b>属性 Holder 的值（{@code Holder$Reference.value()}）；而 vanilla 的
     * Holder 值在注册表 <b>{@code freeze()} 时</b>才绑定
     * （{@code MappedRegistry.lambda$freeze$0 → Holder$Reference.bindValue}；
     * {@code BuiltInRegistries.bootStrap() = createContents(); freeze(); validate(REGISTRY);}）。
     * 内容注册窗口（bootStrap HEAD）早于 freeze —— 现场求值必抛
     * {@code IllegalStateException: Trying to access unbound value '.../minecraft:max_health'}
     * （T1 首跑原样复现，日志在案）。vanilla 自身同样在 freeze 之后构建这些 supplier
     * （{@code DefaultAttributes.<clinit>} 由 {@code Bootstrap.validate()} 触发）。
     * 延迟到客户端注册 flush 的落地点求值即天然满足 —— <b>S3 附加约束</b>：宿主自身
     * 内容注册（{@code ClientModLoader.begin}）晚于 bootStrap TAIL，TAIL 求值仍会 NPE
     * 于未绑定的 neo 属性 holder（T3 实测），故 S3 落地推迟到首个
     * {@code packs.registered}（见 {@code ClientRegistrationBuffer} "S3 延迟落地"节）。</p>
     *
     * <p>写入机制：落地求值时 {@code builder.get()} → {@code build()} → 经
     * {@code ReflectiveRegistries} 写入 {@code DefaultAttributes.SUPPLIERS}
     * （首次字段读取触发 {@code DefaultAttributes.<clinit>} —— 彼时已可安全构建；
     * clinit 每类至多一次，写入不会被其覆盖）。
     * 类型须先经 {@link #registerEntityType} 注册（否则显式拒绝）。</p>
     *
     * @param type    实体类型
     * @param builder {@code AttributeSupplier.Builder} 的延迟工厂（在客户端注册 flush 被求值恰一次）
     */
    public static <T extends LivingEntity> void registerAttributes(EntityType<T> type,
            Supplier<AttributeSupplier.Builder> builder) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(builder, "builder");
        registerDeferred(identifierOf(type), "entity_attribute",
                () -> ReflectiveRegistries.registerAttributes(type, builder.get()));
    }

    /**
     * 注册刷怪放置规则（内容组；登记类别 {@code spawn_placement}）：
     * {@code SpawnPlacements.register} 为 private static（javap 核实），经反射直调；
     * 公开类型（{@code SpawnPlacementType} / {@code Heightmap.Types} /
     * {@code SpawnPredicate}）直接使用 vanilla 形。
     *
     * @param type          实体类型（须为 {@code Mob} 子类 —— vanilla 约束）
     * @param placement     放置类型（如 {@code SpawnPlacementTypes.ON_GROUND}）
     * @param heightmapType 高度图类型
     * @param predicate     放置谓词（可为 {@code SpawnPlacements.checkSpawnRules(...)} 之类）
     */
    public static <T extends Mob> void registerSpawnPlacement(EntityType<T> type,
            SpawnPlacementType placement, Heightmap.Types heightmapType,
            SpawnPlacements.SpawnPredicate<T> predicate) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(heightmapType, "heightmapType");
        Objects.requireNonNull(predicate, "predicate");
        registerNonRegistry(identifierOf(type), "spawn_placement",
                () -> ReflectiveRegistries.registerSpawnPlacement(type, placement, heightmapType, predicate));
    }

    /**
     * 注册方块实体类型（内容组）：包私有构造器直构 + 注册（26.1.2 无 Builder）。
     *
     * @param id       方块实体类型 id
     * @param supplier 方块实体工厂（公开形 —— 见 {@link BrushRegistration.BrushBlockEntitySupplier}）
     * @param blocks   该方块实体依附的方块（vanilla 语义：至少一个）
     * @return 注册后的方块实体类型（可继续用于 {@link #registerBlockEntityRenderer}）
     */
    public static <T extends BlockEntity> BlockEntityType<T> registerBlockEntityType(Identifier id,
            BrushRegistration.BrushBlockEntitySupplier<T> supplier, Block... blocks) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(blocks, "blocks");
        if (blocks.length == 0) {
            throw new IllegalArgumentException("[brush] registerBlockEntityType for " + id
                    + ": at least one block is required (vanilla BlockEntityType contract)");
        }
        return registerInternal(BuiltInRegistries.BLOCK_ENTITY_TYPE, id,
                () -> ReflectiveRegistries.registerBlockEntityType(id, supplier, blocks));
    }

    /**
     * 注册菜单类型（内容组）：包私有构造器直构 + 注册；{@code requiredFeatures} 为空集
     * （{@code FeatureFlagSet.of()} —— 无特征要求的常规情形）。需要特征门控时用 3 参重载。
     */
    public static <M extends AbstractContainerMenu> MenuType<M> registerMenuType(Identifier id,
            BrushRegistration.BrushMenuSupplier<M> supplier) {
        return registerMenuType(id, supplier, FeatureFlagSet.of());
    }

    /**
     * 注册菜单类型（显式 {@code requiredFeatures} 变体）：与 vanilla 构造器第二参同义
     * （由 {@code MenuType.requiredFeatures()} 参与菜单可用性判定）。
     *
     * @param id               菜单类型 id
     * @param supplier         菜单工厂（公开形 —— 见 {@link BrushRegistration.BrushMenuSupplier}）
     * @param requiredFeatures 所需特征集（{@code FeatureFlagSet.of()} = 无要求）
     * @return 注册后的菜单类型（可继续用于 {@link #registerMenuScreen}）
     */
    public static <M extends AbstractContainerMenu> MenuType<M> registerMenuType(Identifier id,
            BrushRegistration.BrushMenuSupplier<M> supplier, FeatureFlagSet requiredFeatures) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(requiredFeatures, "requiredFeatures");
        return registerInternal(BuiltInRegistries.MENU, id,
                () -> ReflectiveRegistries.registerMenuType(id, supplier, requiredFeatures));
    }

    // ------------------------------------------------------------------
    // E2：客户端组（任意时机调用；内部缓冲，CLIENT_REGISTRATION 窗口统一落地）
    // ------------------------------------------------------------------

    /**
     * 注册实体渲染器（客户端组）：{@code EntityRenderers} 的私有 Map 写入。
     * 调用任意时机（{@code onInitialize} / 内容注册窗口均可）；过晚（Minecraft 实例已存在
     * ⇒ 渲染器映射已构建）显式拒绝。
     *
     * @param type     实体类型（通常为 {@link #registerEntityType} 的返回值）
     * @param provider 渲染器工厂（vanilla 公开接口，直接使用）
     */
    public static <T extends Entity> void registerEntityRenderer(EntityType<? extends T> type,
                                                                 EntityRendererProvider<T> provider) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(provider, "provider");
        registerDeferred(identifierOf(type), "entity_renderer",
                () -> ReflectiveRegistries.registerEntityRenderer(type, provider));
    }

    /**
     * 注册方块实体渲染器（客户端组）：{@code BlockEntityRenderers} 的私有 Map 写入。
     * 调用时机与拒绝语义同 {@link #registerEntityRenderer}。
     *
     * @param type     方块实体类型（通常为 {@link #registerBlockEntityType} 的返回值）
     * @param provider 渲染器工厂（vanilla 公开接口，直接使用；26.1.2 为
     *                 {@code BlockEntityRendererProvider<T, S>} 双参形）
     */
    public static <T extends BlockEntity, S extends BlockEntityRenderState> void registerBlockEntityRenderer(
            BlockEntityType<? extends T> type, BlockEntityRendererProvider<T, S> provider) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(provider, "provider");
        registerDeferred(identifierOf(type), "block_entity_renderer",
                () -> ReflectiveRegistries.registerBlockEntityRenderer(type, provider));
    }

    /**
     * 注册菜单屏幕（客户端组）：{@code MenuScreens} 的私有 Map 写入（含 vanilla 同款
     * 重复检查）。调用时机与拒绝语义同 {@link #registerEntityRenderer}。
     *
     * @param type    菜单类型（通常为 {@link #registerMenuType} 的返回值）
     * @param factory 屏幕工厂（公开形 —— 见 {@link BrushRegistration.BrushScreenFactory}；
     *                屏幕类型须实现 {@code MenuAccess}，{@code AbstractContainerScreen}
     *                子类天然满足）
     */
    public static <M extends AbstractContainerMenu, S extends Screen & MenuAccess<M>> void registerMenuScreen(
            MenuType<M> type, BrushRegistration.BrushScreenFactory<M, S> factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        registerDeferred(identifierOf(type), "menu_screen",
                () -> ReflectiveRegistries.registerMenuScreen(type, factory));
    }

    // ------------------------------------------------------------------
    // G1：数据包注册表读取（docs/21；登记入口在 api.BrushDataRegistries）
    // ------------------------------------------------------------------

    /**
     * 读取 Brush 数据包注册表（G1）—— 最近一次成功装载的只读视图。
     *
     * <p><b>登记在别处</b>：注册表的<b>登记</b>入口是
     * {@link BrushDataRegistries#register(ResourceKey, Codec)}（须在 mod 上下文中调用）；
     * 本方法只做读取 —— 无窗口 / 无 mod 上下文约束，任意时机可调用：装载前返回空
     * （集成服务器构造完成 / 数据重载完成后非空）。装载时机、按包序合并语义与失败语义
     * 见 {@code registry.DataRegistryEngine} javadoc 与 docs/21。</p>
     *
     * <p><b>边界</b>：注册表位于 Brush 自有的 RegistryAccess 中，<b>对 vanilla 服务器注册表
     * 查询不可见</b>（26.1.2 已无服务器侧数据包注册表路径 —— docs/21 决议记录）；
     * 远端多人服务器下本方法恒为空（客户端 JVM 无集成服务器 —— docs/21 已知边界）。</p>
     *
     * @param key 注册表键（须为 {@link BrushDataRegistries#register(ResourceKey, Codec)}
     *            登记时的<b>同一实例</b> —— {@code ResourceKey} 为身份相等，不同实例即使
     *            标识符相同也不会命中，见 {@code DataRegistryEngine} javadoc "键身份"节）
     * @return 已装载的注册表视图；未装载 / 未知键时为空
     */
    public static <T> Optional<Registry<T>> dataRegistry(ResourceKey<? extends Registry<T>> key) {
        Objects.requireNonNull(key, "key");
        return DataRegistryEngine.lookup(key);
    }

    // ------------------------------------------------------------------
    // 内部：窗口守卫 + 归账 + 成功自证
    // ------------------------------------------------------------------

    /**
     * 共同路径：上下文检查 → 执行 vanilla 注册 → 归账 + 相位安全回查
     * （{@code getKey}，不解析 Holder 值 —— pre-freeze 的绑定语义见方法体内注释）。
     *
     * <p>失败形态（都在发生点记归账拒绝并抛可读异常，不吞）：
     * <ul>
     *   <li>无 mod 上下文：调用方不是经 {@code ModBootstrap} 实例化的 mod（含监听器被
     *       {@code Event.register} 之外的路径直挂、被其他 mod 复用等情况）—— 属实现缺陷，直接拒绝；</li>
     *   <li>vanilla 窗口守卫异常：包装为带指引的 {@link IllegalStateException}（保留 cause 原样）。</li>
     * </ul>
     */
    private static <T> T registerInternal(Registry<? super T> registry, Identifier id, Supplier<T> action) {
        String modId = requireModContext(id, "content registration");

        // 守卫：CREATIVE_MODE_TAB 拒收 —— Bootstrap#bootStrap() 在生产路径上无条件调用
        // CreativeModeTabs.validate()，14 个 vanilla 标签页 (row,column) 槽位已占满，
        // 新条目的重复槽位会直接中止启动。
        if ((Registry<?>) registry == BuiltInRegistries.CREATIVE_MODE_TAB) {
            String reason = "custom creative tabs are not supported yet (C2b deferred): all 14 vanilla"
                    + " (row,column) slots are occupied and Bootstrap.bootStrap() calls"
                    + " CreativeModeTabs.validate() unconditionally (javap-proven, offset 78), so a new"
                    + " tab would abort startup -- append items to an existing tab via"
                    + " BrushCreativeTabs.entriesOf(...) instead (C2a; see docs/14 section 9)";
            ContentLedger.recordRejection(modId, id.toString(), reason);
            throw new IllegalStateException("[brush] content registration for " + id + " by mod '"
                    + modId + "' rejected: " + reason);
        }

        String type = typeName(registry);
        T registered;
        try {
            registered = action.get();
        } catch (RuntimeException | Error e) {
            String reason = "window constraint violated: " + e
                    + " -- register inside the BrushEvents.CONTENT_REGISTRATION"
                    + " listener (the only legal window; see docs/15 section 2.1)";
            ContentLedger.recordRejection(modId, id.toString(), reason);
            throw new IllegalStateException("[brush] content registration for " + id + " by mod '"
                    + modId + "' failed: " + e.getMessage() + " -- register inside the"
                    + " BrushEvents.CONTENT_REGISTRATION listener (the only legal window;"
                    + " see docs/15 section 2.1)", e);
        }

        ContentLedger.record(modId, type, id.toString());

        // 注册后一致性回查（相位安全）：不能走 getValue —— pre-freeze 时非 intrusive 注册表
        // （如 MENU）的 Holder 值尚未绑定（值只在 MappedRegistry.freeze() 的
        // byValue.forEach(ref -> ref.bindValue(value)) 里绑定），getValue 会抛
        // 'Trying to access unbound value'（T1 二跑实测 + RegProbe 隔离复现，日志在案；
        // intrusive 注册表如 ITEM/BLOCK 其值的构造器已预建 Holder，故现场可读）。
        // 改走 MappedRegistry.getKey(T)：byValue.get(instance) -> ref.key().identifier()，
        // 不解析值，注册后全相位可用（DefaultedMappedRegistry 查不到时回退 defaultKey，
        // null / defaultKey != id 同样被本检查捕获）。
        Identifier backKey = registry.getKey(registered);
        if (!id.equals(backKey)) {
            LOGGER.warn("[brush] content: " + type + " lookup for " + id + " resolves back to "
                    + backKey + " instead of " + id + " -- registry rejected the entry?");
        }
        return registered;
    }

    /**
     * 非注册表类别的登记路径（E2：刷怪放置）：同一 mod 上下文守卫 + 归账，
     * 但不触碰 {@code Registry}（无窗口冻结语义，写入的是 vanilla 的私有映射）。
     * 失败在发生点记拒绝并包为可读异常（不吞）。
     */
    private static void registerNonRegistry(Identifier id, String category, Runnable action) {
        String modId = requireModContext(id, category + " registration");
        try {
            action.run();
        } catch (RuntimeException | Error e) {
            String reason = "failed: " + e + " -- " + category + " registration for " + id
                    + " must run inside a BrushEvents.CONTENT_REGISTRATION listener"
                    + " (see docs/15 section 2.1)";
            ContentLedger.recordRejection(modId, id.toString(), reason);
            throw new IllegalStateException("[brush] " + category + " registration for " + id
                    + " by mod '" + modId + "' failed: " + e.getMessage()
                    + " -- register inside the BrushEvents.CONTENT_REGISTRATION listener", e);
        }
        ContentLedger.record(modId, category, id.toString());
    }

    /**
     * 延迟注册共同路径（E2 属性 / 客户端组）：mod 上下文守卫 → 过晚守卫（Minecraft 实例
     * 已存在 ⇒ 消费面已构建或已读，写入必然静默失效 → 显式拒绝）→ 归账 → 入缓冲
     * （flush 后到场则立即执行 —— 缓冲语义见 {@code ClientRegistrationBuffer}）。
     *
     * <p>时机论证：本守卫拒绝 ⟺ {@code Minecraft.getInstance() != null} <b>且</b>
     * bootStrap 窗口已关闭（{@code ClientRegistrationBuffer.windowActive()} == false）
     * ⟺ 已越过合法窗口且实例已存在 ⟺ vanilla 映射已构建或已读（26.1.2：
     * {@code createEntityRenderers} 在 {@code Minecraft.<init>} 内调用，字节码偏移核实）。
     * <b>只看实例是不够的</b>：26.1.2 三运行时下 {@code BuiltInRegistries.bootStrap()} 的
     * 调用点不同 —— S1 在构造前（Main.main → Bootstrap），S2 在 {@code Minecraft.<init>}
     * 中途（fabric-registry-sync-v0 的 afterModInit），此时窗口内实例已存在但映射尚未
     * 构建，必须放行（T2 首跑即因仅看实例而误拒失败，实测栈
     * {@code Minecraft.<init>(Minecraft.java:477) → BuiltInRegistries.bootStrap()}；
     * 修正记录见 {@code ClientRegistrationBuffer} 窗口标记节）。窗口关闭后实例仍存在的
     * 调用一律拒绝（对 mod 可达的调用点而言该判定三运行时一致）。</p>
     *
     * <p>执行期失败（落地求值 / 写入抛异常）经 {@link #runDeferred} 包装为带
     * mod / id 上下文的可读 ISE 后上抛 —— 执行点（客户端注册 flush：S1/S2 = bootStrap
     * TAIL；S3 = 首个 {@code packs.registered}）远离调用点，不包装
     * 会丢失归属；不吞、不降级（与 {@code registerNonRegistry} 同一风格）。</p>
     */
    private static void registerDeferred(Identifier id, String category, Runnable application) {
        String modId = requireModContext(id, category + " registration");
        if (Minecraft.getInstance() != null && !ClientRegistrationBuffer.windowActive()) {
            String reason = "registration window is closed: the Minecraft instance already exists and"
                    + " the bootStrap window (CONTENT_REGISTRATION .. CLIENT_REGISTRATION) is over,"
                    + " so vanilla has built (and possibly consumed) its registration maps -- submit the"
                    + " registration before client startup (onInitialize or a CONTENT_REGISTRATION /"
                    + " BrushEvents.CLIENT_REGISTRATION listener; deferred registrations are buffered"
                    + " until the client registration flush: the bootStrap TAIL on S1/S2, the first"
                    + " packs.registered on S3) instead of after it";
            ContentLedger.recordRejection(modId, id.toString(), reason);
            throw new IllegalStateException("[brush] " + category + " registration for " + id
                    + " by mod '" + modId + "' rejected: " + reason);
        }
        ContentLedger.record(modId, category, id.toString());
        ClientRegistrationBuffer.enqueue(() -> runDeferred(modId, id, category, application));
    }

    /** 延迟任务执行包装：失败带 mod / id / 窗口上下文（见 {@link #registerDeferred} javadoc）。 */
    private static void runDeferred(String modId, Identifier id, String category,
                                    Runnable application) {
        try {
            application.run();
        } catch (RuntimeException | Error e) {
            String reason = "deferred execution at the client registration flush failed: " + e;
            ContentLedger.recordRejection(modId, id.toString(), reason);
            throw new IllegalStateException("[brush] " + category + " registration for " + id
                    + " by mod '" + modId + "' failed during the client registration flush: "
                    + e.getMessage(), e);
        }
    }

    /**
     * 共同守卫：必须处于 mod 上下文（入口构造 / 事件监听器执行现场），否则拒绝。
     * 包私有（原 private，G1 放宽）：同包的 {@code api.BrushDataRegistries} 复用本守卫
     * （拒绝路径 = 同一 {@link ContentLedger#recordRejection} 归账口径），不复制第二份。
     */
    static String requireModContext(Identifier id, String what) {
        String modId = ModContext.current();
        if (modId == null) {
            String reason = "no mod context -- " + what + " must run inside a mod's"
                    + " listener/task (mod attribution requires the mod bootstrap context;"
                    + " see docs/15 section 3)";
            ContentLedger.recordRejection("(unknown)", id.toString(), reason);
            throw new IllegalStateException("[brush] " + what + " for " + id + " rejected:"
                    + " no mod context active on this thread (register inside a"
                    + " BrushEvents listener registered by a mod entry class)");
        }
        return modId;
    }

    /** 注册表键查找（归账 / 归属 id 的来源）；未注册的值显式拒绝（可读指引）。 */
    private static Identifier identifierOf(EntityType<?> type) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id == null) {
            throw unregistered("entity type", type);
        }
        return id;
    }

    private static Identifier identifierOf(BlockEntityType<?> type) {
        Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        if (id == null) {
            throw unregistered("block entity type", type);
        }
        return id;
    }

    private static Identifier identifierOf(MenuType<?> type) {
        Identifier id = BuiltInRegistries.MENU.getKey(type);
        if (id == null) {
            throw unregistered("menu type", type);
        }
        return id;
    }

    private static IllegalStateException unregistered(String what, Object value) {
        return new IllegalStateException("[brush] " + what + " " + value + " is not registered --"
                + " register it via BrushRegistry first (the registry key is the id used for"
                + " ledger attribution and for derived registrations)");
    }

    /**
     * 类别短名 = 注册表标识的路径段（{@code minecraft:item} → {@code item}；
     * {@code minecraft:block} → {@code block}；泛型类别的其他注册表同理），供归账分类与报告行。
     */
    private static String typeName(Registry<?> registry) {
        String path = registry.key().identifier().getPath();
        return path.isBlank() ? "entry" : path;
    }
}

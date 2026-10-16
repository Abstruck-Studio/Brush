package org.abstruck.brush.transform;

/**
 * L1 钩子事件 id 常量（CLAUDE.md §6：L1 原始钩子层）。
 *
 * <p>id 是 L1 内部标识，可随版本调整；对外的稳定契约是 L2 语义事件
 * （{@code org.abstruck.brush.api.BrushEvents}）。
 *
 * <p><b>类型化（评审 P2-1 整改）</b>：本类常量自整改起为 {@link HookEvent} 强类型 key
 * （评审建议形态 {@code record HookEvent(String id)}），不再裸 String —— 拼错在编译期
 * 即失败，与 L2 的全类型化对称。<b>运行时 id 的字符串值一字未改</b>：台账
 * （{@code InjectionLedger} / docs/footprint.md）、日志与自检报告仍按原字符串核对
 * （值锁定断言见 {@code HookEventTest}；打印侧由 {@code HookEvent#toString() = id} 保证）。
 * 全部 17 个 id 只在本类定义（常量收敛、单一真相源）。</p>
 *
 * <p>注入类事件（{@link #BOOTSTRAP_EARLY} / {@link #REGISTRIES_BOOTSTRAP} / {@link #CLIENT_REGISTRATION}
 * / {@link #PACKS_REGISTERED} / {@link #CLIENT_RUN} / {@link #CLIENT_TICK} / {@link #SCREEN_INIT}
 * / {@link #SCREEN_RESIZE} / {@link #CREATIVE_TAB_OUTPUT} / {@link #HUD_EXTRACT}
 * / {@link #INPUT_KEY_PRESS} / {@link #INPUT_MOUSE_BUTTON} / {@link #INPUT_MOUSE_SCROLL}
 * / {@link #NETWORK_PAYLOAD} / {@link #DATA_LOADED} / {@link #DATA_RELOADED}）
 * 每个 id 恰有一个注入点承载它，每注入点 1 条织入指令（§10.1；{@link #BOOTSTRAP_EARLY}
 * 的织入指令为 {@code EarlyBoot.onBootstrap()} —— 引导与派发一体，见该常量 javadoc）。
 * {@code BuiltInRegistries#bootStrap} 是本项目唯一承载两个注入点的方法
 * （HEAD = {@link #REGISTRIES_BOOTSTRAP} / TAIL = {@link #CLIENT_REGISTRATION}，各 1 条指令）——
 * 同方法第 2 注入点经用户批准（2026-10-12，E3），台账登记（16 号点）与理由见
 * {@link #CLIENT_REGISTRATION} javadoc 与 docs/footprint.md 变更记录；
 * {@link #CLIENT_STOPPING} 是首个<b>非注入</b>事件源（JVM shutdown hook，见该常量 javadoc）。
 */
public final class HookEvents {

    /**
     * 最早 bootstrap 锚点 —— vanilla 最早的可注入执行点（"起点化"引导事件）。
     *
     * <p>注入位置：{@code net.minecraft.server.Bootstrap#bootStrap()V} 的 HEAD（26.1.2 官方名）。
     * HEAD 先于同一方法内的 {@code BuiltInRegistries.bootStrap()}（内容注册窗口），因此本事件
     * 驱动的引导必然早于内容注册 —— 三种运行时的所有情形下无条件成立（顺序保证见
     * {@code EarlyBoot} / {@code BootstrapMixin} javadoc）。三运行时下本锚点均先于宿主入口点
     * 触发。</p>
     *
     * <p>本事件每进程触发 1 次：once 标志在 {@code EarlyBoot} 内（我们的 handler 先于 vanilla
     * 的 bootstrapped 守卫执行，重复调用 {@code bootStrap()} 不重复派发）。handler 在引导
     * 完成后派发本事件（监听器 —— 含模组在引导期注册的 —— 此时均已就位）。非热路径。</p>
     *
     * <p>L2 语义层：暂无 —— 本事件当前是内部引导锚点（被 {@code EarlyBoot} 消费）；
     * 对 mod 的语义化暴露待有真实需求时按 §6 分层补齐。</p>
     */
    public static final HookEvent BOOTSTRAP_EARLY = new HookEvent("bootstrap.early");

    /**
     * 内置注册表 bootstrap 相位 —— 游戏内容注册（物品 / 方块 / …）的<b>唯一合法窗口</b>。
     *
     * <p>注入位置：{@code net.minecraft.core.registries.BuiltInRegistries#bootStrap()V} 的 HEAD
     * （26.1.2 官方名；方法体 = createContents → freeze → validate）。
     * HEAD 处 {@code Bootstrap} 的 bootstrapped 标志已置位（状态守卫放行）、注册表类已初始化、
     * vanilla 内容类已在流程前段完成自注册（早于本窗口；插入序见 {@code BuiltInRegistriesMixin}
     * javadoc 的 javap 复核）、未冻结。更早的时机（启动管线 / 宿主入口点）会被状态守卫抛
     * {@code "Not bootstrapped"} 拦截，更晚则被 {@code freeze()} 拦截（论证见 docs/15 §9）。
     * 非热路径：每进程 1 次。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.CONTENT_REGISTRATION}（mod 在该事件内执行注册）。</p>
     */
    public static final HookEvent REGISTRIES_BOOTSTRAP = new HookEvent("registries.bootstrap");

    /**
     * 客户端注册窗口（E3）—— 客户端侧注册（实体 / 方块实体渲染器、菜单屏幕）的统一落地时机。
     *
     * <p>注入位置：{@code net.minecraft.core.registries.BuiltInRegistries#bootStrap()V} 的
     * <b>TAIL</b>。方法体 = {@code createContents → freeze → validate(REGISTRY)}
     * （javap 实证，见 {@link #REGISTRIES_BOOTSTRAP}），TAIL = 三者全部完成之后。
     * 本点是本项目唯一与 {@link #REGISTRIES_BOOTSTRAP} <b>同方法</b>的第二个注入点
     * （HEAD / TAIL）—— 经用户批准（2026-10-12，E3）：每点仍 1 条织入指令、可链式
     * {@code @Inject}、非热路径（每进程 1 次）；台账登记（16 号点）见 docs/footprint.md
     * 变更记录。</p>
     *
     * <p><b>为什么 TAIL 是正确的窗口</b>（javap 双证据）：① 客户端注册写入的映射
     * （{@code EntityRenderers.PROVIDERS} / {@code BlockEntityRenderers.PROVIDERS} /
     * {@code MenuScreens.SCREENS} / {@code DefaultAttributes.SUPPLIERS}）在 vanilla 中的读取
     * 全部晚于本点 —— {@code createEntityRenderers} 由 {@code EntityRenderDispatcher}
     * 构造调用（{@code Minecraft.<init>} 偏移 1947/1993）、{@code MenuScreens.getConstructor}
     * 在菜单打开时读取。本点相对 {@code Minecraft} 构造的位置因运行时而异：S1 在位构造之前
     * （Main.main → Bootstrap），S2 在构造中途（fabric-registry-sync-v0 的 afterModInit；
     * 实测栈 {@code Minecraft.<init>(Minecraft.java:477) → BuiltInRegistries.bootStrap()}）
     * —— 两种情形下映射构建点（偏移 1947/1993，或菜单打开）均晚于本点；② 这些写入会触发
     * 相关类 clinit（其中含内建渲染器注册，读取 {@code EntityType} 等注册表对象）——
     * 必须在 {@code createContents} 之后执行（HEAD 处注册表内容尚为 null），TAIL 是
     * "vanilla 内容已齐、映射构建之前"的唯一天然锚点。<b>S3 修正（T3 实测，2026-10-12）</b>：
     * 本 TAIL 是<b>事件点</b>（三运行时一致），但 <b>flush 落地点</b>在 S3 推迟到首个
     * {@code packs.registered} —— neo 内容注册（{@code ClientModLoader.begin}）晚于本
     * TAIL，TAIL 求值属性 Supplier 实测 NPE 于未绑定的 neo 属性 holder；
     * 详见 {@code ClientRegistrationBuffer} "S3 延迟落地"节。</p>
     *
     * <p><b>派发语义（事件点三运行时一致；flush 落地点按运行时）</b>：handler 受理
     * mod 在任意时机提交的客户端注册（缓冲 API，见 {@code api.BrushRegistry} 客户端组）
     * —— S1/S2 在 handler 内当场 {@code ClientRegistrationBuffer.flush()} 落地；
     * S3 由首个 {@code packs.registered} 的 sweeper 落地（理由见上）。随后派发本事件 ——
     * 监听器可在事件内做最后一批注册（S1/S2 下 flush 后到场立即执行；S3 下入缓冲、
     * 在 packs.registered 落地；安全性均由 {@code Minecraft.getInstance() != null 且
     * 窗口已关闭} 守卫保证 —— S2/S3 下窗口内实例可能已存在，判定与实测栈见
     * {@code ClientRegistrationBuffer} 窗口标记节）。事件监听器的注册因此在三运行时
     * 均被完整受理。</p>
     *
     * <p>非热路径（每进程 1 次）。自检口径：与 {@link #REGISTRIES_BOOTSTRAP} 同属<b>期望集</b>
     * （自检退出点前必然已触发；窗口生命周期与内容注册同源）。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.CLIENT_REGISTRATION}（无载荷事件）。</p>
     */
    public static final HookEvent CLIENT_REGISTRATION = new HookEvent("client.registration");

    /**
     * 资源包仓库构建完成 —— 模组资源（模型 / 材质 / 语言文件）接入点。
     *
     * <p>注入位置：{@code net.minecraft.server.packs.repository.PackRepository#<init>} 的 RETURN
     * （26.1.2 仅此一个构造器；描述符
     * {@code ([Lnet/minecraft/server/packs/repository/RepositorySource;)V}）。handler 读取实例字段
     * {@code sources} 并写回 {@code ModResourcePacks.attach(...)} 的返回值（追加模组资源包来源）。
     * <b>顺序链</b>：注入在仓库构造返回时立即执行，而 vanilla 到
     * {@code resourcePackRepository.reload()}（discoverAvailable 遍历 sources）才做发现与选中 ——
     * 我们追加的来源必然参与；attach 以 vanilla 来源数组中的
     * {@code net.minecraft.client.resources.ClientPackSource} 判定"客户端仓库"（世界创建界面的
     * 仓库仅含 {@code ServerPacksSource}，自动跳过）。与 Fabric 的同点位注入
     * （fabric-resource-loader 的 PackRepositoryMixin 同样读 {@code this.sources} 重建集合）
     * 任意先后施加互不抹除 —— 双方都基于字段当前内容工作。</p>
     *
     * <p>触发语义：每个 PackRepository 实例构造一次（客户端主仓库 1 次 + 世界创建界面的
     * 数据包选择仓库若干次）；<b>非热路径</b>。事件在 {@code attach} 的 finally 中派发 ——
     * 无论注入点是否修改了来源集合，命中即派发（含无 mod / 非客户端仓库的情形）。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.PACKS_REGISTERED}（无载荷事件；docs/14 §3）。</p>
     */
    public static final HookEvent PACKS_REGISTERED = new HookEvent("packs.registered");

    /**
     * 客户端主循环入口。
     *
     * <p>注入位置：{@code net.minecraft.client.Minecraft#run()V} 的 HEAD
     * （26.1.2 未混淆，官方名即运行时名，CLAUDE.md §5）。非热路径（每个进程仅一次）。
     */
    public static final HookEvent CLIENT_RUN = new HookEvent("client.run");

    /**
     * 客户端每帧 tick。
     *
     * <p>注入位置：{@code net.minecraft.client.Minecraft#tick()V} 的 HEAD。<b>热路径</b>
     * （每秒约 20 次，见 §10.3 第 5 条单独论证）：织入仅 1 条 {@code Dispatcher.fire} 静态调用，
     * 监听器缺席时为一次 ConcurrentHashMap 查找 + 空判断即返回（零分配，
     * {@link Dispatcher#fire(HookEvent)}），无监听器时不进循环、不建数组。
     */
    public static final HookEvent CLIENT_TICK = new HookEvent("client.tick");

    /**
     * 屏幕布局完成 —— {@code Screen.init(II)V} 的 TAIL（模组列表界面，docs/17）。
     *
     * <p>注入位置：{@code net.minecraft.client.gui.screens.Screen#init(int,int)} 的 TAIL
     * （首显路径 = {@code Minecraft.setScreen} 直调本方法；重复调用同实例走
     * {@code repositionElements()}）。TAIL 时该屏 widget 已就绪（首显为子类 {@code init()} 完成，
     * 重复为"先清后建"完成，链见 {@code ScreenMixin} javadoc）。</p>
     *
     * <p><b>派发语义</b>：对<b>所有</b>屏幕派发（参数 {@code args[0]} = Screen 实例，对象同一性
     * 即目标实例）；类型过滤是监听器的职责（当前唯一消费者 {@code ModsUi} 只处理
     * {@code TitleScreen}）。与 {@link #SCREEN_RESIZE} 配套恰好覆盖全部布局路径 ——
     * 缩放路径不经过本方法（{@code resize} 直连 {@code repositionElements}），单点会漏，
     * 见 {@code ScreenMixin} javadoc。</p>
     *
     * <p>非热路径（每屏首次显示 1 次；窗口拖拽期间高频的是 {@link #SCREEN_RESIZE}）。
     * 自检口径：本事件为<b>信息项</b>（{@code ui.mods} 行），<b>不进</b> {@code CoreHooks}
     * 期望集 —— 首屏布局与自检退出点的先后顺序不作断言（见 {@code Selftest} javadoc）。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.SCREEN_INIT}（载荷 = Screen 实例；docs/14 §3）。</p>
     */
    public static final HookEvent SCREEN_INIT = new HookEvent("screen.init");

    /**
     * 屏幕缩放完成 —— {@code Screen.resize(II)V} 的 TAIL（模组列表界面，docs/17）。
     *
     * <p>注入位置：{@code net.minecraft.client.gui.screens.Screen#resize(int,int)} 的 TAIL
     * （方法体 = width/height 赋值 → {@code repositionElements()}；
     * 调用链 = {@code Minecraft.resizeGui()} → {@code Window.setGuiScale} → 本方法）。
     * TAIL 时 widget 已按新尺寸"先清后建"完毕（同 {@link #SCREEN_INIT} 的重复 init 路径），
     * 监听器由此采用"先移除再添加"的幂等挂载。</p>
     *
     * <p><b>派发语义</b>：同 {@link #SCREEN_INIT}（所有屏幕，{@code args[0]} = Screen 实例）。
     * 非热路径但可突发：窗口拖拽缩放期间随窗口系统事件连续触发，频率远低于每帧 tick。
     * 自检口径同 {@link #SCREEN_INIT}（信息项，不进期望集）。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.SCREEN_RESIZE}（载荷 = Screen 实例；docs/14 §3）。</p>
     */
    public static final HookEvent SCREEN_RESIZE = new HookEvent("screen.resize");

    /**
     * 创造模式物品栏内容构建完成 —— 向现有标签页<b>追加</b>条目的接入点。
     *
     * <p>注入位置：{@code net.minecraft.world.item.CreativeModeTab#buildContents} 的 TAIL
     * （方法体 = new ItemDisplayBuilder → displayItemsGenerator.accept → 两处 putfield：
     * {@code this.displayItems = builder.tabContents} /
     * {@code this.displayItemsSearchTab = builder.searchTabContents}）。TAIL 时两个内容集合
     * 均已赋值完毕，handler 直接读取实例字段（{@code @Shadow}）。</p>
     *
     * <p><b>为什么只做"追加"</b>：vanilla 14 个标签页的 (row,column) 槽位已全部占满，且
     * {@code Bootstrap#bootStrap()V} 在注册窗口之后立即
     * {@code invokestatic CreativeModeTabs.validate:()V}（每一条生产启动路径都经过）——
     * 新注册的标签页会撞上 validate 的重复槽位 IllegalArgumentException 而中止启动；
     * 自定义标签页因此需要分页子系统（Fabric 同款工程量）。本事件只做"向现有标签页追加条目"；
     * 经 {@code BrushRegistry} 注册 CREATIVE_MODE_TAB 会被门面拒收（指引见该处守卫）。</p>
     *
     * <p><b>载荷</b>（{@code args}，4 参重载 {@link Dispatcher#fire(HookEvent, Object, Object, Object, Object)}）：
     * [0] = {@code ResourceKey<CreativeModeTab>}（该标签页的注册表键）、
     * [1] = {@code CreativeModeTab$ItemDisplayParameters}、
     * [2] = {@code Collection<ItemStack>}（标签页内容，可写）、
     * [3] = {@code Set<ItemStack>}（搜索标签页内容，可写）。handler 以键相等判定目标标签页。</p>
     *
     * <p><b>触发语义</b>：每次 {@code buildContents} 调用一次（标签页首建 / 重开创造界面重建 /
     * 搜索刷新批量重建）—— <b>非热路径</b>但可突发（重建时多个标签页连续各一次）。
     * 自检口径：<b>信息项</b>（{@code creative.tabs} 行 + {@code CoreHooks.creativeTabHits()}），
     * <b>不进</b>期望集 —— 自检退出点前是否发生过标签页构建不作断言；但报告时若有订阅，
     * 会做一次实弹探针（真实构建 + 投递计数 + 派发计数断言，见 {@code Selftest} javadoc）。</p>
     *
     * <p>L2 语义层：{@code BrushCreativeTabs.entriesOf(...)}（按标签页键订阅；docs/14 §9）。</p>
     */
    public static final HookEvent CREATIVE_TAB_OUTPUT = new HookEvent("creative.tab.output");

    /**
     * 客户端 HUD 提取完成（docs/19 §9）—— 每帧一次。
     *
     * <p>注入位置：{@code net.minecraft.client.gui.Gui#extractRenderState} 的 TAIL
     * （26.1.2 官方名；描述符
     * {@code (Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V}，
     * public）。TAIL 时该帧的 vanilla HUD 元素（准星 / 快捷栏 / 聊天等）已全部写入
     * {@code GuiGraphicsExtractor}，监听器可继续追加绘制。</p>
     *
     * <p><b>载荷</b>（{@link Dispatcher#fire(HookEvent, Object, Object)}）：
     * [0] = {@code GuiGraphicsExtractor}、[1] = {@code DeltaTracker}（与目标方法同参直传）。</p>
     *
     * <p><b>热路径</b>（每渲染帧 1 次，见 §10.3 第 5 条单独论证）：织入仅 1 条 fire 调用；
     * 无监听器时 = 一次 CHM 查找 + 空判断（零分配）—— 与 {@link #CLIENT_TICK} 同一论证。</p>
     *
     * <p>自检口径：<b>信息项</b>（{@code ui.hud} 行）—— 自检退出点（首个 {@code client.tick}）
     * 位于首帧渲染<b>之前</b>，本事件在退出点前必然 0 次，不作断言（同 {@code screen.init} 口径）；
     * 探针做目标类强制加载 + 方法存在断言（{@code SelftestInputHudProbe}）。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.HUD_EXTRACT}（载荷 = extractor + delta，docs/19 §9）。</p>
     */
    public static final HookEvent HUD_EXTRACT = new HookEvent("hud.extract");

    /**
     * 键盘按键回调（docs/19 §9）。
     *
     * <p>注入位置：{@code net.minecraft.client.KeyboardHandler#keyPress} 的 HEAD
     * （26.1.2 官方名；描述符
     * {@code (JILnet/minecraft/client/input/KeyEvent;)V}，private；GLFW 回调经
     * {@code Minecraft.execute} 转发到此）。方法体的第一动作是<b>窗口句柄守卫</b>
     * （{@code window != minecraft.getWindow().handle() → return}）—— HEAD 注入先于守卫执行。</p>
     *
     * <p><b>载荷</b>（{@link Dispatcher#fire(HookEvent, Object, Object)}）：
     * [0] = {@code KeyEvent}（record：key / scancode / modifiers）、
     * [1] = {@code Integer} GLFW action（0=RELEASE / 1=PRESS / 2=REPEAT，
     * 见 {@code api.InputAction}）。不携带窗口句柄（监听器不需要，载荷最小化）。</p>
     *
     * <p>非热路径但可突发（按键重复 / 长按连发）。自检口径：<b>信息项</b> —— 自检无人输入，
     * 退出点前计数为 0 是正常态；探针做 live-fire（伪窗口句柄调用 → 守卫保证 vanilla 体零副作用，
     * 断言派发计数增长，{@code SelftestInputHudProbe}）。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.INPUT_KEY_PRESS}（docs/19 §9）。</p>
     */
    public static final HookEvent INPUT_KEY_PRESS = new HookEvent("input.key.press");

    /**
     * 鼠标按键回调（docs/19 §9）。
     *
     * <p>注入位置：{@code net.minecraft.client.MouseHandler#onButton} 的 HEAD
     * （26.1.2 官方名；描述符
     * {@code (JLnet/minecraft/client/input/MouseButtonInfo;I)V}，private；同款窗口句柄守卫在方法头）。</p>
     *
     * <p><b>载荷</b>： [0] = {@code MouseButtonInfo}（record：button / modifiers）、
     * [1] = {@code Integer} GLFW action（0=RELEASE / 1=PRESS）。</p>
     *
     * <p>冷热 / 自检口径 / L2：同 {@link #INPUT_KEY_PRESS}
     * （L2 = {@code BrushEvents.INPUT_MOUSE_BUTTON}）。</p>
     */
    public static final HookEvent INPUT_MOUSE_BUTTON = new HookEvent("input.mouse.button");

    /**
     * 鼠标滚轮回调（docs/19 §9）。
     *
     * <p>注入位置：{@code net.minecraft.client.MouseHandler#onScroll} 的 HEAD
     * （26.1.2 官方名；描述符 {@code (JDD)V}，private；窗口句柄守卫为
     * 分支取反形态 {@code if (window != handle) return}，语义相同）。</p>
     *
     * <p><b>载荷</b>： [0] = {@code Double} xOffset、[1] = {@code Double} yOffset（原始滚轮增量）。</p>
     *
     * <p>冷热 / 自检口径 / L2：同 {@link #INPUT_KEY_PRESS}
     * （L2 = {@code BrushEvents.INPUT_MOUSE_SCROLL}）。</p>
     */
    public static final HookEvent INPUT_MOUSE_SCROLL = new HookEvent("input.mouse.scroll");

    /**
     * 收到 Brush 通道载荷（网络组<b>投递侧</b>，docs/19 §8）。
     *
     * <p>注入位置：{@code net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl#handleCustomPayload}
     * 的 HEAD（26.1.2 官方名；描述符
     * {@code (Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V}，public；
     * 该类另有一个 abstract 重载 {@code (CustomPacketPayload)V} —— 注入必须写全描述符）。
     * handler 仅当 {@code packet.payload() instanceof BrushPayload} 时派发 ——
     * 类型判定在我们<b>织入的片段内</b>，vanilla 控制流不受影响（§10.1）；
     * 既有 vanilla 载荷（brand 等）零派发开销。</p>
     *
     * <p><b>载荷</b>（{@code Dispatcher.fire(HookEvent, Object, Object)}）：
     * [0] = {@code net.minecraft.resources.Identifier}（通道 id）、[1] = {@code byte[]}（数据）。
     * **2026-10-15 改**：原为单个 {@code BrushPayload} —— 改成 (id, data) 后核心 L2 桥不再持有
     * 具体载荷类型（docs/26 §7.6 第①条后续；为网络引擎整体搬进库模组铺路）。</p>
     *
     * <p>非热路径（每次收到一个自定义载荷包 1 次）。自检口径：<b>信息项</b> —— 自检无连接、
     * 无服务器投递，退出点前计数为 0 是正常态；编解码链的验证在<b>编码侧单点</b>
     * {@code CustomPacketPayload$1#findCodec} 的 RETURN 包装（该点不派发事件、只换 codec，
     * 见 {@code org.abstruck.brush.net.BrushChannels} javadoc —— 该类自 2026-10-15 起住在库模组
     * {@code brush-api} 里，故此处只能用 {@code @code} 而非 {@code @link}）；自检探针做离线 codec 往返 +
     * 负控断言（{@code SelftestNetworkProbe}）。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.NETWORK_PAYLOAD}（docs/19 §8）。</p>
     */
    public static final HookEvent NETWORK_PAYLOAD = new HookEvent("network.payload");

    /**
     * 服务端收到 Brush 通道载荷（docs/27 §2.7 发现 B 的补齐）：L2 对应
     * {@code BrushEvents.SERVER_PAYLOAD}。
     *
     * <p><b>为什么必须有它</b>：既有 {@link #NETWORK_PAYLOAD} 只覆盖客户端侧接收，
     * 于是"双向通信"对普通 mod 不可达（回包需要构造 {@code ClientboundCustomPayloadPacket}
     * 与核心内部件，而普通 mod 不得触碰 L1 / 内部件 —— §6）。补上本点后，双向这件事
     * 对 mod 仍然**只有 L2**：回包经 {@code ServerPayloadEvent.Reply} 句柄，
     * 其实现由库模组提供。</p>
     *
     * <p><b>载荷</b>（{@code Dispatcher.fire(HookEvent, Object, Object, Object)}）：
     * [0] = {@code ServerPayloadEvent.Reply}（回包句柄）、[1] = {@code Identifier}（通道 id）、
     * [2] = {@code byte[]}（数据）。**2026-10-15 改**：末两参原为单个 {@code BrushPayload}，
     * 理由同 {@link #NETWORK_PAYLOAD}。</p>
     *
     * <p><b>注入点</b>：{@code ServerGamePacketListenerImpl#handleCustomPayload(ServerboundCustomPayloadPacket)}
     * HEAD（javap 实证）；injector = {@code @Inject}（可链式）；mixin 住在<b>库模组</b>
     * {@code brush-api}（Phase C 的能力归属）。</p>
     *
     * <p><b>冷路径</b>：每次收到一个自定义载荷包 1 次。自检（T1/T2/T3）中恒 0 ——
     * 与 {@link #DATA_LOADED} 同款：它属"服务端侧、退出点前不发生"的事件，
     * 参与不了期望集；实况证据由 `runRealityProbe`（真进世界）承担。</p>
     */
    public static final HookEvent SERVER_PAYLOAD = new HookEvent("network.server.payload");

    /**
     * 服务器数据加载完成 —— 集成服务器构造完成（Brush 数据包注册表装载锚点，G1，docs/21）。
     *
     * <p><b>注入位置</b>：{@code net.minecraft.server.MinecraftServer#<init>} 的 RETURN
     * （26.1.2 唯一构造器，javap 实证；10 参全描述符见 {@code MinecraftServerMixin} javadoc）。
     * RETURN 处 {@code getResourceManager()} 已非空（世界数据全部就位）—— 是该时刻可达的
     * 最早注入点；服务器构造必晚于 {@code BuiltInRegistries.bootStrap}（内容窗口），无需
     * {@code EarlyBoot} 兜底。</p>
     *
     * <p><b>触发语义</b>：每个 {@code MinecraftServer} 实例一次（单机 = 进世界 / 建世界时；
     * LAN 主机同）。handler 先驱动 {@code DataRegistryEngine.onServerConstructed}（装载 Brush
     * 数据包注册表 —— 先装载后派发，监听器读到的是最新视图），再派发本事件；装载失败
     * （数据错误）不中断构造 —— 旧表保留、逐键 ERROR 日志、事件照发
     * （语义见 {@code DataRegistryEngine} javadoc）。远端多人服务器不产生本事件
     * （客户端 JVM 无 {@code MinecraftServer}，docs/21 已知边界）。</p>
     *
     * <p>非热路径（每服务器实例 1 次）。自检口径：<b>信息项</b>（{@code CoreHooks.INFO_HITS}）
     * —— T1/T2/T3 自检均在加载世界前退出，退出点前计数恒 0，不作期望集断言；实况命中证据
     * 由 RealityProbe（入世界后）承担。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.DATA_LOADED}（载荷 = MinecraftServer；docs/21）。</p>
     */
    public static final HookEvent DATA_LOADED = new HookEvent("data.loaded");

    /**
     * 服务器数据重载完成 —— {@code /reload} / 数据包变更的重载完成（G1，docs/21）。
     *
     * <p><b>注入位置</b>：{@code MinecraftServer#reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;}
     * 的整方法包装（{@code @WrapMethod}，MixinExtras 可链式 —— §7.2；先例
     * {@code CreativeModeInventoryScreenMixin}）。handler 调用原方法取 future、以
     * {@code whenComplete} 挂引擎回调，随后<b>原样返回</b>该 future（不改返回值 —— §10.1）。
     * 资源交换在 future 完成前发生 —— 回调时刻即"重载完成"时刻。</p>
     *
     * <p><b>触发语义</b>：每次 {@code reloadResources} 完成一次（无论成功 / 失败 ——
     * 失败时载荷 = 原异常，引擎跳过装载、旧表保留）。引擎回调（whenComplete 注册于方法返回
     * <b>之前</b> —— 不受外部调用方行为影响；与调用方后续注册的其他回调的相对顺序无契约，
     * 引擎也不依赖该顺序）随后重载 Brush 数据包注册表，再派发本事件。</p>
     *
     * <p><b>异步异常偏离（§3.5，有意）</b>：本事件的派发发生在 {@code CompletableFuture}
     * 回调线程内 —— 该上下文重抛的异常会被 CompletableFuture 静默吞掉；引擎在此
     * catch + ERROR 记录（本工程唯一偏离点），详见 {@code DataRegistryEngine} javadoc。</p>
     *
     * <p>非热路径（每次重载 1 次）。自检口径：信息项，同 {@link #DATA_LOADED}
     * （退出点前恒 0 次）。</p>
     *
     * <p>L2 语义层：{@code BrushEvents.DATA_RELOADED}（载荷 = (MinecraftServer,
     * Optional&lt;Throwable&gt;)；docs/21）。</p>
     */
    public static final HookEvent DATA_RELOADED = new HookEvent("data.reload");

    /**
     * 客户端进程关闭（生命周期<b>延后事件</b>）。
     *
     * <p><b>事件源不是注入点</b>：关闭时刻的可复用注入点不存在（{@link #CLIENT_RUN} 的注入在
     * 方法 HEAD、不覆盖返回路径）；且锚点方案覆盖面小于 JVM shutdown hook（含不经
     * {@code Minecraft#stop()} 的自检退出）—— 因此本事件由 {@link CoreHooks} 安装的
     * <b>JVM shutdown hook</b> 投递，零 footprint
     * （不出现在 {@link InjectionLedger} / {@code docs/footprint.md} 的注入点清单中）。
     * 已评估的备选锚点 {@code Minecraft#close()/#stop()/#destroy()} 与否决理由见
     * {@code docs/14-事件总线与API层.md}。
     *
     * <p><b>触发时机</b>：JVM 关闭序列中（正常退窗、{@code System.exit} 含自检退出均触发；
     * 进程被强杀 / {@code Runtime.halt} 不触发）。触发线程 = JVM 关闭线程（非游戏主线程）；
     * 仅在客户端确实启动过（{@link #CLIENT_RUN} 已触发）时派发。
     *
     * <p><b>证据通道</b>：触发时游戏侧 log4j 已关闭，且 {@code System.out} 被 MC 换成 log4j
     * 承接的流 —— 经 log4j / {@code System.out} 的关闭期输出会被静默丢弃（见 docs/14 §4）。
     * 计数/证据行因此走 {@link RawStdout}（fd 1 直写，关闭钩子线程中同样送达）。
     * 本事件<b>刻意不进自检期望集</b>（退出点之后才触发），
     * 见 {@code Selftest} javadoc"期望集 / 延后集"。
     */
    public static final HookEvent CLIENT_STOPPING = new HookEvent("client.stopping");

    /**
     * 崩溃报告段注入（docs/25 §3.2，缺口 #2）：vanilla 组装崩溃报告正文时触发，
     * handler 把 {@code BrushCrashReport.section()} 追加进正文。
     *
     * <p><b>载荷</b>：[0] = {@code StringBuilder}（vanilla 正在组装的报告正文）。
     * handler <b>只许追加</b>，不得清空 / 重排既有内容 —— 那是独占式语义，
     * 会破坏与其他 mod 的共存（§7.2 的精神）。</p>
     *
     * <p><b>冷路径</b>：只有真的崩溃且有人取报告正文时才触发；正常游玩与自检（T1/T2/T3）
     * 中恒 0 次。因此本事件<b>刻意不进自检期望集</b>（走 {@code CoreHooks} 的信息集，
     * 划分依据见其 {@code INFO_HITS} javadoc）—— 进期望集会让 {@code injection.audit}
     * 在三场里误报缺失。</p>
     *
     * <p><b>注入点</b>：{@code net.minecraft.CrashReport#getDetails(StringBuilder)} TAIL
     * （javap 实证：它是 26.1.2 报告正文的唯一组装点）；injector = {@code @Inject}（可链式），
     * 台账见 docs/footprint.md 缺口 #2 行。</p>
     */
    public static final HookEvent CRASH_REPORT_DETAILS = new HookEvent("crash.report.details");

    private HookEvents() {
    }
}

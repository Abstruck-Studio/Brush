package org.abstruck.brush.transform;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * footprint 台账的代码侧镜像（CLAUDE.md §10.5：启动日志必须打印注入点总数与被注入 vanilla 类数）。
 *
 * <p>{@link #ENTRIES} 必须与 {@code docs/footprint.md} 的台账表逐条一致 —— 每次新增/删除注入点，
 * 两处同改（§14.2 T4：基线 / 台账 / 代码镜像 / mixin 实际指令四面一致）。</p>
 *
 * <p>为什么是静态镜像而不是运行时统计：注入点是可链式 {@code @Inject} + {@code require=1}，
 * "未命中即启动失败"已由 mixin 硬保证；此处只负责把"设计上应有几个"打印出来。</p>
 *
 * <p>public：宿主适配与 {@code org.abstruck.brush.selftest.Selftest} 打印注入面数字用 —— 只读，
 * 不反向依赖宿主（§9.3）。</p>
 */
public final class InjectionLedger {

    /**
     * 条目格式：{@code vanilla类#方法 (可读名) 位置 注入器 事件 (热路径标注)}。
     * 类名部分以 '#' 分隔，供 {@link #injectedClasses()} 去重计数。
     */
    private static final String[] ENTRIES = {
            "net.minecraft.server.Bootstrap#bootStrap"
                    + " (Bootstrap.bootStrap) HEAD @Inject bootstrap.early (cold)",
            "net.minecraft.core.registries.BuiltInRegistries#bootStrap"
                    + " (BuiltInRegistries.bootStrap) HEAD @Inject registries.bootstrap (cold)",
            "net.minecraft.core.registries.BuiltInRegistries#bootStrap"
                    + " (BuiltInRegistries.bootStrap) TAIL @Inject client.registration (cold)",
            "net.minecraft.server.packs.repository.PackRepository#<init>"
                    + " (PackRepository.<init>) RETURN @Inject packs.registered (cold)",
            "net.minecraft.client.Minecraft#run (Minecraft.run) HEAD @Inject client.run (cold)",
            "net.minecraft.client.Minecraft#tick (Minecraft.tick) HEAD @Inject client.tick (hot)",
            "net.minecraft.client.gui.screens.Screen#init(II)V"
                    + " (Screen.init) TAIL @Inject screen.init (cold)",
            "net.minecraft.client.gui.screens.Screen#resize(II)V"
                    + " (Screen.resize) TAIL @Inject screen.resize (cold/bursty)",
            "net.minecraft.world.item.CreativeModeTab#buildContents"
                    + " (CreativeModeTab.buildContents) TAIL @Inject creative.tab.output (cold/bursty)"
                    + " [source=brush-api]",
            "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen#extractTabButton"
                    + " (CreativeModeInventoryScreen.extractTabButton) METHOD-WRAP @WrapMethod"
                    + " creative.tab.phantom-suppress (hot) [source=brush-api]",
            // CustomPacketPayload$1#findCodec 不派发 Dispatcher 事件，目的 token
            // network.channel-codec 为非事件用途标识（同 creative.tab.phantom-suppress 先例）。
            "net.minecraft.client.gui.Gui#extractRenderState"
                    + " (Gui.extractRenderState) TAIL @Inject hud.extract (hot) [source=brush-api]",
            "net.minecraft.client.KeyboardHandler#keyPress"
                    + " (KeyboardHandler.keyPress) HEAD @Inject input.key.press (cold/bursty)"
                    + " [source=brush-api]",
            "net.minecraft.client.MouseHandler#onButton"
                    + " (MouseHandler.onButton) HEAD @Inject input.mouse.button (cold/bursty)"
                    + " [source=brush-api]",
            "net.minecraft.client.MouseHandler#onScroll"
                    + " (MouseHandler.onScroll) HEAD @Inject input.mouse.scroll (cold/bursty)"
                    + " [source=brush-api]",
            // [source=brush-api] —— Phase C 起本条由库模组 brush-api 提供（docs/26 §7）：
            // 台账的语义是"对 vanilla 的全部改动面"，因此**跨 jar 仍登记在一处**（单一镜像，
            // 避免两份镜像漂移）；归属由方括号标注，闸门按来源分别扫描后求和 / 取并集校验。
            "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl#handleCustomPayload"
                    + " (ClientCommonPacketListenerImpl.handleCustomPayload) HEAD @Inject network.payload"
                    + " (cold) [source=brush-api]",
            "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1#findCodec"
                    + " (CustomPacketPayload$1.findCodec) RETURN @ModifyReturnValue network.channel-codec"
                    + " (cold) [source=brush-api]",
            // G1（docs/21）：数据锚点 —— 集成服务器构造完成 / 数据重载完成。
            "net.minecraft.server.MinecraftServer#<init>"
                    + " (MinecraftServer.<init>) RETURN @Inject data.loaded (cold)",
            "net.minecraft.server.MinecraftServer#reloadResources"
                    + " (MinecraftServer.reloadResources) METHOD-WRAP @WrapMethod data.reload (cold)",
            // 缺口 #2（docs/25 §3.2）：崩溃报告注入 mod 列表 —— 只在崩溃取详情时走，纯冷路径。
            "net.minecraft.CrashReport#getDetails"
                    + " (CrashReport.getDetails) TAIL @Inject crash.report.details (cold)",
            // docs/27 §2.7 发现 B：服务端载荷接收 —— 补上"网络只有单向"这个硬阻塞（双向通信）。
            "net.minecraft.server.network.ServerGamePacketListenerImpl#handleCustomPayload"
                    + " (ServerGamePacketListenerImpl.handleCustomPayload) HEAD @Inject"
                    + " network.server.payload (cold) [source=brush-api]",
            // 键位注册：把 mod 的 KeyMapping 接进 Options.keyMappings（final 数组，只在构造期赋值一次；
            // 且构造器内部随后 load() 会读它 —— 故锚在"产生该数组的表达式"而非字段写）
            "net.minecraft.client.Options#<init>"
                    + " (Options.<init>) INVOKE @ModifyExpressionValue Stream.toArray keybinds.splice"
                    + " (cold, once per Options) [source=brush-api]",
    };

    private InjectionLedger() {
    }

    public static int injectionPoints() {
        return ENTRIES.length;
    }

    public static int injectedClasses() {
        return injectedClassNames().size();
    }

    /**
     * 被注入类的<b>名字集合</b>（去重、稳定序）。
     *
     * <p><b>为什么需要它而不只是计数</b>：Phase C 把游戏能力型 API 抽成独立 mod 后，注入面会
     * 跨 jar 分散（docs/26 §3.1）—— 此时"被注入类数"必须跨来源取<b>并集</b>，
     * 而并集只能由名字算，靠各来源的计数相加会算错（例如
     * {@code BuiltInRegistries#bootStrap} 上 HEAD 留核心、TAIL 随能力外移，
     * 两个 jar 各算一次就会把同一类数成两个）。</p>
     */
    public static List<String> injectedClassNames() {
        Set<String> classes = new LinkedHashSet<>();
        for (String entry : ENTRIES) {
            int hash = entry.indexOf('#');
            classes.add(hash < 0 ? entry : entry.substring(0, hash));
        }
        return List.copyOf(classes);
    }

    public static String summary() {
        return "injection points: " + injectionPoints()
                + ", injected vanilla classes: " + injectedClasses()
                + " (footprint ledger: docs/footprint.md)";
    }
}

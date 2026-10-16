package org.abstruck.brush.host.fabric;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * S2 创造栏分页兼容（{@code fabric-creative-tab-api-v1}）—— 为 Brush 自有页的 vanilla 桥补
 * Fabric 分页页码，使 {@code selectTab} 能通过 Fabric 屏幕 mixin 的守卫。S1/S3 无此类
 * （{@code Class.forName} 落空即 no-op），不改变其行为。
 *
 * <p><b>为什么必须补页码</b>：Fabric API 的 {@code CreativeModeInventoryScreenMixin} 对
 * vanilla {@code selectTab(CreativeModeTab)} 织入守卫 {@code if (!isTabVisible(tab)) ci.cancel()}
 * （{@code isTabVisible} = {@code tab.shouldDisplay() && currentPage == getPage(tab)}），
 * 并对屏幕 {@code init()} 织入 {@code currentPage = getPage(selectedTab)}（<b>无条件</b>）。
 * 而 {@code getPage(tab)} 对 COMMON_TABS（恒为 {search, inventory, hotbar, op_blocks}
 * 四个注册表页）之外一律取 {@code ((FabricCreativeModeTabImpl) tab).fabric_getPage()} ——
 * 该实现 {@code page < 0}（未分页）即抛 {@code IllegalStateException("Creative mode tab
 * has no page")}。Fabric 的分页器只给<b>注册表内</b>的页分配页码；Brush 自有页不进注册表
 * （{@code BrushCreativeTab} javadoc），页码恒为 -1。因此不做兼容时：侧栏点击的选中会被
 * 守卫静默取消，且桥页一旦成为 {@code selectedTab}，屏幕下次 {@code init}（重开物品栏 /
 * 改窗口大小）必抛 page 缺失。补页码（= 桥页声明的页码取屏幕<b>当前页</b>）使两条路径都
 * 成立：选中时 {@code isTabVisible} 为真、后续 init 读到合法页（用户继续在原页视图内看到
 * 我们的页内容）。</p>
 *
 * <p><b>为什么是反射 + impl 类名</b>：读页码有公开 API ——
 * {@code FabricCreativeModeInventoryScreen#getCurrentPage()}（api 包，屏幕 mixin 实现）；
 * 写页码<b>没有</b>公开面 —— 公开的 {@code FabricCreativeModeTab} 只有 {@code builder()}，
 * 写入口只有 impl 包的 {@code FabricCreativeModeTabImpl#fabric_setPage(int)}。CLAUDE.md §12.4
 * 要求单 jar 三运行时、加载器构建不得依赖 fabric-api（它只是 S2 测试环境里的宿主 mod），故本类
 * <b>零编译期 Fabric 依赖</b>：两个类名与方法名都是字符串常量 + 反射。分页器不会覆盖桥页的
 * 页码（它只遍历注册表），补一次即持久。</p>
 *
 * <p><b>漂移防线（单一事实来源）</b>：反射面解析只在本类一处（{@link #resolveOrNull()}，
 * 类名 / 方法名常量同处）—— 运行期的 {@link #prepareSelection} 与 T2 自检探针的
 * {@code creative.tab.compat} 行<b>共用同一次解析</b>，字符串不存在第二份副本；Fabric API
 * 若漂移，T2 硬失败（{@code EXIT_TAB_AUDIT}）而不是留到运行期才暴露。</p>
 *
 * <p><b>已知残差</b>（如实登记）：① 空自有页（无任何条目）在 S2 下不可选中 ——
 * {@code shouldDisplay()} 为 false 使守卫取消选中，属于 Fabric 语义；
 * ② 补页码只影响 Fabric 分页账本，S1/S3 无等价概念，无行为差异。</p>
 *
 * <p><b>依赖方向</b>（§18）：本类在 {@code .host} 子包内、仅编译期引用 MC 与 java 类型
 * （Fabric 侧全为字符串），核心加载路径不引用本类；生产调用点只有 UI 层的
 * {@code TabSidebarMount}（宿主适配是 UI 激活路径的一部分），验证调用点是自检探针
 * （{@code SelftestCreativeTabProbe}，经 {@link #resolveOrNull()} 断言同一反射面）。</p>
 */
public final class FabricCreativeTabCompat {

    /** 公开 API 接口（屏幕 mixin 实现它；{@code getCurrentPage} 为 public）。 */
    public static final String SCREEN_API_CLASS =
            "net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen";

    /** impl 接口（mixin 合并进 {@code CreativeModeTab}；{@code fabric_setPage(int)} 为 public）。 */
    public static final String TAB_IMPL_CLASS =
            "net.fabricmc.fabric.impl.creativetab.FabricCreativeModeTabImpl";

    private static final Logger LOGGER = LogManager.getLogger(FabricCreativeTabCompat.class);

    private FabricCreativeTabCompat() {
    }

    /**
     * 解析结果（{@link #resolveOrNull()} 的产物）—— 四个反射成员，解析即得、无副作用。
     *
     * @param screenApi     屏幕侧公开 API 接口类
     * @param getCurrentPage 其 {@code int getCurrentPage()}（实例 = 屏幕）
     * @param tabImpl       标签页侧 impl 接口类
     * @param setPage       其 {@code void fabric_setPage(int)}（实例 = 桥页）
     */
    public record Surface(Class<?> screenApi, Method getCurrentPage, Class<?> tabImpl, Method setPage) {
    }

    /**
     * 解析 Fabric 创造栏分页反射面（单一事实来源；见类 javadoc"漂移防线"）。三条退出：
     * <ul>
     *   <li>返回 {@code null}：{@link #SCREEN_API_CLASS} 不在场（S1/S3 / 无 fabric-api）——
     *       特性缺席，非错误；</li>
     *   <li>抛 {@link IllegalStateException}：类在场但方法缺失（Fabric API 漂移）—— 调用方
     *       必须响铃（运行期 = 点击失败可读日志；探针 = 硬失败）；</li>
     *   <li>返回 {@link Surface}：反射面可用。</li>
     * </ul>
     */
    public static Surface resolveOrNull() {
        Class<?> screenApi;
        try {
            screenApi = Class.forName(SCREEN_API_CLASS);
        } catch (ClassNotFoundException absent) {
            return null;
        }
        try {
            Class<?> tabImpl = Class.forName(TAB_IMPL_CLASS);
            return new Surface(screenApi,
                    screenApi.getMethod("getCurrentPage"),
                    tabImpl,
                    tabImpl.getMethod("fabric_setPage", int.class));
        } catch (ClassNotFoundException | NoSuchMethodException broken) {
            throw new IllegalStateException("[brush] fabric creative-tab compat: " + SCREEN_API_CLASS
                    + " is present but the page surface (" + SCREEN_API_CLASS + "#getCurrentPage / "
                    + TAB_IMPL_CLASS + "#fabric_setPage(int)) does not resolve -- the"
                    + " fabric-creative-tab-api-v1 version likely changed; without the page seed the"
                    + " selectTab guard would cancel or crash (see class javadoc)", broken);
        }
    }

    /**
     * 选中桥页前的准备（{@code TabSidebarMount.activateTab} 调用）：Fabric 分页 API 在场时把
     * 桥页页码补为屏幕当前页；不在场（S1/S3 / 无 fabric-api）即 no-op。
     *
     * @param screen    当前创造屏实例（Fabric 下已由屏幕 mixin 实现 {@code SCREEN_API_CLASS}）
     * @param bridgeTab 待选中的桥实例（{@code BrushCreativeTab#vanillaTab()}）
     * @throws IllegalStateException Fabric API 在场但反射面失配或调用失败 —— 不静默降级，
     *         交由调用方记录（见 {@link #resolveOrNull()}）
     */
    public static void prepareSelection(CreativeModeInventoryScreen screen, CreativeModeTab bridgeTab) {
        Surface surface = resolveOrNull();
        if (surface == null) {
            // S1/S3 / fabric-api 未安装 —— 兼容层设计的 no-op 路径（非错误）
            LOGGER.debug("[brush] fabric creative-tab compat: " + SCREEN_API_CLASS
                    + " absent -- no pagination gate, nothing to seed");
            return;
        }
        if (!surface.screenApi().isInstance(screen)) {
            // 类在场但屏幕未实现（理论上同模块同现；防御式 no-op，不猜测）
            LOGGER.debug("[brush] fabric creative-tab compat: screen does not implement "
                    + SCREEN_API_CLASS + " -- skipping page seed");
            return;
        }
        try {
            int page = (Integer) surface.getCurrentPage().invoke(screen);
            surface.setPage().invoke(bridgeTab, page);
            LOGGER.info("[brush] fabric creative-tab compat: seeded bridge tab page=" + page
                    + " (Fabric selectTab guard / screen init read getPage(selectedTab); see class javadoc)");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("[brush] fabric creative-tab compat: page seed failed"
                    + " (getCurrentPage / fabric_setPage invocation)", failure);
        }
    }
}

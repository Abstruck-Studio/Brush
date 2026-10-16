package org.abstruck.brush;

/**
 * Brush 内置 UI 模块的装载契约（Phase D，2026-10-12；评审 P1-5）。
 *
 * <p><b>解决的问题</b>：{@code transform} 层此前对 {@code ui} 包存在<b>硬编码编译期依赖</b>
 * （{@code EarlyBoot.boot()} 直接调用 {@code ModsUi.install()} / {@code TabSidebar.install()}）——
 * 这是分层倒置（核心层反向依赖界面层），且每加一个 UI 模块就要改核心引导代码。
 * 本 SPI 把它改为<b>发现式</b>：核心只依赖本接口（根包，零逻辑、零 MC 类型），模块实现类
 * 经 {@code META-INF/services} 注册，{@code EarlyBoot} 用 {@link java.util.ServiceLoader} 装载。
 *
 * <p><b>类加载器纪律（S1 类身份陷阱，勿改）</b>：装载必须用 {@code EarlyBoot} 自身的类加载器
 * （{@code EarlyBoot.class.getClassLoader()}），<b>不得</b>用线程上下文类加载器（TCCL）——
 * S1 下 {@code EarlyBoot} 由 {@code BrushClassLoader} 加载，而 TCCL 是 app 加载器：经 TCCL
 * 装载会得到 app 侧的 {@code BrushModule}/{@code ModsUi} 第二份类，与 {@code BrushClassLoader}
 * 侧的接口不是同一个类，for-each 处当场 {@code ClassCastException}。语义与理由见
 * {@code EarlyBoot#installModules()}。
 *
 * <p><b>实现约束</b>：实现类必须 public + public 无参构造器（{@code ServiceLoader} 要求）；
 * {@link #install()} 应幂等（引导可能被多入口触发，见 {@code EarlyBoot} 的幂等语义）；
 * 引导期执行的代码不得引用任何 {@code net.minecraft.*} 类型（类加载边界，
 * 见 {@code ModsUi} 类 javadoc —— 首个屏幕事件到达时才允许下沉到 MC 逻辑）。
 * 仅为本仓库内置 UI 模块服务，<b>不是</b> mod 作者 API。
 */
public interface BrushModule {

    /** 模块名（日志与归因用；如 {@code "ui.mods"}）。 */
    String name();

    /** 安装（注册监听器等）；实现须幂等，重复调用为 no-op。 */
    void install();
}

package org.abstruck.brush.api;

import java.nio.file.Path;

import net.minecraft.client.Minecraft;

/**
 * mod 实机测试上下文（{@code --brush.mod-test} 模式，提案 {@code brush-inworld-mod-test-hook-proposal} §3 方案 A）。
 *
 * <p>由加载器在 {@code CLIENT_READY} 之后、游戏实例已就绪时提供给 {@link ModClientTest} 实现 ——
 * 把"进世界"的样板代码（首启辅助功能屏拦路、{@code CreateWorldScreen.openFresh} 反射触达
 * {@code onCreate}、"世界已就绪"需轮询服务端状态、截屏落盘）收编为统一上下文（提案 §2.4：
 * "这些是纯测试装置工程，每个 mod 重复实现毫无意义，适合加载器封装成测试上下文"）。
 *
 * <p><b>调用契约</b>：
 * <ul>
 *   <li>仅在 {@code --brush.mod-test} 运行中提供：常规运行 / 自检 / 实机探针模式下本上下文
 *       不构造、不派生 —— 零成本。</li>
 *   <li>实现类全部经加载器侧（{@code selftest} 源集的 {@code ModTestProbe}）构造；mod 实现
 *       只读使用，不得自行 new。</li>
 *   <li>方法线程语义 = 游戏主线程（{@code CLIENT_TICK} 监听器内被调用）；{@link #screenshot}
 *       与 {@link #awaitServerReady} 可阻塞主线程（加载器在自检退出点前调用，不与其他机制冲突）。</li>
 * </ul>
 *
 * <p><b>退出码协议（提案 §3，钉死）</b>：加载器保留 {@code bit 0..15}（{@code 0} = 全过、
 * {@code 1} = general fail、加载器自身的 mod-test 检查位）；{@code bit 16+}（值 {@code 65536}
 * 起）交 mod 自定测试项位组合。mod 实现若需要位组合，用 {@code 1 << 16} 起算 —— 与加载器保留位
 * 不重叠（现有 {@code Selftest} / {@code RealityProbe} 全部位 ≤ 256 = {@code bit 8}，远低于 16）。
 *
 * <p><b>MC 类型引用</b>：{@link #minecraft()} 返回 {@link Minecraft}（vanilla 类）—— 与本包
 * 其他 L2 接口（{@link org.abstruck.brush.api.BrushEvents} 引用 {@code Screen} /
 * {@code MinecraftServer} 等）同口径：惰性引用，三运行时官方名一致，零重映射。
 */
public interface ClientTestContext {

    /**
     * 当前 {@link Minecraft} 实例（客户端就绪后非 null；本上下文由加载器在 {@code CLIENT_READY}
     * 之后提供，调用时必然已构造）。mod 实现可用它读取世界 / 连接 / 屏幕等状态做断言。
     */
    Minecraft minecraft();

    /**
     * 建一个临时世界并等"可玩"（含关掉首启辅助功能屏等已知拦路屏）；返回后玩家已就位。
     *
     * <p>语义（提案 §2.4 样板收编）：
     * <ul>
     *   <li>若 {@code worldName} 非空且 {@code run/saves/<worldName>} 已存在 → 直接加入（复用既有世界）；</li>
     *   <li>否则经 {@code CreateWorldScreen.openFresh} + 反射 {@code onCreate} 创建新世界；</li>
     *   <li>首启的 {@code AccessibilityOnboardingScreen}（vanilla 26.1.2）会先于标题屏拦路 ——
     *       本方法检测并关闭（调 {@code onClose()}）后继续；</li>
     *   <li>等待"服务端 tick 已启动且集成服务器非 null"（即 {@link #awaitServerReady} 判定），
     *       返回后玩家已进世界。</li>
     * </ul>
     *
     * <p>失败即抛 {@code IllegalStateException}（可读原因），不静默降级 —— 同 {@code Selftest}
     * 口径（§14.1 不降低校验严格度）。
     *
     * @param worldName 世界名（{@code run/saves/<name>} 下的 Level 目录名）；{@code null} = 用默认
     *                  世界名（{@code "brush_mod_test"}，由加载器约定）
     */
    void quickPlayOrCreateWorld(String worldName);

    /**
     * 就绪判定：服务端 tick 已启动且集成服务器非 null（轮询 {@code Minecraft.getSingleplayerServer()}
     * + {@code level != null}；超时即返回 {@code false}，不抛 —— 调用方自行判定失败）。
     *
     * @param timeoutMs 最长等待毫秒（调用方通常传 {@code 120_000} = 120 秒，与实机探针同量级）
     * @return {@code true} = 在超时前就绪；{@code false} = 超时未就绪
     */
    boolean awaitServerReady(long timeoutMs);

    /**
     * 游戏目录（{@code Minecraft.gameDirectory}，S1 下 = {@code run/}）；mod 测试装置写产物
     * （截屏 / 世界 id 文件）用。
     */
    Path gameDirectory();

    /**
     * 统一截屏落盘（复用 {@code Screenshot.takeScreenshot} / {@code NativeImage}，提案 §3 "命名与
     * 目录由加载器约定"）：截图保存到 {@code <gameDirectory>/mod-test-screenshots/<name>.png}。
     *
     * <p>同步调用（主线程）；失败抛 {@code RuntimeException}（可读原因），不吞。
     *
     * @param name 截图名（不含 {@code .png}；非法字符由加载器清洗）
     */
    void screenshot(String name);
}

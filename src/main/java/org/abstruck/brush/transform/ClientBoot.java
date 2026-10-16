package org.abstruck.brush.transform;

import org.abstruck.brush.mod.ModBootstrap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * 客户端锚点统一编排（{@code client.run}）—— 客户端入口迟绑定（{@code entrypoints.client}）+
 * 客户端就绪派发，收拢为 <b>一条织入指令</b>的唯一入口（§10.1"每方法 1 注入点 / 1 条指令"）。
 *
 * <p><b>为什么复用 {@code client.run} 而非新增锚点</b>（docs/09 §7）：
 * <ol>
 *   <li>{@code ClientBootstrap#bootstrap} 由 {@code Main.main} 调用，<b>早于 Minecraft 实例
 *       构造</b> —— 以它为客户端入口锚点则 mod 拿不到客户端实例，且需新增注入点；</li>
 *   <li>{@code client.run}（{@code Minecraft#run()V} 的 HEAD）在三运行时均命中
 *       （T1/T2/T3 覆盖），且语义上正是"客户端实例已就绪、渲染侧 bootstrap 已完成、首个 tick
 *       之前"；</li>
 *   <li>复用 = 注入点总数与被注入类数不变，仅该点的织入指令由 {@code Dispatcher.fire(...)}
 *       改为本类的 {@code onClientRun()}（引导+派发一体，与 {@link EarlyBoot} 同款形态）。</li>
 * </ol></p>
 *
 * <p><b>顺序保证（结构性，非调度巧合）</b>：本方法<b>先</b>派发全部客户端入口
 * （{@link ModBootstrap#runClientEntrypoints()}），<b>再</b>派发 {@link HookEvents#CLIENT_RUN} ——
 * 因此在 {@code onInitializeClient()} 内注册的 {@code CLIENT_READY} 监听器<b>同批送达</b>
 * （不存在"注册晚于派发"的中途窗口，见 {@code api.BrushClientModInitializer} javadoc）。</p>
 *
 * <p><b>失败语义</b>：客户端入口派发失败（可读报告已由 {@link ModBootstrap} 打印）→
 * {@link IllegalStateException} 上抛（经 vanilla {@code Minecraft.run()} 冒泡，中止启动；
 * {@code CLIENT_RUN} 不派发）。不静默降级。</p>
 *
 * <p><b>分层</b>：本类不引用任何 MC / 宿主类型（与 {@code api} 层同一纪律）；依赖仅
 * JDK + log4j + {@link ModBootstrap}（游戏侧类空间）。</p>
 */
public final class ClientBoot {

    /** 游戏侧 log4j（与其余 transform 类同一 logger 体系）。 */
    private static final Logger LOGGER = LogManager.getLogger(ClientBoot.class);

    private ClientBoot() {
    }

    /**
     * {@code client.run} 锚点入口（{@code MinecraftMixin} 的 handler 调用）——
     * 派发全部客户端入口（迟绑定，见 {@link ModBootstrap#runClientEntrypoints}），
     * 随后派发 {@link HookEvents#CLIENT_RUN}。
     *
     * <p>失败语义见类 javadoc；每进程恰一次（{@code Minecraft#run()} 只调用一次，
     * 客户端入口派发侧另有幂等守卫）。
     */
    public static void onClientRun() {
        try {
            ModBootstrap.runClientEntrypoints();
        } catch (IOException e) {
            throw new IllegalStateException("[brush] client init failed at the client.run anchor ("
                    + HookEvents.CLIENT_RUN + "): " + e.getMessage(), e);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[brush] client boot: entrypoints done, dispatching " + HookEvents.CLIENT_RUN);
        }
        Dispatcher.fire(HookEvents.CLIENT_RUN);
    }
}

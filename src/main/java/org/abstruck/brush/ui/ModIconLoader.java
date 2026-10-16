package org.abstruck.brush.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.abstruck.brush.mod.ModBootstrap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组图标加载管线：默认图标（brush.png）与各 mod 自定义 icon 的解码（{@code NativeImage}）
 * → 纹理（{@code DynamicTexture}）→ 静态缓存。
 *
 * <p><b>默认图标来源</b>：主资源目录按原版/模组资源约定提供
 * （{@code src/main/resources/assets/brush/textures/gui/icon.png}，16x16），打包后即类路径
 * 资源。路径的单一来源 = 加载器自身元数据的 {@code icon} 字段（{@code /brush.mod.json}，
 * 与普通 mod 同格式同位置；见 {@link #DEFAULT_ICON_RESOURCE}）。{@code getResourceAsStream}
 * 经加载本类的 ClassLoader 解析，三运行时同一份实现（§9.3）—— 不经资源包系统，与 S2/S3
 * 宿主的资源包 / 图集 / 资源重载互不干扰。
 *
 * <p><b>mod 自定义 icon</b>：{@code brush.mod.json} 的可选 {@code icon} 字段 = jar 内条目路径
 * （如 {@code "icon.png"}）。任一环节失败（未声明 / 条目缺失 / 目录 / 解码失败 / IO）→ 回退
 * 默认图标 + 警告日志，绝不抛出、绝不崩 UI。未声明 icon 也回退默认图标（正常路径，不告警）。
 *
 * <p><b>纹理生命周期（不注册 TextureManager）</b>：注册进 TextureManager 的纹理会随资源重载
 * （F3+T）被 {@code close()} 且无人重建 —— 图标将永久失效。本类改为自己持有
 * {@link DynamicTexture}：绘制走 {@code extractor.blit(getTextureView(), getSampler(), ...)}
 * （blit 直接消费 GpuTextureView，不经过 TextureManager），重载免疫。进程级持有
 * 1 + N 张 16x16 RGBA（约 1KB/张），无泄漏累积。
 *
 * <p><b>线程与 GL 时机</b>：{@code DynamicTexture} 的构造器即创建 GpuTexture 并上传
 * （{@code getTextureView()} 在未创建时直接抛 IllegalStateException，非惰性）。因此全部加载
 * 发生在 {@link #ensureDefaultIcon()} / {@link #ensureLoaded(List)} —— 二者的调用点
 * （{@code ModsTitleButton.onLayout} 与 {@code ModsScreen.init}）都在渲染线程的屏幕生命周期
 * 内，GL 上下文就绪；绘制阶段（extract）只读缓存，不做 IO、不建纹理。
 *
 * <p><b>类加载边界</b>：本类引用 MC 类型，绝不被 {@link ModsUi} 引用（引导期零 MC 类型）；
 * 首次触及点 = 首个屏幕事件（与 {@link ModsTitleButton} 同款边界，见 {@code ModsUi} javadoc）。
 * 状态计数经 {@link ModsUi} 的零 MC 类型计数器上报（Selftest {@code ui.icons} 信息项）。
 */
public final class ModIconLoader {

    /**
     * 默认图标资源路径 —— 单一来源 = 加载器自身元数据 {@code /brush.mod.json} 的 {@code icon}
     * 字段（与普通 mod 同格式同位置；图标资源改位时只改资源与元数据，不存在常量副本）。
     * 元数据不可用 / icon 未声明时回退传统常量路径。
     */
    private static final String DEFAULT_ICON_RESOURCE = defaultIconResource();

    private static String defaultIconResource() {
        String icon = ModBootstrap.normalizeIconPath(ModBootstrap.loaderMetadata().icon);
        return icon.isEmpty() ? "/assets/brush/textures/gui/icon.png" : "/" + icon;
    }

    private static final Logger LOGGER = LogManager.getLogger(ModIconLoader.class);

    /**
     * mod id → 纹理（渲染线程独占，无需并发控制）。
     * 值可为 {@code null}（加载失败且默认图标也不可用）—— 显式缓存 null 防止逐帧重试 + 日志刷屏，
     * 故用 {@code containsKey} 判定而非 {@code computeIfAbsent}（后者不记录 null）。
     */
    private static final Map<String, IconTexture> MOD_ICONS = new HashMap<>();

    private static IconTexture defaultIcon;
    private static boolean defaultIconAttempted;

    /** 注册序号：为每张图标纹理生成会话内唯一的 Identifier（渲染线程独占，无需并发控制）。 */
    private static int iconSequence;

    private ModIconLoader() {
    }

    /**
     * 一张已上传的图标纹理。
     *
     * @param texture 纹理本体（{@code ModsScreen} 的直绘路径用它，见该处的 blit 调用）
     * @param id      <b>在 TextureManager 中的标识</b>（2026-10-15 新增）：标题界面淡入需要把 alpha
     *                作为**颜色参数**传进绘制调用（{@code ARGB.white(alpha)}），而带颜色的 blit 重载
     *                按 Identifier 解析纹理 —— 故纹理必须在 TextureManager 里注册。
     * @param width   纹理宽（blit 的 UV / texW 计算）
     * @param height  纹理高
     */
    public record IconTexture(DynamicTexture texture, Identifier id, int width, int height) { }

    /**
     * 默认图标（brush.png），懒加载 + 缓存；失败返回 {@code null}（绘制端跳过图标、按钮仍可点击），
     * 只告警一次。调用方须在渲染线程、GL 上下文就绪时调用。
     */
    public static IconTexture defaultIcon() {
        if (!defaultIconAttempted) {
            defaultIconAttempted = true;
            defaultIcon = loadDefaultIcon();
        }
        return defaultIcon;
    }

    /** 预载默认图标（{@code ModsTitleButton.onLayout} 调用点；失败仅告警，不影响按钮挂载）。 */
    public static void ensureDefaultIcon() {
        defaultIcon();
    }

    /** 预载全部 mod 图标 + 默认图标（{@code ModsScreen.init} 调用点）。 */
    public static void ensureLoaded(List<ModBootstrap.ModEntry> mods) {
        defaultIcon();
        for (ModBootstrap.ModEntry mod : mods) {
            forMod(mod);
        }
    }

    /**
     * 某 mod 的图标：自定义 icon 不可用时回退默认图标；结果（含回退与 null）按 mod id 缓存。
     *
     * <p>无 jar 的条目（Brush 自身的列表条目，见 {@code ModsScreen#displayMods}）恒用默认图标
     * （其元数据的 icon 声明即默认图标路径本身，声明与显示同源，见 {@link #DEFAULT_ICON_RESOURCE}），
     * 且<b>不占 id 缓存</b> —— 防止与 brush-mods/ 下恰好同名的 mod 互相污染图标。
     */
    public static IconTexture forMod(ModBootstrap.ModEntry mod) {
        if (mod.jar() == null) {
            return defaultIcon();
        }
        if (MOD_ICONS.containsKey(mod.id())) {
            return MOD_ICONS.get(mod.id());
        }
        IconTexture icon = loadModIcon(mod);
        MOD_ICONS.put(mod.id(), icon);
        return icon;
    }

    // ------------------------------------------------------------------
    // 加载实现
    // ------------------------------------------------------------------

    private static IconTexture loadDefaultIcon() {
        try (InputStream in = ModIconLoader.class.getResourceAsStream(DEFAULT_ICON_RESOURCE)) {
            if (in == null) {
                ModsUi.onDefaultIconFailed();
                LOGGER.warn("[brush] mods UI: default icon resource " + DEFAULT_ICON_RESOURCE
                        + " not found on the classpath (expected at src/main/resources/assets/brush/textures/gui/);"
                        + " buttons/cards render without an icon");
                return null;
            }
            IconTexture icon = upload("brush ui default icon", in);
            ModsUi.onDefaultIconLoaded(icon.width(), icon.height());
            LOGGER.info("[brush] mods UI: default icon loaded from " + DEFAULT_ICON_RESOURCE
                    + " (" + icon.width() + "x" + icon.height() + ")");
            return icon;
        } catch (IOException e) {
            ModsUi.onDefaultIconFailed();
            LOGGER.warn("[brush] mods UI: default icon " + DEFAULT_ICON_RESOURCE
                    + " could not be decoded: " + e);
            return null;
        }
    }

    private static IconTexture loadModIcon(ModBootstrap.ModEntry mod) {
        String iconPath = ModBootstrap.normalizeIconPath(mod.icon());
        if (iconPath.isEmpty()) {
            return defaultIcon();   // 未声明 icon：正常回退默认图，非错误
        }
        try (ZipFile zip = new ZipFile(mod.jar().toFile())) {
            ZipEntry entry = zip.getEntry(iconPath);
            if (entry == null || entry.isDirectory()) {
                ModsUi.onModIconFallback();
                LOGGER.warn("[brush] mods UI: mod '" + mod.id() + "': icon '" + mod.icon()
                        + "' not found in " + mod.jar().getFileName() + "; using the default icon");
                return defaultIcon();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                IconTexture icon = upload("brush mod icon: " + mod.id(), in);
                ModsUi.onModIconLoaded();
                return icon;
            }
        } catch (IOException e) {
            ModsUi.onModIconFallback();
            LOGGER.warn("[brush] mods UI: mod '" + mod.id() + "': icon '" + mod.icon()
                    + "' could not be read/decoded (" + e + "); using the default icon");
            return defaultIcon();
        }
    }

    /**
     * 解码 + 建纹理 + 上传 + <b>注册进 TextureManager</b>。{@code DynamicTexture} 构造器内即完成
     * createTexture + upload，故调用方必须已处于渲染线程且 GL 上下文就绪。
     *
     * <p><b>为什么注册</b>（2026-10-15，用户报"标题界面淡入时我们按钮的图标不淡入"）：原版的淡入是
     * {@code Screen#fadeWidgets} 遍历子控件调 {@code AbstractWidget#setAlpha(float)}，而 alpha 只能经
     * <b>绘制调用的颜色参数</b>传入（vanilla {@code AbstractButton.extractDefaultSprite} 即
     * {@code blitSprite(..., ARGB.white(alpha))}）。带颜色的纹理 blit 重载是
     * {@code blit(RenderPipeline, Identifier, x, y, u, v, w, h, texW, texH, color)} —— 按 Identifier
     * 从 TextureManager 解析<b>原始纹理</b>（同 {@code LogoRenderer} 画 logo 的做法），
     * 而 {@code blit(GpuTextureView, GpuSampler, ...)} 没有颜色参数，旧实现因此丢掉了 alpha。
     *
     * <p><b>用 register 而非 registerAndLoad</b>：动态纹理不是文件资源。已核实
     * {@code TextureManager.reload} 只调度 {@code ReloadableTexture}（即 registerAndLoad 那类），
     * 经 register 注册的非重载纹理<b>不参与资源重载、不会被关闭</b> ⇒ 会话内缓存安全。
     */
    private static IconTexture upload(String label, InputStream in) throws IOException {
        NativeImage image = NativeImage.read(in);
        DynamicTexture texture = new DynamicTexture(() -> label, image);
        texture.getTextureView();   // 显式取一次：明确的失败点（若上传异常，此处抛出并被上层捕获）
        Identifier id = Identifier.fromNamespaceAndPath("brush", "ui/icon/" + (++iconSequence));
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return new IconTexture(texture, id, image.getWidth(), image.getHeight());
    }
}

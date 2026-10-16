package org.abstruck.brush.tools;

import java.util.List;

/**
 * S3/T3 测试实例的宿主构件清单（url / sha1 / size 均为查证值，结论登记见
 * docs/13-宿主适配-NeoForge.md §2）：
 * <ul>
 *   <li>installer jar：maven.neoforged.net 官方制品（与 install_profile.json 中
 *       {@code net.neoforged:neoforge:26.1.2.114:installer} 条目一致）；</li>
 *   <li>sodium-neoforge：来自 Modrinth；
 *       许可 <b>Polyform-Shield-1.0.0</b> —— 仅本地测试使用，不分发（§14.4 第 5 条）。</li>
 * </ul>
 * 安装产物（patched client jar / universal jar / 26 条库）不在此清单：由官方安装器幂等产出，
 * 装配器按安装目录的 maven 布局解析（{@link NeoForgeHostHarness}）。
 * 本类只属于测试装置（tools 源集，不进产物 jar）。
 */
final class NeoForgeHostProfile {

    /** NeoForge 版本（与 universal jar 的 toml 一致）。 */
    static final String NEOFORGE_VERSION = "26.1.2.114";

    /** 官方安装器制品（maven.neoforged.net）。 */
    record Installer(String url, String fileName, String sha1, long size) { }

    /** T3 真实 NeoForge mod（Modrinth）；{@code project} 用于日志与文档可读。 */
    record Mod(String project, String url, String fileName, String sha1, long size) { }

    static final Installer INSTALLER = new Installer(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + NEOFORGE_VERSION
                    + "/neoforge-" + NEOFORGE_VERSION + "-installer.jar",
            "neoforge-" + NEOFORGE_VERSION + "-installer.jar",
            "5bad022e852b32ea5fd81e1e307bf6526d288309", 11320327L);

    static final List<Mod> MODS = List.of(
            new Mod("sodium",
                    "https://cdn.modrinth.com/data/AANobbMI/versions/zg4YQ9EL/sodium-neoforge-0.9.2%2Bmc26.1.2.jar",
                    "sodium-neoforge-0.9.2+mc26.1.2.jar",
                    "e03e21bf6553fc517241d59c2c116b8e5cc882f8", 1260562L));

    private NeoForgeHostProfile() { }
}

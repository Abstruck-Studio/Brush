package org.abstruck.brush.tools;

import java.util.List;

/**
 * S2/T2 测试实例的宿主构件清单（库清单 7 条 + 真实 Fabric mod；url / sha1 / size 均为查证值，
 * 来源：26.1.2 + loader 0.19.5 的 launcher profile 与 Modrinth API；结论登记见
 * docs/11-宿主预研.md 与 docs/12-宿主适配-Fabric.md）。
 * 本类只属于测试装置（tools 源集，不进产物 jar）。
 */
final class FabricHostProfile {

    /** Fabric Loader 版本（26.1.2 线，docs/11 §1）。 */
    static final String LOADER_VERSION = "0.19.5";

    /** launcher profile libraries（7 条，原序）。{@code size <= 0} = profile 未给出、跳过大小校验。 */
    record Lib(String url, String fileName, String sha1, long size) { }

    /** T2 真实 Fabric mod（Modrinth）。 */
    record Mod(String project, String url, String fileName, String sha1, long size) { }

    private static Lib maven(String path, String sha1, long size) {
        int slash = path.lastIndexOf('/');
        return new Lib("https://maven.fabricmc.net/" + path, path.substring(slash + 1), sha1, size);
    }

    static final List<Lib> LIBS = List.of(
            maven("org/ow2/asm/asm/9.10.1/asm-9.10.1.jar",
                    "ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236", 126151L),
            maven("org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar",
                    "8d49f14d51f632cb1d87c88d1ceaf50db0d8af1b", 35140L),
            maven("org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar",
                    "4229e4c55fd8e01c23f9fe9884075cc628aacc50", 74840L),
            maven("org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar",
                    "e244332a17564c1d1572449399a842de35881be2", 51958L),
            maven("org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar",
                    "7bb9d450e8d4cbf9f9e04096c44bbfe7fba80b15", 95628L),
            maven("net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar",
                    "5f66cc9f59b8efaa942155a3d5a30599bf6640dd", 1539080L),
            maven("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
                    "ff9e65cffca4a67f31523e1807fe0855940fcbfa", -1L));

    static final List<Mod> MODS = List.of(
            new Mod("fabric-api",
                    "https://cdn.modrinth.com/data/P7dR8mSH/versions/3dM0X6ou/fabric-api-0.155.3%2B26.1.2.jar",
                    "fabric-api-0.155.3+26.1.2.jar",
                    "16c30dfcceaed1ac3b6ac84cbd348a906dc5093e", 2521340L),
            new Mod("sodium",
                    "https://cdn.modrinth.com/data/AANobbMI/versions/tZQ3jqnf/sodium-fabric-0.9.2%2Bmc26.1.2.jar",
                    "sodium-fabric-0.9.2+mc26.1.2.jar",
                    "d107b02081262e243e80559192a2de86007155d6", 1940135L),
            // 标题界面按钮共存回归夹具（modmenu 18.0.2 = 26.1.2 线；其依赖
            // （fabric-*-api-v1 四模块）由上方 fabric-api 满足，无新增外部依赖）
            new Mod("modmenu",
                    "https://cdn.modrinth.com/data/mOgUt4GM/versions/ZJ6YTrMY/modmenu-18.0.2.jar",
                    "modmenu-18.0.2.jar",
                    "8fa5f0c6e844f44bfd836197b680ee6ea0006bcd", 859999L));

    private FabricHostProfile() { }
}

package org.abstruck.brush.host.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * S3 的 mod 类加载后备加载器：父 = 游戏 {@code ModuleClassLoader} 的原
 * fallback 加载器；自身在追加的 mod jar 中查找类与资源；jar 未命中时经模块加载器
 * （{@link #moduleLoader}，即游戏的 {@code TransformingClassLoader}）做<b>模块兜底</b>。
 *
 * <p>被安装进游戏加载器的 {@code fallbackClassLoader} 字段（经 {@link NeoForgeRuntimeBridge}），
 * 承担三件事：
 * <ol>
 *   <li><b>mod 类加载</b>：FML 的 {@code ModuleClassLoader.loadClass} 对"未命中任何模块的包"
 *       回退到此 —— 父优先（原 fallback 链、再上游），未命中时自 mod jar 定义；</li>
 *   <li><b>mixin 配置读取</b>：注册窗口期本加载器被临时设为 TcCL，
 *       {@code FMLMixinService.getResourceAsStream} 的资源回退（TcCL）经此命中 mod jar；</li>
 *   <li><b>CodeSource</b>：自 mod jar 定义的类附"来源 jar 的 file: URL"（与
 *       {@code BrushClassLoader} 同款做法 —— Mixin 的 ASM 版本探测经
 *       {@code CodeSource} 定位 jar 清单，jar:entry 形态会抛 FileSystemNotFoundException）。</li>
 * </ol>
 *
 * <p><b>模块兜底为什么必需</b>：由本加载器定义的 mod 类，其符号引用
 * （如 {@code org.abstruck.brush.api.BrushModInitializer}、{@code net.minecraft.*}）
 * 按 JVM 语义经<b>定义加载器</b>解析 —— 即经本类，而非模块感知的游戏加载器。若只按
 * 父链（原 fallback：一个普通 URLClassLoader，只见启动库）解析，模块类（Brush API / MC /
 * 其他 mod 模块）全部落空（{@code NoClassDefFoundError}）。因此 {@link #findClass} 在 jar
 * 未命中后向 {@code moduleLoader} 做一次 {@code Class.forName(...)}（load-time、不初始化）：
 * 模块包由它按 {@code packageLookup} 路由，非模块名它会再回退到本类 ——
 * 由<b>按类名去重的重入守卫</b>（{@link #moduleLookups}）打断该环：同一线程正在为类 X
 * 做模块兜底期间，重入的 X 请求直接 CNFE，环终止；X 之外的类（如嵌套 defineClass 期间
 * 解析的接口/父类引用）照常走模块兜底 —— 守卫<b>不能</b>粗化为
 * "本线程暂停一切模块兜底"。
 *
 * <p>类加载语义与 {@code ModClassLoader} 相同：默认 parent-first（{@link ClassLoader#loadClass}
 * 双亲委派），仅父链未命中时在 mod jar 内 {@link #findClass}。包定义（{@code definePackage}）
 * 按需补齐，避免 mod 代码经 {@code getPackage()} 取到 null。
 */
final class ModJarFallbackLoader extends ClassLoader {

    private final List<Path> jars = new CopyOnWriteArrayList<>();

    /** 模块兜底加载器（游戏 {@code TransformingClassLoader}）；null = 关闭兜底（不为 null 的构造路径不存在，防御性保留）。 */
    private final ClassLoader moduleLoader;

    /** 模块兜底重入守卫：线程 → 正在兜底解析的类名集（见类 javadoc"模块兜底"）。 */
    private final ThreadLocal<Set<String>> moduleLookups = ThreadLocal.withInitial(HashSet::new);

    ModJarFallbackLoader(ClassLoader parent, ClassLoader moduleLoader) {
        super(parent);
        this.moduleLoader = moduleLoader;
    }

    void addJar(Path jar) {
        jars.add(jar);
    }

    List<Path> jars() {
        return List.copyOf(jars);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String entryPath = name.replace('.', '/') + ".class";
        for (Path jar : jars) {
            byte[] bytes = readEntry(jar, entryPath);
            if (bytes == null) {
                continue;
            }
            definePackageIfNeeded(name, jar);
            ProtectionDomain domain = new ProtectionDomain(
                    new CodeSource(jarUrl(jar), (Certificate[]) null), null, this, null);
            return defineClass(name, bytes, 0, bytes.length, domain);
        }
        Class<?> fromModules = findClassInModules(name);
        if (fromModules != null) {
            return fromModules;
        }
        throw new ClassNotFoundException(name);
    }

    /**
     * 模块兜底（见类 javadoc"模块兜底"）：jar 未命中后向 {@code moduleLoader} 做一次
     * load-time 解析；重入守卫按类名去重，防 {@code TCL → fallback(本类) → TCL} 成环。
     *
     * @return 模块侧解析到的类；模块侧同样没有（或命中重入守卫）时 null（由调用方抛 CNFE）
     */
    private Class<?> findClassInModules(String name) {
        ClassLoader modules = this.moduleLoader;
        if (modules == null) {
            return null;
        }
        Set<String> inFlight = this.moduleLookups.get();
        if (!inFlight.add(name)) {
            // 同一线程正为 name 做模块兜底（且模块侧已把它打回本类）—— 环，落空
            return null;
        }
        try {
            return Class.forName(name, false, modules);
        } catch (ClassNotFoundException e) {
            return null;
        } finally {
            inFlight.remove(name);
        }
    }

    @Override
    protected URL findResource(String name) {
        for (Path jar : jars) {
            if (hasEntry(jar, name)) {
                return jarEntryUrl(jar, name);
            }
        }
        return null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        List<URL> found = new ArrayList<>();
        for (Path jar : jars) {
            if (hasEntry(jar, name)) {
                found.add(jarEntryUrl(jar, name));
            }
        }
        return Collections.enumeration(found);
    }

    /**
     * 枚举 jar 内全部含 {@code .class} 条目的包名（点分格式；跳过 {@code META-INF/} 与默认包）。
     * 用途：{@link NeoForgeRuntimeBridge} 把包名注册进游戏加载器的 {@code parentLoaders}
     * （FML 帧计算按包名回读类字节的通道；机理见桥接类 javadoc）。
     */
    static Set<String> packagesIn(Path jar) throws IOException {
        Set<String> packages = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
                    continue;
                }
                int slash = name.lastIndexOf('/');
                if (slash <= 0) {
                    continue;   // 默认包：FML 的 packageName() 为 null，无法经 parentLoaders 注册
                }
                packages.add(name.substring(0, slash).replace('/', '.'));
            }
        }
        return packages;
    }

    private void definePackageIfNeeded(String className, Path jar) {
        int dot = className.lastIndexOf('.');
        if (dot <= 0) {
            return;
        }
        String packageName = className.substring(0, dot);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        try {
            definePackage(packageName, null, null, null, null, null, null, jarUrl(jar));
        } catch (IllegalArgumentException ignored) {
            // 并发定义同类包：另一线程已先定义，正常竞争
        }
    }

    private static URL jarUrl(Path jar) {
        try {
            return jar.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("bad jar path: " + jar, e);
        }
    }

    private static URL jarEntryUrl(Path jar, String name) {
        try {
            // URI 构造（Java 25 起 new URL(String) 弃用）；条目名按 jar 约定为普通 ASCII 路径
            return java.net.URI.create("jar:" + jarUrl(jar) + "!/" + name).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalArgumentException("bad jar entry url: " + jar + "!/" + name, e);
        }
    }

    private static boolean hasEntry(Path jar, String name) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            return entry != null && !entry.isDirectory();
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readEntry(Path jar, String name) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }
}

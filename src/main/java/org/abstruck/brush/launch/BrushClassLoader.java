package org.abstruck.brush.launch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * 类加载器（P1.5）：加载 MC 客户端（26.1.2 本地 vanilla jar，未混淆直接加载）与全部库。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>可插入 ClassTransformer</b>：所有经本加载器定义的类字节都过一遍 {@link ClassTransformer}
 *       链（P2 注入层在此注册）</li>
 *   <li><b>parent = 平台类加载器</b>（仅 JDK）：与构建期 classpath（Gradle / 本项目的 gson / asm）
 *       完全隔离，避免构建依赖泄漏进 MC 的类空间</li>
 *   <li><b>parent-first 委托</b>（{@link URLClassLoader} 默认语义）：JDK 类由 parent 提供，
 *       MC 与库的类落入 {@link #findClass}</li>
 *   <li><b>parallel capable</b>：MC 在多线程上加载类（render / IO / sound），非并行加载器的
 *       全局锁会在交叉加载时形成死锁风险，必须注册</li>
 *   <li><b>逐类日志见证</b>：入口类与首批 {@code net.minecraft.*} 类的定义打印到控制台 ——
 *       启动路径经过本加载器的直接证据</li>
 * </ul>
 *
 * <p>日志规模控制：仅打印入口类 + 首批 {@value #FIRST_LOG_LIMIT} 个 {@code net.minecraft.*} 类，
 * 之后每 {@value #PROGRESS_EVERY} 个类打印一行进度（全量打印会造成数万行输出）。
 */
public final class BrushClassLoader extends URLClassLoader {

    private static final Logger LOGGER = LogManager.getLogger(BrushClassLoader.class);

    static {
        registerAsParallelCapable();
    }

    /**
     * 类加载期字节码变换器（P2 注入层的插口）。
     *
     * <p>约定：返回 {@code null} 表示未修改；返回数组则替换原字节。实现必须线程安全
     * （多线程并发定义类时会被并发调用）。
     */
    public interface ClassTransformer {

        /**
         * @param className  点分全限定类名（与 Mixin 的类名约定一致）
         * @param classBytes 原始类字节
         * @return 变换后的字节；{@code null} = 保持原样
         */
        byte[] transform(String className, byte[] classBytes);
    }

    /** 首批逐类日志的条数上限。 */
    private static final int FIRST_LOG_LIMIT = 25;

    /** 之后每定义这么多类打印一行进度。 */
    private static final int PROGRESS_EVERY = 2000;

    private final List<ClassTransformer> transformers = new CopyOnWriteArrayList<>();

    /**
     * 每来源一份的 {@link ProtectionDomain} 缓存（P1-2/3）：键 = {@link #sourceOf} 归一的来源 URL
     * （jar 根），条目数与类路径 jar 数同级。PD 不可变且与具体类无关（ClassLoader + CodeSource
     * 即全部内容），同 jar 的类共享同一实例 —— 逐类新建纯属浪费（URLClassLoader 亦按 CodeSource
     * 粒度缓存）。
     */
    private final Map<URL, ProtectionDomain> protectionDomains = new ConcurrentHashMap<>();

    /**
     * 自管来源 jar 句柄缓存（每来源恰开一次；{@link #close()} 统一释放）。
     * 不落入 {@code JarFileFactory} 全局缓存 —— 动机与代价见 {@link #findClass} 内注释。
     */
    private final Map<URL, JarFile> sourceJars = new ConcurrentHashMap<>();

    private final String witnessClass;
    private final AtomicInteger definedClasses = new AtomicInteger();
    private final AtomicInteger loggedClasses = new AtomicInteger();

    /**
     * @param classpath    类路径（jar 文件，本加载器独有；parent 已持有的 JDK 类不在此列）
     * @param parent       父加载器（P1.5 固定为平台类加载器）
     * @param witnessClass 入口类（点分名），其定义必须打印（验收证据）
     */
    public BrushClassLoader(List<Path> classpath, ClassLoader parent, String witnessClass) {
        super(classpath.stream().map(BrushClassLoader::toUrl).toArray(URL[]::new), parent);
        this.witnessClass = witnessClass;
    }

    private static URL toUrl(Path path) {
        try {
            return path.toAbsolutePath().normalize().toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("bad classpath entry: " + path, e);
        }
    }

    /** 注册类加载期变换器（P2 注入层使用）。 */
    public void addTransformer(ClassTransformer transformer) {
        transformers.add(transformer);
    }

    /**
     * 追加一个 mod jar 到类路径<b>末尾</b>（运行期由游戏侧 {@code StandaloneBridge}
     * 经 JDK 反射调用 —— 两侧不共享类身份，见该类 javadoc 的桥接约定）。
     *
     * <p>追加语义（不是前置）：vanilla jar 与游戏库已在前，同名条目不会被 mod 遮蔽；
     * 追加后 mod 类经本加载器的 {@link #findClass}（含注入管线）定义，且
     * {@code BrushMixinService} 的资源读取（配置 / mixin 类字节）经同一实例自动可见。
     *
     * <p>线程安全：{@link URLClassLoader#addURL} 内部同步；调用时机在 mod 加载期（单线程引导段），
     * 早于任何 mod 类的加载。
     */
    public void addModJar(Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) {
            throw new IOException("addModJar: not a regular file: " + jar);
        }
        addURL(toUrl(jar));
        LOGGER.info("[brush] BrushClassLoader appended mod jar: " + jar.getFileName());
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String resourcePath = name.replace('.', '/') + ".class";
        URL url = findResource(resourcePath);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }
        URL source = sourceOf(url);
        byte[] bytes;
        if ("jar".equals(url.getProtocol())) {
            // 自管 JarFile 句柄读字节（P1-2/3 附带修复）：url.openStream() 默认把来源 jar 的句柄
            // 留在 JarFileFactory 全局缓存（loader.close() 放不掉 —— Windows 上文件被钉住），
            // 而 useCaches(false) 则每次读都新开 JarFile（对 MC 大 jar = 每类一次中央目录解析，
            // 实测把启动从 ~30s 拖到 ~8 分钟）。自管缓存 = 每 jar 恰开一次 + close() 显式释放。
            bytes = readEntry(source, resourcePath, name);
        } else {
            // 目录类路径（罕见）：直读，不驻留句柄
            try {
                URLConnection connection = url.openConnection();
                connection.setUseCaches(false);
                try (InputStream in = connection.getInputStream()) {
                    bytes = in.readAllBytes();
                }
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
        for (ClassTransformer transformer : transformers) {
            byte[] transformed = transformer.transform(name, bytes);
            if (transformed != null) {
                bytes = transformed;
            }
        }
        logDefine(name);
        definePackageIfNeeded(name, source);
        // 附 CodeSource（位置 = 来源 jar 本身的 file: URL）：部分库经 getProtectionDomain()
        // .getCodeSource() 定位自身。不能直接用 findResource 的返回值 —— 对 jar 内条目它是
        // "jar:file:...!/entry" 形态，Mixin 的 ASM 版本探测（MainAttributes.getNioAttributes ->
        // Paths.get(uri)）对此抛 FileSystemNotFoundException（zipfs 里没有该 jar 的 FileSystem），
        // 版本回退为 0 导致 mixin 的兼容级别检查被拒；
        // URLClassLoader 的标准行为同样是位置 = 来源 jar 的 file: URL（JarLoader 构造 CodeSource 时）。
        // （P1-2/3）ProtectionDomain 按来源缓存复用，不逐类新建。
        ProtectionDomain domain = protectionDomains.computeIfAbsent(source, src ->
                new ProtectionDomain(new CodeSource(src, (Certificate[]) null), null, this, null));
        return defineClass(name, bytes, 0, bytes.length, domain);
    }

    /**
     * 从来源 jar（自管缓存句柄）读条目字节：每 jar 恰开一次、{@link #close()} 统一释放 ——
     * 既不把句柄留在 {@code JarFileFactory} 全局缓存，也不每次重开（见 {@link #findClass} 注释）。
     */
    private byte[] readEntry(URL source, String entryPath, String className) throws ClassNotFoundException {
        JarFile jar;
        try {
            jar = sourceJar(source);
        } catch (IOException e) {
            throw new ClassNotFoundException(className, e);
        }
        ZipEntry entry = jar.getEntry(entryPath);
        if (entry == null) {
            throw new ClassNotFoundException(className + " (entry " + entryPath + " not found in " + source + ")");
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(className, e);
        }
    }

    /**
     * 取（或打开并缓存）来源 jar 句柄。打开失败 = {@link IOException}（由调用方转
     * {@link ClassNotFoundException} 或按无 Manifest 处理）；putIfAbsent 竞争失败方关闭自身多余句柄。
     */
    private JarFile sourceJar(URL source) throws IOException {
        JarFile cached = sourceJars.get(source);
        if (cached != null) {
            return cached;
        }
        JarFile opened;
        try {
            opened = new JarFile(Paths.get(source.toURI()).toFile());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("cannot derive a jar file path from " + source + ": " + e, e);
        }
        JarFile raced = sourceJars.putIfAbsent(source, opened);
        if (raced != null) {
            opened.close();
            return raced;
        }
        return opened;
    }

    /**
     * 关闭：先释放全部自管来源 jar 句柄（P1-2/3 附带修复 —— 默认 URL 缓存路径下句柄引用
     * 归 {@code JarFileFactory} 管理，close 后文件仍被钉住；自管句柄随 close 即放行，
     * Windows 上文件可立即删除 / 替换），再走 {@link URLClassLoader#close()}。
     */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (JarFile jar : sourceJars.values()) {
            try {
                jar.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;   // 继续关其余句柄；末尾抛出首个失败
                }
            }
        }
        sourceJars.clear();
        try {
            super.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 求 {@link #findResource} 结果 URL 对应的"来源根"：jar 内条目 → jar 本身的 file: URL。
     * 非 jar 条目（本加载器的目录类路径）保持原样 —— 尚无依赖该场景的调用方。
     */
    private static URL sourceOf(URL classUrl) {
        if (!"jar".equals(classUrl.getProtocol())) {
            return classUrl;
        }
        URLConnection connection;
        try {
            connection = classUrl.openConnection();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open connection for " + classUrl, e);
        }
        if (connection instanceof JarURLConnection jarConnection) {
            return jarConnection.getJarFileURL();
        }
        throw new IllegalStateException("jar: URL did not yield a JarURLConnection: " + classUrl);
    }

    /**
     * 按来源 jar 定义包（P1-2/3）：本类的 {@link #findClass} 直接走
     * {@code defineClass(name, bytes, off, len, domain)}，绕过了 {@link URLClassLoader} 标准路径中
     * 依 jar Manifest 定义包（规格 / 实现版本属性、Sealed 判定）的那一步 —— 不补这一步，
     * 经本加载器定义的类的 {@code Class.getPackage()} 恒为 null，依赖包元数据的库会行为异常。
     *
     * <p>幂等 / 并发：{@link #getDefinedPackage} 判重；并发定义同一包时
     * {@link #definePackage(String, Manifest, URL)} 抛 {@link IllegalArgumentException}
     * （并行加载器下的正常竞争，吞掉）。
     */
    private void definePackageIfNeeded(String className, URL source) {
        int dot = className.lastIndexOf('.');
        if (dot <= 0) {
            return;   // 默认包：Java 不允许为默认包定义 Package
        }
        String packageName = className.substring(0, dot);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        Manifest manifest = null;
        if ("file".equals(source.getProtocol())) {
            try {
                manifest = sourceJar(source).getManifest();   // 句柄复用自管缓存（同 jar 不重复打开 / 不钉文件）
            } catch (IOException | IllegalArgumentException e) {
                manifest = null;   // 目录类路径 / 非法 jar：无 Manifest，按无属性包定义
            }
        }
        try {
            if (manifest != null) {
                definePackage(packageName, manifest, source);   // 标准 URLClassLoader 语义（含 Sealed 判定）
            } else {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        } catch (IllegalArgumentException ignored) {
            // 并发定义同类包：另一线程已先定义，正常竞争
        }
    }

    private void logDefine(String name) {
        boolean witness = name.equals(witnessClass);
        int total = definedClasses.incrementAndGet();
        if (witness || (name.startsWith("net.minecraft.") && loggedClasses.incrementAndGet() <= FIRST_LOG_LIMIT)) {
            LOGGER.info("[brush] BrushClassLoader defines " + name + (witness ? "   <-- entry class" : "")
                    + "  [" + Thread.currentThread().getName() + "]");
        }
        if (total % PROGRESS_EVERY == 0) {
            LOGGER.info("[brush] BrushClassLoader: " + total + " classes defined via this loader so far");
        }
    }
}

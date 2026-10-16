package org.abstruck.brush.mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * mod jar 的平台桩预处理（{@code api.BrushExpectPlatform} 的机制实现）。
 *
 * <p><b>做什么</b>：扫描 mod jar 中带 {@code @BrushExpectPlatform} 的静态桩方法，把其方法体
 * <b>重写为对平台实现类的委托调用</b>（{@code ALOAD 各参数 → INVOKESTATIC 实现类方法 → xRETURN}，
 * 直线代码，{@code COMPUTE_MAXS} 足够、无栈帧重算），写出一个预处理副本到缓存目录；
 * 原 jar 不被改动（只读纪律）。无桩方法的 mod <b>不产生任何副本</b>（零开销路径）。
 *
 * <p><b>校验（全部硬错误，实例化任何入口之前）</b>：
 * <ul>
 *   <li>桩方法必须 static、且所在类不是接口 / 注解（桩需要方法体可替换）；</li>
 *   <li><b>当前运行时</b>的实现类 {@code <桩类全名>Impl<suffix>}（suffix 见
 *       {@link RuntimeKind#implSuffix()}）必须在同一 jar 内，且声明同名同签名的 static 方法 ——
 *       缺失即加载失败（不静默回退，见 {@code api.BrushExpectPlatform} javadoc 的缺失语义）。</li>
 * </ul>
 *
 * <p><b>副本复用</b>：副本路径 {@code <cache>/<modId>-<RuntimeKind>.jar}，判据 = <b>源 jar 内容
 * SHA-256 旁车</b>（{@code <prepared>.sha256}，P1-6）—— mtime 在复制 / 检出 / 触碰后失真
 * （内容未变仍重建，或内容已变而被误复用），内容哈希没有这个盲区。桩扫描每启动必做
 * （mod 规模 O(ms)），重写与写盘才走缓存；源内容变更即重建。
 * 多运行时实例的缓存目录互相独立（S1 run/brush-cache / S2 run/fabric/brush-cache /
 * S3 run/neoforge/brush-cache），副本按运行时后缀区分，互不串用。
 *
 * <p><b>已知限制（如实登记）</b>：{@code META-INF/} 下的类条目（如 multi-release jar 的
 * {@code versions/}）不扫描不重写 —— 现实中的 Brush mod 不打包多版本类，出现时按普通条目
 * 原样复制；注解为 {@code CLASS} 保留，运行时不可见，零 footprint。
 */
public final class ModJarPreparer {

    private static final Logger LOGGER = LogManager.getLogger(ModJarPreparer.class);

    /** 桩注解的描述符（与 {@code api.BrushExpectPlatform} 全名锁定）。 */
    private static final String ANNOTATION_DESC = "Lorg/abstruck/brush/api/BrushExpectPlatform;";

    private ModJarPreparer() {
    }

    /**
     * 按需预处理 mod jar（见类 javadoc）。
     *
     * @return 运行时应使用的 jar 路径：无桩方法 = 原 jar；有 = 缓存内预处理副本
     * @throws IOException 校验失败（消息可读，自解释）或 IO / 字节码解析失败
     */
    public static Path prepare(DiscoveredMod mod, RuntimeKind kind, Path cacheDir) throws IOException {
        Path source = mod.jar();
        Path prepared = cacheDir.resolve(mod.metadata().id + "-" + kind + ".jar");
        Path hashFile = cacheDir.resolve(prepared.getFileName() + ".sha256");

        List<String> problems = new ArrayList<>();
        try (ZipFile zip = new ZipFile(source.toFile())) {
            // 第一遍：找出带桩注解的方法（按类分组）
            List<StubClass> stubs = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
                    continue;
                }
                ClassReader reader = new ClassReader(readAllBytes(zip, entry));
                CollectingVisitor collector = new CollectingVisitor();
                try {
                    reader.accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (RuntimeException e) {
                    throw new IOException("class entry " + name + " cannot be parsed as bytecode: " + e, e);
                }
                if (!collector.stubs.isEmpty()) {
                    stubs.add(new StubClass(name, reader.getClassName(), collector.classAccess,
                            collector.stubs));
                }
            }

            if (stubs.isEmpty()) {
                return source;   // 零桩：原 jar 直用（也不写副本 —— 每次启动重扫，mod 规模下 O(ms)）
            }

            // 副本复用（P1-6）：判据 = 源 jar 内容哈希（旁车 <prepared>.sha256），不是 mtime ——
            // 复制 / 检出 / 触碰都会改 mtime 而不改内容（假重建），或反之（误复用陈旧副本）。
            String sourceHash = sha256(source);
            if (Files.isRegularFile(prepared) && sourceHash.equals(readSidecarOrNull(hashFile))) {
                LOGGER.info("[brush]   prepared jar reused: " + prepared.getFileName()
                        + " (runtime " + kind + ", source sha256 " + sourceHash.substring(0, 12) + "...)");
                return prepared;
            }

            // 校验 + 解析实现目标（对全部桩先收集问题，一次报全）
            Set<String> rewrittenEntries = new HashSet<>();
            int bound = 0;
            for (StubClass stubClass : stubs) {
                validateClass(stubClass, problems);
                for (StubMethod stub : stubClass.methods) {
                    if (stub.hasProblem) {
                        continue;   // 该方法的体问题已记录（静态检查失败），跳过实现解析
                    }
                    String implInternal = stubClass.internalName + "Impl" + kind.implSuffix();
                    String implEntry = implInternal + ".class";
                    ZipEntry implZip = zip.getEntry(implEntry);
                    if (implZip == null || implZip.isDirectory()) {
                        problems.add(describe(stubClass, stub) + ": platform implementation class "
                                + implInternal.replace('/', '.') + " not found in the mod jar (runtime "
                                + kind + "; expected <stub class>Impl" + kind.implSuffix() + ")");
                        continue;
                    }
                    if (!hasStaticMethod(zip, implZip, stub.name, stub.desc)) {
                        problems.add(describe(stubClass, stub) + ": class "
                                + implInternal.replace('/', '.') + " exists but declares no static method "
                                + stub.name + stub.desc + " with the matching signature");
                        continue;
                    }
                    stub.implInternal = implInternal;
                    rewrittenEntries.add(stubClass.entryName);
                    bound++;
                }
            }
            if (!problems.isEmpty()) {
                throw new IOException(String.join("; ", problems));
            }

            // 第二遍：重写受影响类的桩方法体
            Files.createDirectories(cacheDir);
            Path tmp = cacheDir.resolve(prepared.getFileName() + ".tmp");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry entry = entries.nextElement();
                    ZipEntry copy = new ZipEntry(entry.getName());
                    out.putNextEntry(copy);
                    byte[] bytes = rewrittenEntries.contains(entry.getName())
                            ? rewriteClass(readAllBytes(zip, entry), stubsFor(stubs, entry.getName()))
                            : readAllBytes(zip, entry);
                    out.write(bytes);
                    out.closeEntry();
                }
            }
            Files.move(tmp, prepared, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // 旁车哈希（.tmp + REPLACE_EXISTING 落位，同副本写模式）：写于副本落位<b>之后</b> ——
            // 若在两者之间崩溃，无旁车 = 下次判缓存未命中而重建（安全方向）；反序会留下错配哈希。
            Path hashTmp = cacheDir.resolve(hashFile.getFileName() + ".tmp");
            Files.writeString(hashTmp, sourceHash + System.lineSeparator());
            Files.move(hashTmp, hashFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[brush]   prepared mod jar: " + source.getFileName() + " -> " + prepared
                    + " (" + bound + " platform stub method(s) bound for runtime " + kind + ")");
            return prepared;
        }
    }

    // ------------------------------------------------------------------
    // 扫描 / 校验
    // ------------------------------------------------------------------

    /** 一个类中的桩方法集合。 */
    private static final class StubClass {

        final String entryName;
        final String internalName;
        final int classAccess;
        final List<StubMethod> methods;

        StubClass(String entryName, String internalName, int classAccess, List<StubMethod> methods) {
            this.entryName = entryName;
            this.internalName = internalName;
            this.classAccess = classAccess;
            this.methods = methods;
        }
    }

    /** 单个桩方法（{@code implInternal} 在实现解析成功后填充）。 */
    private static final class StubMethod {

        final String name;
        final String desc;
        final int access;
        boolean hasProblem;
        String implInternal;

        StubMethod(String name, String desc, int access) {
            this.name = name;
            this.desc = desc;
            this.access = access;
        }
    }

    /** 第一遍访问器：收集带注解的方法（跳过类自身之外的无关事件）。 */
    private static final class CollectingVisitor extends ClassVisitor {

        int classAccess;
        final List<StubMethod> stubs = new ArrayList<>();

        CollectingVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            classAccess = access;
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.AnnotationVisitor visitAnnotation(String annotationDescriptor,
                        boolean visible) {
                    // 参数名刻意避开外层 methodDescriptor —— 这里是注解描述符，不是方法描述符
                    if (ANNOTATION_DESC.equals(annotationDescriptor)) {
                        stubs.add(new StubMethod(methodName, methodDescriptor, access));
                    }
                    return null;   // 注解内容不需要
                }
            };
        }
    }

    private static void validateClass(StubClass stubClass, List<String> problems) {
        if ((stubClass.classAccess & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0) {
            problems.add(stubClass.internalName.replace('/', '.')
                    + ": @BrushExpectPlatform methods cannot live in an interface or annotation type"
                    + " (a stub needs a replaceable method body)");
            for (StubMethod stub : stubClass.methods) {
                stub.hasProblem = true;
            }
            return;
        }
        for (StubMethod stub : stubClass.methods) {
            if ((stub.access & Opcodes.ACC_STATIC) == 0) {
                problems.add(describe(stubClass, stub) + ": @BrushExpectPlatform requires a static method");
                stub.hasProblem = true;
            } else if ((stub.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                problems.add(describe(stubClass, stub) + ": @BrushExpectPlatform requires a regular method"
                        + " with a body (not abstract/native)");
                stub.hasProblem = true;
            }
        }
    }

    private static String describe(StubClass stubClass, StubMethod stub) {
        return "class " + stubClass.internalName.replace('/', '.') + ": @BrushExpectPlatform method "
                + stub.name + stub.desc;
    }

    /** 实现类中是否存在同名同签名的 static 方法（解析失败 = 视为不存在，随后报可读错误）。 */
    private static boolean hasStaticMethod(ZipFile zip, ZipEntry implEntry, String name, String desc)
            throws IOException {
        ClassReader reader = new ClassReader(readAllBytes(zip, implEntry));
        boolean[] found = {false};
        try {
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                        String signature, String[] exceptions) {
                    if (methodName.equals(name) && descriptor.equals(desc)
                            && (access & Opcodes.ACC_STATIC) != 0) {
                        found[0] = true;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            throw new IOException("implementation class " + implEntry.getName()
                    + " cannot be parsed as bytecode: " + e, e);
        }
        return found[0];
    }

    // ------------------------------------------------------------------
    // 重写
    // ------------------------------------------------------------------

    /** 第二遍：把指定类的桩方法体替换为委托调用（其余方法原样保留）。 */
    private static byte[] rewriteClass(byte[] bytes, List<StubClass> stubs) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Set<String> targets = new HashSet<>();
        for (StubClass stubClass : stubs) {
            for (StubMethod stub : stubClass.methods) {
                if (stub.implInternal != null) {
                    targets.add(stub.name + stub.desc);
                }
            }
        }
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!targets.contains(name + descriptor)) {
                    return mv;
                }
                String implInternal = null;
                for (StubClass stubClass : stubs) {
                    for (StubMethod stub : stubClass.methods) {
                        if (stub.name.equals(name) && stub.desc.equals(descriptor)
                                && stub.implInternal != null) {
                            implInternal = stub.implInternal;
                        }
                    }
                }
                return new StubBodyRewriter(mv, implInternal, name, descriptor);
            }
        }, 0);
        return writer.toByteArray();
    }

    /**
     * 桩方法体替换器：吞掉原方法体（桩实现体是 mod 作者写的占位代码，加载期语义即被替换），
     * 转发注解 / 参数等非代码事件，在 {@code visitEnd} 写出委托调用体。
     */
    private static final class StubBodyRewriter extends MethodVisitor {

        private final String implInternal;
        private final String name;
        private final String desc;

        StubBodyRewriter(MethodVisitor mv, String implInternal, String name, String desc) {
            super(Opcodes.ASM9, mv);
            this.implInternal = implInternal;
            this.name = name;
            this.desc = desc;
        }

        // 原方法体的全部代码事件：吞掉（见类 javadoc）

        @Override
        public void visitCode() {
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        }

        @Override
        public void visitInsn(int opcode) {
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                boolean isInterface) {
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
        }

        @Override
        public void visitLabel(org.objectweb.asm.Label label) {
        }

        @Override
        public void visitLdcInsn(Object value) {
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt,
                org.objectweb.asm.Label... labels) {
        }

        @Override
        public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                org.objectweb.asm.Label[] labels) {
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        }

        @Override
        public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                org.objectweb.asm.Label handler, String type) {
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                org.objectweb.asm.Label start, org.objectweb.asm.Label end, int index) {
        }

        @Override
        public void visitLineNumber(int line, org.objectweb.asm.Label start) {
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
        }

        @Override
        public void visitEnd() {
            mv.visitCode();
            int slot = 0;
            for (Type argument : Type.getArgumentTypes(desc)) {
                mv.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
                slot += argument.getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, implInternal, name, desc, false);
            mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0);   // COMPUTE_MAXS 重算
            mv.visitEnd();
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static List<StubClass> stubsFor(List<StubClass> stubs, String entryName) {
        List<StubClass> out = new ArrayList<>(1);
        for (StubClass stubClass : stubs) {
            if (stubClass.entryName.equals(entryName)) {
                out.add(stubClass);
            }
        }
        return out;
    }

    private static byte[] readAllBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /**
     * 计算文件内容的 SHA-256（十六进制小写，{@link HexFormat} 格式化；流式读取，64 KiB 缓冲）。
     * 用途：{@link #prepare} 的副本缓存判据（P1-6）。
     */
    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandated by the JVM spec but unavailable", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 读取旁车哈希；缺失 / 不可读 = null（视为缓存未命中，重建 —— 不因缓存文件损坏中止启动）。 */
    private static String readSidecarOrNull(Path hashFile) {
        try {
            return Files.readString(hashFile).strip();
        } catch (IOException e) {
            return null;
        }
    }
}

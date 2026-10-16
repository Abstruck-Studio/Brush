package org.abstruck.brush.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * L2 平台特定逃生舱（Architectury {@code @ExpectPlatform} 模式的项目内等价物）。
 *
 * <p><b>契约</b>：标注在<b>静态桩方法</b>上（类不得是接口 / 抽象类）。桩方法的方法体在
 * 加载期被替换为对平台实现类的委托调用；mod 代码<b>照常直接调用桩方法</b>，
 * 在三种运行时下得到各自平台的行为：
 *
 * <pre>{@code
 * public final class Platform {
 *     @BrushExpectPlatform
 *     public static String loaderName() { throw new AssertionError("replaced at load time"); }
 * }
 * // 平台实现（同包、名字 = 桩类名 + 后缀）：
 * public final class PlatformImplBrush { public static String loaderName() { return "Brush"; } }
 * public final class PlatformImplFabric  { public static String loaderName() { return "Fabric"; } }
 * public final class PlatformImplNeoForge{ public static String loaderName() { return "NeoForge"; } }
 * }</pre>
 *
 * <p><b>实现类命名</b>：{@code <桩类全名>Impl} + {@code Brush} / {@code Fabric} / {@code NeoForge}
 * （后缀即 {@code mod.RuntimeKind.implSuffix()}）。实现类必须声明与桩方法<b>同名同签名</b>的
 * static 方法。
 *
 * <p><b>缺失语义（硬失败）</b>：mod 加载期（实例化任何入口之前）预处理该 mod 的 jar：
 * 对<b>当前运行时</b>找不到对应实现类或方法 = 加载失败，报错指明缺哪个类 / 哪个方法 ——
 * 不允许静默回退（静默回退会让"三平台一致"变成运行时惊喜）。
 * 只实现部分平台的 mod 是合法的，但在缺少实现的运行时上<b>加载即失败</b>。
 *
 * <p><b>运行时成本</b>：委托调用是普通 {@code invokestatic}（编译期已定名的直接调用），
 * 无反射、无查表。注解本身 {@link RetentionPolicy#CLASS} 保留（运行时不可见，零 footprint）。
 *
 * <p><b>限制</b>：桩方法必须 static；参数 / 返回值类型必须三运行时类空间中同名可及
 * （三运行时统一官方名 —— 直接用官方名类型即可）；恒为空 body 的实现不受支持
 * （必须给出真实实现类的委托）。预处理副本的落盘与复用规则见 {@code mod.ModJarPreparer}。
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface BrushExpectPlatform {
}

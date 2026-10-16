package org.abstruck.brush.api;

import org.abstruck.brush.transform.Dispatcher;

/**
 * 监听器注册句柄（{@link Event#register} 的返回值）：注销单个监听器。
 *
 * <p>注销幂等；注销后 {@link #isActive()} 恒为 false，且该监听器不再被派发
 * —— 通过 {@link EventRegistration} 暴露的能力是"注销"，注册顺序与开关属内部实现
 * （需要时再按真实需求开放，L2 契约宁小勿全）。
 *
 * <p>{@link #close()} 等价于 {@link #unregister()}（支持 try-with-resources）。
 */
public final class EventRegistration implements AutoCloseable {

    private final Dispatcher.Registration registration;

    EventRegistration(Dispatcher.Registration registration) {
        this.registration = registration;
    }

    /** 注销该监听器（幂等；重复调用无副作用）。 */
    public void unregister() {
        registration.unregister();
    }

    /** 该监听器当前是否仍会被派发（注销后恒为 false）。 */
    public boolean isActive() {
        return registration.isEnabled();
    }

    @Override
    public void close() {
        unregister();
    }
}

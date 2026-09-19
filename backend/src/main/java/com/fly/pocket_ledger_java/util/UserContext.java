package com.fly.pocket_ledger_java.util;

/**
 * 基于 ThreadLocal 的当前登录用户上下文。
 * 拦截器在校验通过后写入，请求结束时由拦截器清理。
 * 业务代码（Controller/Service）任何位置一行即可拿到当前用户，无需再传参或查 token。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        CURRENT_USER.set(user);
    }

    public static LoginUser get() {
        return CURRENT_USER.get();
    }

    /**
     * 只想要 userId 时的便捷方法。
     */
    public static Long getUserId() {
        LoginUser user = CURRENT_USER.get();
        return user == null ? null : user.getId();
    }

    /**
     * 必须在请求结束时调用：Tomcat 用线程池复用线程，
     * 不清理的话，下一个落到这条线程的请求可能"继承"上一个用户的身份（串号事故）。
     */
    public static void clear() {
        CURRENT_USER.remove();
    }
}

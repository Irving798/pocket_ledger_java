package com.fly.pocket_ledger_java.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.util.JwtUtils;
import com.fly.pocket_ledger_java.util.LoginUser;
import com.fly.pocket_ledger_java.util.UserContext;
import com.fly.pocket_ledger_java.vo.ApiResponse;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 基于 JWT 的登录认证拦截器，是受保护 Controller 之前的统一身份校验入口。
 *
 * <p>它在一次同步 HTTP 请求中的主要执行顺序如下：</p>
 * <ol>
 *     <li>读取请求头中的 {@code Authorization: Bearer <token>}；</li>
 *     <li>对 JWT 进行验签、过期时间检查和载荷解析；</li>
 *     <li>认证成功后，把解析出的当前用户保存到 {@link UserContext}；</li>
 *     <li>Controller 和 Service 在本次请求中可通过 {@link UserContext} 获取当前用户；</li>
 *     <li>请求处理完成后清理用户上下文，避免 Tomcat 线程复用造成身份串号。</li>
 * </ol>
 *
 * <p>免登录路径不在本类中硬编码，而是在 {@code auth.ignore-urls} 中配置，并由
 * {@code WebMvcConfig} 在注册本拦截器时排除。因此，进入本类的 Controller 请求
 * 原则上都属于需要登录的请求。</p>
 *
 * <p>本类只负责“请求是否具有有效登录身份”，不负责校验用户名和密码、用户角色、
 * 业务权限或数据归属。用户名和密码只在登录接口中校验。</p>
 *
 * <p>所有认证失败场景（未携带 token、请求头格式错误、token 过期或无效）统一返回
 * HTTP 401 和“未登录或登录已失效”。具体失败原因只记录在服务端 warn 日志中，
 * 避免向客户端暴露不必要的认证细节。</p>
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** Authorization 请求头必须使用的固定前缀，末尾包含一个空格。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 负责使用服务端密钥验证 JWT，并从载荷中解析当前用户。 */
    private final JwtUtils jwtUtils;

    /** 负责把统一的认证失败对象序列化为 JSON 响应。 */
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtUtils jwtUtils, ObjectMapper objectMapper) {
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        /*
         *跨域OPTIONS 预检请求直接放行
         */
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        /*
         *非controller方法直接放行
         */
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        /*
         * 第 1 步：读取并检查 Authorization 请求头。
         *
         * 合法格式为 "Bearer <token>"。前缀区分大小写，并且 Bearer 后必须有空格。
         * 请求头缺失或格式不符合约定时，直接写入 401 响应并返回 false。
         * 返回 false 会终止当前处理链，Controller 不会被执行。
         */
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            log.warn("认证失败：缺少或格式非法的 Authorization 头 [{} {}]",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }
        String token = authorization.substring(BEARER_PREFIX.length());

        /*
         * 第 2 步：验证并解析 JWT。
         *
         * parseToken 会校验 JWT 签名、格式和过期时间，并从载荷中读取 userId、username，
         * 组装成 LoginUser。过期 token 和其他无效 token 对客户端统一返回 401，但服务端
         * 使用不同日志记录失败原因，便于定位问题。
         */
        LoginUser loginUser;
        try {
            loginUser = jwtUtils.parseToken(token);
        } catch (ExpiredJwtException expired) {
            log.warn("认证失败：token 已过期 [{} {}]", request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return false;
        } catch (JwtException | IllegalArgumentException invalid) {
            log.warn("认证失败：token 无效 [{} {}]", request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }

        /*
         * 第 3 步：建立本次请求的用户上下文并放行。
         *
         * UserContext 内部使用 ThreadLocal，使并发请求各自保存自己的 LoginUser，互不
         * 覆盖。返回 true 后 Spring MVC 才会继续调用目标 Controller。
         */
        UserContext.set(loginUser);
        return true;
    }

    /**
     * 在一次请求完成后清理当前线程保存的用户身份。
     *
     * <p>当本拦截器的 {@link #preHandle(HttpServletRequest, HttpServletResponse, Object)}
     * 返回 true 后，Spring MVC 会在请求处理完成时回调本方法。即使 Controller 或
     * Service 抛出异常，通常也会进入该回调，异常对象可通过 {@code ex} 参数取得。</p>
     *
     * <p>Tomcat 会复用工作线程。如果不调用 {@link UserContext#clear()}，同一线程下次
     * 处理其他请求时可能读到上一个用户的身份，造成内存泄漏或用户身份串号。</p>
     *
     * <p>认证失败时 {@code preHandle} 返回 false，本回调不会执行；但认证失败分支没有
     * 向 UserContext 写入数据，因此不需要清理。</p>
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        UserContext.clear();
    }





    /**
     * 写出统一的未认证响应。
     *
     * <p>该方法同时设置 HTTP 状态码、JSON Content-Type 和 UTF-8 字符集，并把
     * {@link ResultCode#UNAUTHORIZED} 包装成项目统一的 {@link ApiResponse} 响应体。</p>
     *
     * @param response 当前 HTTP 响应
     * @throws IOException 写入响应体失败时抛出
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(ResultCode.UNAUTHORIZED.getCode());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(ResultCode.UNAUTHORIZED));
    }
}

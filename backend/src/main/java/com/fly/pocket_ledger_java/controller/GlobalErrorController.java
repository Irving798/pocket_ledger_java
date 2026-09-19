package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.vo.ApiResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;

/**
 * 「容器级错误」的统一出口。
 *
 * <p>目标只有一个：让那些<b>没能进入 Controller、也没能被 GlobalExceptionHandler 抓住</b>的错误，
 * 也返回项目统一的 {@code {code, message, data}} 结构，而不是 Spring Boot 默认的那套 JSON。</p>
 *
 * <p>一、它和 GlobalExceptionHandler 的分工（最重要的一点）</p>
 * <pre>
 *   请求 → Tomcat → DispatcherServlet → 找到 Controller → 执行
 *                    ↑                  ↑
 *                    │                  └─ 这里抛出的异常：GlobalExceptionHandler 负责
 *                    └─ 这里之前/之外出的错（路径没匹配上、Filter 抛异常、Error 级故障、容器自己拒绝的请求）
 *                       GlobalExceptionHandler 完全看不到 → 由本类负责
 * </pre>
 *
 * <p>二、请求是怎么跑到这里来的？（三步）</p>
 * <ol>
 *   <li>某处调用了 {@code response.sendError(404)}。最常见的情况：你访问了一个没有对应接口的路径，
 *       静态资源处理器找不到文件，于是发出 404；</li>
 *   <li>Tomcat 不会直接把错误返回给浏览器，而是把当前请求“内部转发”到错误页路径 {@code /error}。
 *       这次转发叫 ERROR 分发：浏览器地址栏不变，但 request 上被塞进了几个描述错误的属性
 *       （状态码、异常、出错路径等）；</li>
 *   <li>这次转发同样要经过 DispatcherServlet，于是命中了本类的 {@code @RequestMapping("/error")}。</li>
 * </ol>
 *
 * <p>三、为什么必须自己写一个</p>
 * Spring Boot 默认的 BasicErrorController 返回的是 {@code {timestamp, status, error, path}}，
 * 字段名和本项目的契约 {@code {code, message, data}} 不一样，客户端按 {@code code} 解析会拿不到值。
 * 本类被 Spring 扫描到之后，Boot 自动配置里的 BasicErrorController 就自动失效了
 * （它的条件是 {@code @ConditionalOnMissingBean(ErrorController.class)}），相当于“顶替”，不是“补充”。
 *
 * <p>四、两个必须守住的约束</p>
 * <ul>
 *   <li>路径必须是 {@code /error}：Boot 2.3 起 {@link #getErrorPath()} 已废弃，返回值被忽略，
 *       错误路径固定取配置项 {@code server.error.path}（默认就是 /error）。所以别去改这个配置，
 *       改了之后本类不会再被调用；</li>
 *   <li>{@code /error} 必须留在 {@code auth.ignore-urls} 白名单里（application.yml 已配好）。
 *       因为 ERROR 转发也会经过拦截器链，没有白名单会被 AuthInterceptor 当成“未登录”，
 *       把真正的错误响应改写成 401。</li>
 * </ul>
 */
@RestController
public class GlobalErrorController implements ErrorController {

    /** 容器约定的错误分发路径，与 server.error.path 保持一致。 */
    public static final String ERROR_PATH = "/error";

    /** 合法错误状态码的下界：小于 400 的不是错误。 */
    private static final int MIN_ERROR_STATUS = 400;

    /** 合法错误状态码的上界：HTTP 状态码最大 599。 */
    private static final int MAX_ERROR_STATUS = 599;

    /** 取不到状态码、或状态码不合法时的兜底值。 */
    private static final int DEFAULT_ERROR_STATUS = 500;

    /**
     * 容器错误分发的落点：把容器给出的状态码，翻译成统一响应体。
     *
     * <p>注意方法签名里的 {@link ResponseEntity}：HTTP 状态码是运行时才知道的，
     * 所以不能用固定的 {@code @ResponseStatus} 注解，只能通过 ResponseEntity 动态设置。</p>
     *
     * @param request 当前这次错误请求（容器已在它身上放好错误属性）
     * @return HTTP 状态码 = 原始错误码，响应体 = 统一失败结构
     */
    @RequestMapping(ERROR_PATH)
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        // 第 1 步：问容器“这次到底错在哪”，拿到真实的 HTTP 状态码
        int status = resolveStatus(request);
        // 第 2 步：状态码原样写回 HTTP 状态行，同时把同样的数值放进响应体的 code
        //         （契约要求 code 与 HTTP 状态码恒等），message 按状态码查文案
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(status, messageOf(status)));
    }

    /**
     * 从 request 属性中取错误状态码，并保证结果一定是一个合法的 4xx/5xx。
     *
     * <p>状态码是 Servlet 规范规定容器必须放进 request 的属性，键名就是
     * {@link RequestDispatcher#ERROR_STATUS_CODE}。直接访问 {@code /error}（不是错误转发）
     * 时这个属性不存在，所以要兜底。</p>
     *
     * @param request 当前错误请求
     * @return 合法的错误状态码；无法确定时返回 500
     */
    private static int resolveStatus(HttpServletRequest request) {
        // getAttribute 返回 Object，用 instanceof 判断再强转，避免类型不符时抛 ClassCastException
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer ? (Integer) attribute : DEFAULT_ERROR_STATUS;

        // 万一容器给了 0、200 这种不是错误的状态码，就按服务器内部错误处理，
        // 否则会出现“HTTP 200 但响应体是失败结构”这种自相矛盾的结果
        if (status < MIN_ERROR_STATUS || status > MAX_ERROR_STATUS) {
            return DEFAULT_ERROR_STATUS;
        }
        return status;
    }

    /**
     * 状态码 → 契约文案。只做查表，不做任何业务判断。
     *
     * @param status 合法的错误状态码
     * @return 对应文案
     */
    private static String messageOf(int status) {
        // 404：路径不存在
        if (status == 404) {
            return ResultCode.NOT_FOUND.getMessage();
        }
        // 405：路径存在但请求方法不对（例如接口只允许 POST，却用了 GET）
        if (status == 405) {
            return ResultCode.METHOD_NOT_ALLOWED.getMessage();
        }
        // 5xx：服务端自己的问题，一律用固定文案，不把堆栈等内部细节返回给客户端
        if (status >= 500) {
            return ResultCode.INTERNAL_ERROR.getMessage();
        }
        // 其余 4xx（403、413 等）：契约没有专门文案，统一归到“请求参数不合法”。
        // 文案是笼统的，但 code 仍然是真实的原始状态码，不会误导客户端判断
        return ResultCode.BAD_REQUEST.getMessage();
    }

    /**
     * ErrorController 接口要求实现的方法。
     *
     * <p>Boot 2.3 里它被标记为 {@code @Deprecated}，但仍是抽象方法，所以现在必须实现、否则编译不过；
     * 而框架已经不再读取它的返回值——真正的错误路径来自配置 {@code server.error.path}。
     * 这里返回同一个常量只是为了语义完整，改成别的值也不会有任何效果。</p>
     *
     * @return 错误分发路径
     */
    @Override
    public String getErrorPath() {
        return ERROR_PATH;
    }
}

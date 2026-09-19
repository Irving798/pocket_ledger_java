package com.fly.pocket_ledger_java.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 鉴权相关配置项。白名单 ignore-urls ：
 * 放行清单收敛在配置文件里，加"免登录"接口只改 yml，不改 Java 代码。
 * 由 WebMvcConfig 上的 @EnableConfigurationProperties 注册（不用 @Component，
 * 这样 @WebMvcTest 切片加载 WebMvcConfig 时也能拿到该 Bean）。
 */
@Data
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /**
     * 无需登录即可访问的路径，yml 里逗号分隔，Boot 自动绑定成 List。
     */
    private List<String> ignoreUrls = new ArrayList<>();
}

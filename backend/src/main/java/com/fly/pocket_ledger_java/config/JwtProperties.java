package com.fly.pocket_ledger_java.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * jwt.* 配置项的载体，字段名与 yml 里的 key 一一对应（驼峰映射 expire-minutes）。
 * Java 8 没有 record；Boot 2.x 的 @ConfigurationProperties 用 setter 绑定，所以是 @Data 类 + @Component 注册。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 签名密钥，至少 32 字节 */
    private String secret;

    /** token 有效期（分钟） */
    private long expireMinutes = 120;
}

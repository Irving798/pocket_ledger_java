package com.fly.pocket_ledger_java.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 鉴权模块的基础设施 Bean。
 */
@Configuration(proxyBeanMethods = false)
public class AuthConfig {

    /**
     * 密码哈希器，注册与登录校验共用。
     * bcrypt 慢因子默认 10，即哈希一次约 60~100ms——慢正是它的安全性来源。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

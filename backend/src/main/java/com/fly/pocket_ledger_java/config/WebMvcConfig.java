package com.fly.pocket_ledger_java.config;

import com.fly.pocket_ledger_java.interceptor.AuthInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：跨域规则与登录拦截器的注册。
 * 通过 @EnableConfigurationProperties 注册 AuthProperties，
 * 使 @WebMvcTest 切片测试加载本配置时也能拿到鉴权配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AuthProperties authProperties;

    public WebMvcConfig(AuthInterceptor authInterceptor, AuthProperties authProperties) {
        this.authInterceptor = authInterceptor;
        this.authProperties = authProperties;
    }

    /**
     * 开发环境跨域放开所有来源、方法与头；预检请求缓存 1 小时。
     * 上线时应收紧为具体的前端域名。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * 注册登录拦截器：默认拦截所有路径（“全都要登录”的安全默认值），
     * 放行清单收敛到 auth.ignore-urls 配置，加“免登录”接口只改 yml 不改代码。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(authProperties.getIgnoreUrls());
    }
}

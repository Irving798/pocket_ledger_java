package com.fly.pocket_ledger_java.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：通过 @MapperScan 扫描 mapper 包下的接口并生成代理 Bean。
 * XML 映射文件的位置由 application.yml 的 mapper-locations 指定，两者分工不同。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.fly.pocket_ledger_java.mapper")
public class MybatisPlusConfig {
}

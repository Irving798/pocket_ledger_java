package com.fly.pocket_ledger_java.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import java.time.Clock;
import java.time.ZoneId;

/**
 * 账单模块的基础设施 Bean：业务时钟与 Jackson 反序列化收紧。
 */
@Configuration(proxyBeanMethods = false)
public class BillConfig {

    /**
     * 统一业务时钟，固定台北时区。
     * 日期校验（如账单日期不能晚于当天）都基于它，测试时可替换为固定时钟以覆盖跨日边界。
     */
    @Bean
    public Clock ledgerClock() {
        return Clock.system(ZoneId.of("Asia/Taipei"));
    }

    /**
     * 禁止把 JSON 小数反序列化为整型字段（如 1.5 传给 Long）。
     * 契约不接受小数形式的 ID，默认放行会导致静默截断，这里直接拒绝。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer rejectFractionalIds() {
        return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
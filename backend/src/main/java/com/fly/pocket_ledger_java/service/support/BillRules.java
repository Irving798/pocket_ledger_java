package com.fly.pocket_ledger_java.service.support;

import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;

/**
 * 账单领域规则组件，集中处理日期边界、细分合计与金额格式约束。
 */
@Component
public class BillRules {
    private final Clock clock;

    /**
     * 注入业务时钟，确保“今天”的判断可在测试中稳定控制。
     *
     * @param clock 业务时钟
     */
    public BillRules(Clock clock) { this.clock = clock; }

    /**
     * 校验账单日期位于系统允许的最早日期与当前业务日期之间。
     *
     * @param date 待校验的账单日期
     */
    public void checkDate(LocalDate date) {
        // 使用注入时钟获取当前日期，避免直接依赖机器时间导致测试不稳定。
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ResultCode.PARAM_INVALID.getCode(),
                    "参数错误：bill_date 不得晚于今天");
        }
        // 数据库与接口统一限制历史日期下界，避免保存异常的极早日期。
        if (date.isBefore(LocalDate.of(1000, 1, 1))) {
            throw new BusinessException(ResultCode.PARAM_INVALID.getCode(),
                    "参数错误：bill_date 不得早于 1000-01-01");
        }
    }

    /**
     * 校验用户细分金额合计不得超过账单总金额。
     *
     * @param total 账单总金额
     * @param sum 用户细分金额合计
     */
    public void checkSum(BigDecimal total, BigDecimal sum) {
        // 超出总额时抛出明确业务错误，阻止主账单与明细进入持久层。
        if (sum.compareTo(total) > 0) {
            throw new BusinessException(ResultCode.BREAKDOWN_EXCEEDS_TOTAL);
        }
    }

    /**
     * 将金额严格转换为两位小数字符串，不允许在展示阶段静默舍入。
     *
     * @param value 待格式化金额
     * @return 两位小数的纯数字字符串
     */
    public static String money(BigDecimal value) {
        // UNNECESSARY 会在金额精度超过两位时直接报错，及时暴露不符合约束的数据。
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}

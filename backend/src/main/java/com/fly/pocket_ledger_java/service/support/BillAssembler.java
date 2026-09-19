package com.fly.pocket_ledger_java.service.support;

import com.fly.pocket_ledger_java.entity.BillBreakdown;
import com.fly.pocket_ledger_java.mapper.model.BillRow;
import com.fly.pocket_ledger_java.vo.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 账单视图组装器，将持久层查询结果与细分记录转换为接口返回对象。
 */
@Component
public class BillAssembler {
    /**
     * 组装账单及其细分，统一格式化金额，并在需要时追加系统生成的“其他”项。
     *
     * @param row 账单与分类的联合查询结果
     * @param details 用户填写的细分记录；为 null 时表示调用方未请求细分
     * @return 完整账单视图
     */
    public BillVO toVO(BillRow row, List<BillBreakdown> details) {
        BillVO vo = new BillVO();
        vo.setId(row.getId());
        vo.setCategoryId(row.getCategoryId());
        vo.setCategoryName(row.getCategoryName());
        vo.setType(row.getType());
        vo.setAmount(BillRules.money(row.getAmount()));
        vo.setDescription(row.getDescription());
        vo.setBillDate(row.getBillDate());
        // null 与空列表含义不同：null 表示未加载细分，因此响应也保持 null。
        if (details == null) {
            vo.setBreakdowns(null);
            return vo;
        }

        List<BillBreakdownVO> result = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        // 汇总用户细分并逐项转换，金额统一输出为两位小数字符串。
        for (BillBreakdown detail : details) {
            sum = sum.add(detail.getAmount());
            result.add(new BillBreakdownVO
                    (
                    detail.getId(),
                    detail.getItemName(),
                    BillRules.money(detail.getAmount()),
                    detail.getSortOrder(),
                    "user"
                    )
            );
        }
        // 总金额减去用户细分得到未分配金额；负数说明持久化数据已经违反业务约束。
        BigDecimal remainder = row.getAmount().subtract(sum);
        if (remainder.signum() < 0) {
            throw new IllegalStateException("持久化账单违反细分合计约束");
        }
        // 已有用户细分且仍有余额时追加系统项，使细分展示合计与账单总额一致。
        if (!details.isEmpty() && remainder.signum() > 0) {
            result.add(new BillBreakdownVO(null, "其他", BillRules.money(remainder),
                    details.size(), "system"));
        }
        vo.setBreakdowns(result);
        return vo;
    }
}

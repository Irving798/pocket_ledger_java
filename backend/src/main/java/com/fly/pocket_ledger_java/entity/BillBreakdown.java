package com.fly.pocket_ledger_java.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 账单细分实体，对应表 fly_bill_breakdown。
 * 表里只存用户显式输入的细分；响应中 source=system 的“其他”余项由组装器计算，不入库。
 */
@Data
@TableName("fly_bill_breakdown")
public class BillBreakdown {

    /** 细分 ID，自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID，冗余存储，必须与父账单的用户一致 */
    private Long userId;

    /** 所属账单 ID */
    private Long billId;

    /** 细分项名称，如“主餐”“饮料” */
    private String itemName;

    /** 细分金额，正数，小数最多两位 */
    private BigDecimal amount;

    /** 排序值，由服务端按用户输入下标生成，用于还原填写顺序 */
    private Integer sortOrder;
}
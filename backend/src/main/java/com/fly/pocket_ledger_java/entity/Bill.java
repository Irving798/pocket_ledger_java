package com.fly.pocket_ledger_java.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 账单实体，对应表 fly_bill。
 * 只保存用户显式提交的主账单字段；细分在 fly_bill_breakdown 表中单独维护。
 */
@Data
@TableName("fly_bill")
public class Bill {

    /** 账单 ID，自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 ID，由服务端从登录身份注入，不从请求体接收 */
    private Long userId;

    /** 分类 ID */
    private Long categoryId;

    /** 账单总金额，正数，小数最多两位 */
    private BigDecimal amount;

    /** 账单描述 */
    private String description;

    /** 账单日期 */
    private LocalDate billDate;
}
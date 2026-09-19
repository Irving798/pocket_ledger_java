package com.fly.pocket_ledger_java.vo;

import com.fasterxml.jackson.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

/**
 * 账单视图对象：单条账单的完整返回结构，对齐契约（docs/pocket-ledger-api.html）。
 * 金额用字符串返回避免精度丢失，日期固定 yyyy-MM-dd 格式。
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)// 始终序列化所有字段：breakdowns 为 null 也要返回，表达“未加载细分”
public class BillVO {

    /** 账单 ID */
    private Long id;

    /** 分类 ID */
    @JsonProperty("category_id")
    private Long categoryId;

    /** 分类名称，冗余返回省去前端二次查询 */
    @JsonProperty("category_name")
    private String categoryName;

    /** 账单类型：income 收入 / expense 支出 */
    private String type;

    /** 账单总金额，字符串形式的两位小数 */
    private String amount;

    /** 账单描述 */
    private String description;

    /** 账单日期 */
    @JsonProperty("bill_date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate billDate;

    /** 细分列表；为 null 表示本次查询未加载细分，为空列表表示确实没有细分 */
    private List<BillBreakdownVO> breakdowns;
}
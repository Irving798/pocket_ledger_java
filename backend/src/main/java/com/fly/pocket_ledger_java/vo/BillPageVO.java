package com.fly.pocket_ledger_java.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

/**
 * 账单分页视图对象：分页查询接口的返回结构。
 * 除当前页列表外，还携带符合筛选条件的账单总数及收入/支出总额统计。
 */
@Data
@AllArgsConstructor
public class BillPageVO {

    /** 符合筛选条件的账单总数 */
    private long total;

    /** 当前页码，从 1 开始 */
    private int page;

    /** 每页条数 */
    @JsonProperty("page_size")
    private int pageSize;

    /** 收入总额，字符串形式的两位小数 */
    @JsonProperty("income_total")
    private String incomeTotal;

    /** 支出总额，字符串形式的两位小数 */
    @JsonProperty("expense_total")
    private String expenseTotal;

    /** 当前页账单列表 */
    private List<BillVO> list;
}
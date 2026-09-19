package com.fly.pocket_ledger_java.vo;

import com.fasterxml.jackson.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 账单细分视图对象：金额以字符串形式返回，避免 JSON 数字精度丢失。
 * 显式细分合计小于账单总额时，组装器会追加一个不入库的“其他”余项（id=null、source=system）。
 */
@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)// 始终序列化所有字段：系统余项的 id 为 null 也必须出现在响应里
public class BillBreakdownVO {

    /** 细分 ID；系统自动补足的“其他”项不入库，固定为 null */
    private Long id;

    /** 细分项名称，如“主餐”“饮料” */
    @JsonProperty("item_name")
    private String itemName;

    /** 细分金额，字符串形式的两位小数 */
    private String amount;

    /** 排序值，还原用户填写顺序，由服务端按输入下标生成 */
    @JsonProperty("sort_order")
    private Integer sortOrder;

    /** 细分来源：user 表示用户显式输入，system 表示服务端自动补足的“其他”余项 */
    private String source;
}
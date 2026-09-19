package com.fly.pocket_ledger_java.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import javax.validation.constraints.*;
import java.math.BigDecimal;

/**
 * 账单细分输入项：只接受用户显式输入的 {item_name, amount}。
 * 不接受细分 id、sort_order、source——排序由服务端按输入下标生成，系统“其他”项由响应侧计算。
 * message 只写“原因”，全局异常处理器会拼成“参数错误：字段 原因”（422）。
 */
@Data
public class BillBreakdownDTO {

    /** 细分项名称，最长 50 字符；“其他”为系统余项保留名，用户输入不允许使用 */
    @JsonProperty("item_name")
    @NotBlank(message = "不能为空")
    @Size(max = 50, message = "长度不能超过 50")
    @Pattern(regexp = "^(?!其他$)[\\s\\S]*$", message = "不能使用保留名“其他”")
    private String itemName;

    /** 细分金额，正数，整数最多 10 位、小数最多 2 位 */
    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.00", inclusive = false, message = "必须大于 0")
    @Digits(integer = 10, fraction = 2, message = "整数最多 10 位，小数最多 2 位")
    private BigDecimal amount;

    /**
     * 对齐契约的 strip_whitespace，在 setter 里去首尾空白。
     */
    public void setItemName(String itemName) {
        this.itemName = itemName == null ? null : itemName.trim();
    }

    /**
     * 拦截未定义的请求体字段并拒绝（如 id、sort_order、source），不允许客户端回传响应字段。
     */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("不支持的字段：" + name);
    }
}
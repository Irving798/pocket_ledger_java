package com.fly.pocket_ledger_java.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 账单新增/更新参数，规则对齐契约（docs/pocket-ledger-api.html）。
 * message 只写“原因”，全局异常处理器会拼成“参数错误：字段 原因”（422）。
 * 身份相关字段（user_id 等）由服务端从登录上下文注入，不接受请求体传入。
 */
@Data
public class BillWriteDTO {

    /** 分类 ID，必须指向已存在的分类（存在性由业务层校验） */
    @JsonProperty("category_id")
    @NotNull(message = "不能为空")
    @Positive(message = "必须大于 0")
    private Long categoryId;

    /** 账单总金额，正数，整数最多 10 位、小数最多 2 位 */
    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.00", inclusive = false, message = "必须大于 0")
    @Digits(integer = 10, fraction = 2, message = "整数最多 10 位，小数最多 2 位")
    private BigDecimal amount;

    /** 账单描述，可选，最长 200 字符 */
    @Size(max = 200, message = "长度不能超过 200")
    private String description;

    /** 账单日期，不允许晚于当前业务日期（由业务层校验） */
    @JsonProperty("bill_date")
    @NotNull(message = "不能为空")
    private LocalDate billDate;

    /**
     * 细分列表，可选，最多 20 项。
     * 更新接口中：传 null 表示保留旧细分，传 [] 表示清空，传非空列表表示整体替换。
     */
    @Valid
    @Size(max = 20, message = "最多 20 项")
    private List<@NotNull(message = "细分项不能为 null") BillBreakdownDTO> breakdowns;

    /**
     * 对齐契约的 strip_whitespace，在 setter 里去首尾空白。
     * 手写 setter 后 Lombok @Data 不再生成同名方法。
     */
    public void setDescription(String description) {
        this.description = description == null ? null : description.trim();
    }

    /**
     * 拦截未定义的请求体字段并拒绝（如 user_id、细分 id、source），归属与系统项只能由服务端决定。
     */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("不支持的字段：" + name);
    }
}
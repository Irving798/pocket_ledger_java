package com.fly.pocket_ledger_java.dto;

import lombok.Data;

import javax.validation.constraints.Pattern;

/**
 * 分类查询参数。
 * 注意：Boot 2.x 用 javax.validation 命名空间，Boot 3 之后才是 jakarta.validation。
 * message 只写"原因"，全局异常处理器拼成"参数错误：type 必须为 income 或 expense"（422）。
 */
@Data
public class CategoryQueryDTO {

    /**
     * 可选；income = 收入，expense = 支出
     */
    @Pattern(regexp = "^(income|expense)$", message = "必须为 income 或 expense")
    private String type;
}

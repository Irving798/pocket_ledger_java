package com.fly.pocket_ledger_java.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import javax.validation.constraints.*;
import java.time.LocalDate;

/**
 * 账单分页查询参数，绑定 URL query string（字段名保持 snake_case 对齐契约）。
 * 分页字段都有默认值，缺省时按 desc 第 1 页、每页 10 条、不含细分查询。
 * message 只写“原因”，全局异常处理器会拼成“参数错误：字段 原因”（422）。
 */
@Data
public class BillQueryDTO {

    /** 可选：起始日期（含），格式 yyyy-MM-dd；与 end_date 同时提供时不能晚于它 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate start_date;

    /** 可选：截止日期（含），格式 yyyy-MM-dd */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate end_date;

    /** 可选：按分类 ID 筛选 */
    @Positive(message = "必须大于 0")
    private Long category_id;

    /** 可选：按账单类型筛选，income 收入 / expense 支出 */
    @Pattern(regexp = "income|expense", message = "必须为 income 或 expense")
    private String bill_type;

    /** 按账单日期排序方向，默认 desc（最新的在前） */
    @NotNull(message = "不能为空")
    @Pattern(regexp = "asc|desc", message = "必须为 asc 或 desc")
    private String order = "desc";

    /** 页码，从 1 开始 */
    @NotNull(message = "不能为空")
    @Min(value = 1, message = "必须大于等于 1")
    private Integer page = 1;

    /** 每页条数，1~100 */
    @NotNull(message = "不能为空")
    @Min(value = 1, message = "必须大于等于 1")
    @Max(value = 100, message = "不能超过 100")
    private Integer page_size = 10;

    /** 是否在响应中包含每条账单的细分列表，默认不包含 */
    @NotNull(message = "不能为空")
    private Boolean include_breakdowns = false;
}
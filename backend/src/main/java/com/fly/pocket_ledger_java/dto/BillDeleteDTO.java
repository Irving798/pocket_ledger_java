package com.fly.pocket_ledger_java.dto;


import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import javax.validation.constraints.*;
import java.util.List;

/**
 * 账单批量删除参数：只接受账单 ID 列表。
 * 列表不能为空，元素必须为正数；不存在的 ID 由业务层按约定跳过，不在此处校验。
 * message 只写“原因”，全局异常处理器会拼成“参数错误：字段 原因”（422）。
 */
@Data
public class BillDeleteDTO {

    /** 待删除的账单 ID 列表，至少 1 个，元素必须为正数且不能为 null */
    @NotEmpty(message = "账单ID不能为空")
    @Size(max = 100, message = "单次最多删除100条")
    private List<@NotNull(message="不能为null") @Positive(message="必须大于0") Long> ids;

    /**
     * 拦截未定义的请求体字段并拒绝（如 user_id），归属只能由服务端决定。
     */
    @JsonAnySetter
    public void rejectUnknown(String name,Object value){
        throw new IllegalArgumentException("不支持字段："+name);
    }
}


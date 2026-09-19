package com.fly.pocket_ledger_java.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分类信息视图对象：只暴露给前端的字段，实体类（含敏感字段）不允许直接出 Controller。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryVO {

    /** 分类 ID */
    private Long id;

    /** 分类名称，如“餐饮”“交通” */
    private String name;

    /** 分类类型：income 收入 / expense 支出 */
    private String type;

    /** 前端展示用的图标标识 */
    private String icon;

    /** 分类排序值，越小越靠前 */
    private Integer sort;
}

package com.fly.pocket_ledger_java.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 分类实体，对应表 fly_category。
 */
@Data
@TableName("fly_category")
public class Category {

    /** 分类 ID，自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
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

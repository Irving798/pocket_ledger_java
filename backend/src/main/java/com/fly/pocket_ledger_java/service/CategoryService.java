package com.fly.pocket_ledger_java.service;

import com.fly.pocket_ledger_java.vo.CategoryVO;

import java.util.List;

/**
 * 分类业务接口，提供按收支类型查询分类的能力。
 */
public interface CategoryService {

    /**
     * 按可选类型筛选分类，并按固定顺序返回结果。
     *
     * @param type 分类类型；为空时查询全部分类
     * @return 排序后的分类列表
     */
    List<CategoryVO> listCategories(String type);
}

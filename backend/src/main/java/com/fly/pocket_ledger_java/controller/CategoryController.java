package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.dto.CategoryQueryDTO;
import com.fly.pocket_ledger_java.service.CategoryService;
import com.fly.pocket_ledger_java.vo.ApiResponse;
import com.fly.pocket_ledger_java.vo.CategoryVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 分类查询接口，路径对齐契约：GET /categories（无 /api 前缀），且在 auth.ignore-urls 白名单里免登录。
 */
@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 注入分类业务服务。
     *
     * @param categoryService 分类业务服务
     */
    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 查询分类列表；未指定类型时返回全部分类，指定类型时仅返回对应收支分类。
     *
     * @param query 分类类型查询条件
     * @return 符合条件的分类列表
     */
    @GetMapping
    public ApiResponse<List<CategoryVO>> listCategories(@Valid CategoryQueryDTO query) {
        // 类型合法性由 DTO 校验保证，服务层负责筛选、稳定排序及视图转换。
        return ApiResponse.success(categoryService.listCategories(query.getType()));
    }
}

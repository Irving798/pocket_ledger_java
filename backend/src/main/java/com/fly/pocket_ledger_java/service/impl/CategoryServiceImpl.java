package com.fly.pocket_ledger_java.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fly.pocket_ledger_java.entity.Category;
import com.fly.pocket_ledger_java.mapper.CategoryMapper;
import com.fly.pocket_ledger_java.service.CategoryService;
import com.fly.pocket_ledger_java.vo.CategoryVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 分类业务实现，负责可选类型筛选、稳定排序与视图转换。
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;

    /**
     * 注入分类持久层。
     *
     * @param categoryMapper 分类持久层
     */
    public CategoryServiceImpl(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    /**
     * 类型有值时按类型筛选，未提供类型时查询全部，并按排序值和 ID 升序返回。
     *
     * @param type 分类类型；为空时不添加类型条件
     * @return 排序后的分类视图列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<CategoryVO> listCategories(String type) {
        LambdaQueryWrapper<Category> queryWrapper = Wrappers.lambdaQuery(Category.class)
                .eq(StringUtils.hasText(type), Category::getType, type)
                .orderByAsc(Category::getSort, Category::getId);

        // Java 8 没有 Stream.toList()，项目统一写 collect(Collectors.toList())
        return categoryMapper.selectList(queryWrapper)
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }
    /**
     * 将分类实体转换为接口返回的分类视图。
     *
     * @param category 分类实体
     * @return 分类视图
     */
    private CategoryVO toVO(Category category) {
        return new CategoryVO(
                category.getId(),
                category.getName(),
                category.getType(),
                category.getIcon(),
                category.getSort()
        );
    }
}

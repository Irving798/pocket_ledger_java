package com.fly.pocket_ledger_java.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fly.pocket_ledger_java.entity.Category;
import com.fly.pocket_ledger_java.mapper.CategoryMapper;
import com.fly.pocket_ledger_java.vo.CategoryVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), "CategoryServiceImplTest"),
                Category.class
        );
    }

    @Mock
    private CategoryMapper categoryMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void listsCategoriesUsingTypeFilterAndStableOrdering() {
        Category food = category(2L, "餐饮", "expense", "food", 10);
        Category salary = category(1L, "工资", "income", "salary", 20);
        when(categoryMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(food, salary));

        List<CategoryVO> result = categoryService.listCategories("expense");

        assertThat(result).containsExactly(
                new CategoryVO(2L, "餐饮", "expense", "food", 10),
                new CategoryVO(1L, "工资", "income", "salary", 20)
        );

        ArgumentCaptor<Wrapper<Category>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(categoryMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSegment)
                .contains("type")
                .contains("ORDER BY")
                .contains("sort ASC")
                .contains("id ASC");
    }

    private Category category(Long id, String name, String type, String icon, Integer sort) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setType(type);
        category.setIcon(icon);
        category.setSort(sort);
        return category;
    }
}

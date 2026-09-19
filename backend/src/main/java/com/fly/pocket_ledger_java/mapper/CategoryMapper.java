package com.fly.pocket_ledger_java.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.pocket_ledger_java.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类持久层，复用 MyBatis-Plus 提供的基础 CRUD 能力。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}

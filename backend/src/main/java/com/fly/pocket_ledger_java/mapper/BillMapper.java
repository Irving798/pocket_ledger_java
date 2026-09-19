package com.fly.pocket_ledger_java.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.pocket_ledger_java.dto.BillQueryDTO;
import com.fly.pocket_ledger_java.entity.Bill;
import com.fly.pocket_ledger_java.mapper.model.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;


/**
 * 账单持久层，提供用户隔离的查询、统计、锁定及写入操作。
 */
public interface BillMapper extends BaseMapper<Bill>{
    /**
     * 按用户归属查询并锁定单笔账单，供更新或删除流程使用。
     *
     * @param userId 当前用户 ID
     * @param ids 账单 ID
     * @return 被锁定的账单；不存在或不属于当前用户时为空
     */
    Bill lockOwned(@Param("userId") Long userId,@Param("id") Long ids);

    /**
     * 按固定顺序锁定当前用户拥有的多笔账单；ID 集合为空时不锁定数据。
     *
     * @param userId 当前用户 ID
     * @param ids 账单 ID 集合
     * @return 实际锁定的账单 ID
     */
    List<Long> lockOwnedIds(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    /**
     * 查询当前用户拥有的单笔账单及关联分类信息。
     *
     * @param userId 当前用户 ID
     * @param id 账单 ID
     * @return 账单联合查询结果；不存在或无权访问时为空
     */
    BillRow findOwned(@Param("userId") Long userId,@Param("id") Long id);

    /**
     * 按查询条件统计当前用户的账单数量、收入合计与支出合计。
     *
     * @param userId 当前用户 ID
     * @param q 账单筛选条件
     * @return 账单汇总结果
     */
    BillSummary summarize(@Param("userId") Long userId,@Param("q") BillQueryDTO q);

    /**
     * 按查询条件、排序方向和偏移量查询当前用户的一页账单。
     *
     * @param userId 当前用户 ID
     * @param q 账单筛选、排序及每页条数
     * @param offset 分页偏移量
     * @return 当前页账单联合查询结果
     */
    List<BillRow> findPage(@Param("userId") Long userId, @Param("q") BillQueryDTO q,
                           @Param("offset") long offset);

    /**
     * 按用户归属更新单笔账单，防止修改其他用户数据。
     *
     * @param userId 当前用户 ID
     * @param bill 待更新的账单及字段
     * @return 更新行数
     */
    int updateOwned(@Param("userId") Long userId, @Param("bill") Bill bill);

    /**
     * 按用户归属批量删除账单；ID 集合为空时不删除数据。
     *
     * @param userId 当前用户 ID
     * @param ids 账单 ID 集合
     * @return 删除行数
     */
    int deleteOwned(@Param("userId") Long userId, @Param("ids") List<Long> ids);
}

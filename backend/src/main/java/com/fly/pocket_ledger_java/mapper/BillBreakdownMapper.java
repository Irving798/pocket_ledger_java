package com.fly.pocket_ledger_java.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.pocket_ledger_java.entity.BillBreakdown;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * 账单细分持久层，所有自定义查询与删除均携带用户条件以保证数据隔离。
 */
public interface BillBreakdownMapper extends BaseMapper<BillBreakdown> {
    /**
     * 按当前用户及账单 ID 集合查询细分；ID 集合为空时不返回数据。
     *
     * @param userId 当前用户 ID
     * @param ids 账单 ID 集合
     * @return 按账单及细分顺序排列的明细
     */
    List<BillBreakdown> findByBills(@Param("userId") Long userId,
                                    @Param("ids") List<Long> ids);

    /**
     * 删除当前用户指定账单下的全部细分。
     *
     * @param userId 当前用户 ID
     * @param billId 账单 ID
     * @return 删除行数
     */
    int deleteByBill(@Param("userId") Long userId, @Param("billId") Long billId);

    /**
     * 批量删除当前用户多个账单下的细分；ID 集合为空时不删除数据。
     *
     * @param userId 当前用户 ID
     * @param ids 账单 ID 集合
     * @return 删除行数
     */
    int deleteByBills(@Param("userId") Long userId, @Param("ids") List<Long> ids);
}

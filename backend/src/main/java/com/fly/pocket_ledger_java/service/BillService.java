package com.fly.pocket_ledger_java.service;

import com.fly.pocket_ledger_java.dto.BillQueryDTO;
import com.fly.pocket_ledger_java.dto.BillWriteDTO;
import com.fly.pocket_ledger_java.vo.ApiResponse;
import com.fly.pocket_ledger_java.vo.BillPageVO;
import com.fly.pocket_ledger_java.vo.BillVO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import javax.validation.Valid;
import java.util.List;

/**
 * 账单业务接口，定义账单写入与分页查询流程。
 */
public interface BillService {
    /**
     * 为当前登录用户新增账单。
     *
     * @param userId 当前登录用户 ID，由服务端身份上下文获取
     * @param dto 新增账单的请求数据
     * @return 新增后的账单信息
     */
    BillVO create(Long userId, BillWriteDTO dto);

    /**
     * 按筛选、排序与分页条件查询当前用户的账单列表。
     *
     * @param userId 当前登录用户 ID，由服务端身份上下文获取
     * @param query 账单查询条件
     * @return 账单列表信息
     */
    BillPageVO list(Long userId, BillQueryDTO query);

    /**
     * 获取当前用户指定 ID 的账单信息。
     *
     * @param userId 当前登录用户 ID，由服务端身份上下文获取
     * @param id 账单 ID
     * @return 账单信息
     */
    BillVO get(Long userId, Long id);

    /**
     * 删除当前用户指定 ID 的账单信息。
     *
     * @param userId 当前登录用户 ID，由服务端身份上下文获取
     * @param billIds 账单 ID 列表
     * @return 是否删除成功
     */

    int delete(Long userId, List<Long> billIds);


    /**
     * 更新账单信息
     * @param billId
     * @param dto
     * @return
     */
    BillVO update(Long userId, Long billId, BillWriteDTO dto);
}

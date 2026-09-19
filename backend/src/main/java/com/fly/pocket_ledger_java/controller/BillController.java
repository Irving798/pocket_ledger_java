package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.dto.BillDeleteDTO;
import com.fly.pocket_ledger_java.dto.BillQueryDTO;
import com.fly.pocket_ledger_java.dto.BillWriteDTO;
import com.fly.pocket_ledger_java.exception.BusinessException;
import com.fly.pocket_ledger_java.service.BillService;
import com.fly.pocket_ledger_java.util.UserContext;
import com.fly.pocket_ledger_java.vo.ApiResponse;
import com.fly.pocket_ledger_java.vo.BillPageVO;
import com.fly.pocket_ledger_java.vo.BillVO;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 账单接口，负责接收账单写入与列表查询请求，并将当前登录用户身份传递给服务层。
 */
@RestController
@RequestMapping("/bills")
public class BillController {

    private final BillService billService;

    /**
     * 注入账单业务服务。
     *
     * @param billService 账单业务服务
     */
    public BillController(BillService billService) {
        this.billService = billService;
    }

    /**
     * 为当前登录用户新增账单。
     *
     * @param dto 已通过参数校验的账单写入数据
     * @return 新增后的完整账单信息
     */
    @PostMapping("/add")
    public ApiResponse<BillVO> create(
            @Valid @RequestBody BillWriteDTO dto) {


        BillVO result = billService.create(userId(), dto);

        return ApiResponse.success("添加成功", result);
    }

    /**
     * 按查询条件分页获取当前登录用户的账单列表。
     *
     * @param query 账单筛选、排序与分页条件
     */
    @GetMapping("/list")
    public ApiResponse<BillPageVO> list(@Valid @ModelAttribute BillQueryDTO query) {
        return ApiResponse.success(billService.list(userId(), query));
    }

    /**
     * 根据账单 ID 获取账单详情。
     *
     */
    @GetMapping("/{id}")
    public ApiResponse<BillVO> get(@PathVariable Long id) {
        return ApiResponse.success(billService.get(userId(), id));
    }


    /**
     * 批量删除账单
     *
     */
    @DeleteMapping
    public ApiResponse<Integer> delete(@Valid @RequestBody BillDeleteDTO dto){
        int count = billService.delete(userId(),dto.getIds());
        return ApiResponse.success("成功删除"+count+"条",count);
    }

    /**
     * 账单修改接口
     *
     *
     */
    @PutMapping("{bill_id}")
    public ApiResponse<BillVO> update(@PathVariable("bill_id") Long billId,
                                      @Valid @RequestBody BillWriteDTO dto){
        //校验账单 ID 是否有效。
        checkId(billId);

        return ApiResponse.success("更新成功", billService.update(userId(), billId, dto));

    }








    // 私有工具方法

    // ==================== 登录用户解析 ====================

    /**
     * 从鉴权上下文读取当前用户 ID，并防止无效身份进入账单业务流程。
     *
     * @return 当前登录用户 ID
     */
    private Long userId() {
        Long currentUserId = UserContext.getUserId();

        // 上下文缺失或用户 ID 非法均视为未登录，统一交由全局异常处理器返回 401。
        if (currentUserId == null || currentUserId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        return currentUserId;
    }


    /**
     * 校验账单 ID 是否有效。
     *
     * @param id 账单 ID
     */
    private void checkId(Long id) {
        if (id <= 0) {
            throw new BusinessException(422, "参数错误：bill_id 必须大于 0");
        }
    }
}

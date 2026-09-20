package com.fly.pocket_ledger_java.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.dto.BillBreakdownDTO;
import com.fly.pocket_ledger_java.dto.BillQueryDTO;
import com.fly.pocket_ledger_java.dto.BillWriteDTO;
import com.fly.pocket_ledger_java.entity.Bill;
import com.fly.pocket_ledger_java.entity.BillBreakdown;
import com.fly.pocket_ledger_java.exception.BusinessException;
import com.fly.pocket_ledger_java.mapper.BillBreakdownMapper;
import com.fly.pocket_ledger_java.mapper.CategoryMapper;
import com.fly.pocket_ledger_java.mapper.model.BillRow;
import com.fly.pocket_ledger_java.mapper.model.BillSummary;
import com.fly.pocket_ledger_java.service.BillService;
import com.fly.pocket_ledger_java.service.support.BillAssembler;
import com.fly.pocket_ledger_java.service.support.BillRules;
import com.fly.pocket_ledger_java.vo.BillPageVO;
import com.fly.pocket_ledger_java.vo.BillVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.fly.pocket_ledger_java.mapper.BillMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 账单业务实现，协调规则校验、主账单与细分持久化以及返回对象组装。
 */
@Service
public class BillServiceImpl implements BillService {
    //  声明业务流程需要使用的对象
    private final BillMapper billMapper;
    private final BillBreakdownMapper breakdownMapper;
    private final CategoryMapper categoryMapper;
    private final BillRules billRules;
    private final BillAssembler assembler;

    /**
     * 注入账单业务流程所需的持久层、规则与组装组件。
     *
     * @param billMapper      账单持久层
     * @param breakdownMapper 账单细分持久层
     * @param categoryMapper  分类持久层
     * @param billRules       账单业务规则组件
     * @param assembler       账单视图组装组件
     */
    public BillServiceImpl(
            BillMapper billMapper,
            BillBreakdownMapper breakdownMapper,
            CategoryMapper categoryMapper,
            BillRules billRules,
            BillAssembler assembler) {

        this.billMapper = billMapper;
        this.breakdownMapper = breakdownMapper;
        this.categoryMapper = categoryMapper;
        this.billRules = billRules;
        this.assembler = assembler;
    }


    /**
     * 新增接口
     *
     * @param userId 当前登录用户 ID
     * @param dto    账单写入数据
     * @return 新增后的完整账单信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BillVO create(Long userId, BillWriteDTO dto) {
        // 检查账单日期
        billRules.checkDate(dto.getBillDate());
        // 检查分类是否存在
        requireCategory(dto.getCategoryId());

        // 检查细分金额合计不能超过账单总金额
        BigDecimal breakdownTotal = sumInput(dto.getBreakdowns());// 计算细分金额合计
        billRules.checkSum(dto.getAmount(), breakdownTotal);
        // 构造主账单实体
        Bill bill = new Bill();
        bill.setUserId(userId);
        applyFields(bill, dto);
        // 保存主账单，并同时核对受影响行数和数据库回填的主键。
        int insertedRows = billMapper.insert(bill);

        if (insertedRows != 1 || bill.getId() == null) {
            throw new IllegalStateException("新增账单未取得主键");
        }
        // 细分与主账单在同一事务中写入，任一写入失败都会整体回滚。
        insertDetails(userId, bill.getId(), dto.getBreakdowns());
        // 查询刚新增的数据，组装并返回 BillVO
        return loadDetail(userId, bill.getId());
    }


    /**
     * 分页查询当前用户的账单列表接口
     * <p>
     * 主要流程：
     * 1. 校验用户 ID 和日期范围；
     * 2. 计算分页偏移量；
     * 3. 统计账单总数、收入总额和支出总额；
     * 4. 查询当前页账单；
     * 5. 根据请求决定是否查询账单细分；
     * 6. 将查询结果组装成分页对象返回。
     */
    @Override
    public BillPageVO list(Long userId, BillQueryDTO query) {
        //校验用户ID
        requireUserId(userId);
        //若提供了开始日期和结束日期的查询条件，则校验开始日期不能结束日期
        if (query.getStart_date() != null && query.getEnd_date() != null
                && query.getStart_date().isAfter(query.getEnd_date())) {
            throw new BusinessException(ResultCode.PARAM_INVALID.getCode(),
                    "start_date 不能晚于 end_date");
        }


        //计算分页偏移量
        long offset = ((long) query.getPage() - 1L) * query.getPage_size();

        //统计账单总数，收入总额和支出总额；
        BillSummary summary = billMapper.summarize(userId, query);

        //查询当前页账单（offset过大时返回空列表）
        List<BillRow> rows = offset >= summary.getTotal()
                ? Collections.emptyList()
                : billMapper.findPage(userId, query, offset);

        //用账单 ID 对细分记录进行分组,Map 的结构类似：{账单ID1: [细分1, 细分2],账单ID2: [细分1] }
        Map<Long, List<BillBreakdown>> grouped = Collections.emptyMap();

        //根据请求决定是否查询账单细分；只有调用方明确要求返回细分，并且当前页确实存在账单时，才查询账单细分
        if (Boolean.TRUE.equals(query.getInclude_breakdowns()) && !rows.isEmpty()) {
            // 收集当前页所有账单 ID。
            List<Long> ids = rows.stream()
                    .map(BillRow::getId)
                    .collect(Collectors.toList());

            //一次性查询当前页所有账单的细分，然后按照 billId 分组。
            grouped = breakdownMapper.findByBills(userId, ids)
                    .stream()
                    .collect(Collectors.groupingBy(BillBreakdown::getBillId));
        }

        //组装账单vo对象的列表
        List<BillVO> list = new ArrayList<>();
        for (BillRow row : rows) {
            /*
             * 如果请求要求包含细分：
             * - 从 grouped 中取得当前账单的细分；
             * - 当前账单没有细分时使用空列表。
             *
             * 如果请求不要求包含细分，则传入 null，
             * 让组装器在响应中保留“未加载细分”的语义。
             */
            List<BillBreakdown> details =
                    Boolean.TRUE.equals(query.getInclude_breakdowns())
                            ? grouped.getOrDefault(
                            row.getId(),
                            Collections.emptyList()
                    )
                            : null;

            /*
             * 将数据库查询结果转换为接口返回对象。
             * 组装器还会负责金额格式化和剩余金额等处理。
             */
            list.add(assembler.toVO(row, details));
        }

        //组装最终分页响应并返回
        return new BillPageVO(
                summary.getTotal(),
                query.getPage(),
                query.getPage_size(),
                BillRules.money(summary.getIncomeTotal()),
                BillRules.money(summary.getExpenseTotal()),
                list
        );


    }


    /**
     * 根据账单ID查询当前用户的账单接口
     * <p>
     * 主要流程：
     * 1. 校验用户 ID
     * 2. 查询账单
     * 3. 查询账单细分
     * 4. 组装账单视图
     * 5. 返回账单视图
     */
    @Override
    public BillVO get(Long userId, Long billId) {
        //校验用户ID
        requireUserId(userId);

        //查询账单
        BillRow row = billMapper.findOwned(userId, billId);

        //查询账单细分
        List<BillBreakdown> details = breakdownMapper.findByBills(userId, Collections.singletonList(billId));

        //组装账单vo对象
        BillVO vo = assembler.toVO(row, details);

        //返回账单vo对象
        return vo;

    }


    /*
     *根据账单id列表批量删除账单以及关联表细分项
     *
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int delete(Long userId, List<Long> billIds) {
        // 删细分。
        breakdownMapper.delete(Wrappers.<BillBreakdown>lambdaQuery()
                .eq(BillBreakdown::getUserId, userId)
                .in(BillBreakdown::getBillId, billIds));
        //删主账单。)
        int deleted = billMapper.delete(Wrappers.<Bill>lambdaQuery()
                .eq(Bill::getUserId, userId)
                .in(Bill::getId, billIds)
        );

        // 统计删除的主账单数量并返回。
        return deleted;
    }


    /**
     * 更新账单及其显式细分，主表修改与细分替换在同一事务中完成。
     * <p>
     * 调用前提：userId 是可信身份，billId 是正数，dto 已通过 BillWriteDTO 校验。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BillVO update(Long userId, Long billId, BillWriteDTO dto) {
        //确认账单存在、且属于当前用户，并取得父账单排他锁。
        Bill bill = billMapper.lockOwned(userId, billId);
        if (bill == null) {
            throw new BusinessException(ResultCode.BILL_NOT_FOUND);
        }

        //检查日期范围是否规范和分类是否存在
        billRules.checkDate(dto.getBillDate());
        requireCategory(dto.getCategoryId());

        //传null->保留旧细分,传[]->清空列表
        List<BillBreakdownDTO> details = dto.getBreakdowns();
        BigDecimal sum = BigDecimal.ZERO;
        if (details == null) {
            //查询并累加得到旧细分的合计
            List<BillBreakdown> olddetails = breakdownMapper.selectList(
                    Wrappers.<BillBreakdown>lambdaQuery()
                            .eq(BillBreakdown::getUserId, userId)
                            .eq(BillBreakdown::getBillId, billId)
                            .orderByAsc(BillBreakdown::getSortOrder, BillBreakdown::getId));
            for (BillBreakdown b : olddetails) {
                sum = sum.add(b.getAmount());
            }
        } else {
            // 清空或替换分支只需汇总本次输入；空列表的合计为 0，无需查询旧细分。
            sum = sumInput(details);
        }
        billRules.checkSum(dto.getAmount(), sum);

        // 校验通过后才写库。只复制分类、金额、描述和日期，不覆盖 id 或 userId。
        int updated = billMapper.update(null, Wrappers.<Bill>lambdaUpdate()
                .eq(Bill::getId, bill.getId())
                .eq(Bill::getUserId, userId)              // 用户隔离条件保留
                .set(Bill::getCategoryId, dto.getCategoryId())
                .set(Bill::getAmount, dto.getAmount())
                .set(Bill::getDescription, dto.getDescription())
                .set(Bill::getBillDate, dto.getBillDate()));

        //细分项不为空则删除旧细分项,插入新细分项
        if (details != null) {
            // 删除旧细分项
            breakdownMapper.delete(Wrappers.<BillBreakdown>lambdaQuery()
                    .eq(BillBreakdown::getUserId, userId)
                    .eq(BillBreakdown::getBillId, billId));
            // 插入新细分项
            insertDetails(userId, billId, details);

        }
        return loadDetail(userId, billId);
    }

















    // ==================== 私有辅助方法 ====================

    /**
     * 确认指定分类存在，避免账单引用无效分类。
     *
     * @param categoryId 分类 ID
     */
    private void requireCategory(Long categoryId) {
        // 未找到分类时转换为稳定的业务错误码，不向控制器暴露持久层细节。
        if (categoryMapper.selectById(categoryId) == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }
    }

    /**
     * 汇总用户输入的所有细分金额；未传细分时按零处理。
     *
     * @param details 用户输入的账单细分
     * @return 细分金额合计
     */
    private BigDecimal sumInput(List<BillBreakdownDTO> details) {
        BigDecimal total = BigDecimal.ZERO;

        // null 表示请求未提供细分，不参与合计计算。
        if (details == null) {
            return total;
        }
        // DTO 校验已保证单项金额有效，此处只负责精确累加。
        for (BillBreakdownDTO detail : details) {
            total = total.add(detail.getAmount());
        }
        return total;
    }

    /**
     * 将允许写入的请求字段复制到主账单实体。
     *
     * @param bill 待写入的账单实体
     * @param dto  账单写入数据
     */
    private void applyFields(Bill bill, BillWriteDTO dto) {
        bill.setCategoryId(dto.getCategoryId());
        bill.setAmount(dto.getAmount());
        bill.setDescription(dto.getDescription());
        bill.setBillDate(dto.getBillDate());
    }

    /**
     * 按用户输入顺序保存账单细分，并校验每次写入结果。
     *
     * @param userId 当前登录用户 ID
     * @param billId 主账单 ID
     * @param detail 用户输入的账单细分
     */
    private void insertDetails(
            Long userId,
            Long billId,
            List<BillBreakdownDTO> detail
    ) {
        // 未提供细分时无需产生明细记录，主账单仍可独立保存。
        if (detail == null) {
            return;
        }

        // 使用输入下标作为排序值，确保后续查询能还原用户填写顺序。
        for (int i = 0; i < detail.size(); i++) {
            BillBreakdownDTO input = detail.get(i);

            BillBreakdown breakdown = new BillBreakdown();
            breakdown.setUserId(userId);
            breakdown.setBillId(billId);
            breakdown.setItemName(input.getItemName());
            breakdown.setAmount(input.getAmount());
            breakdown.setSortOrder(i);

            int insertedRows = breakdownMapper.insert(breakdown);

            // 单条明细必须恰好写入一行，否则抛出异常触发整个新增事务回滚。
            if (insertedRows != 1) {
                throw new IllegalStateException("新增细分失败");
            }

        }

    }

    /**
     * 按用户归属重新读取主账单与细分，并组装完整账单视图。
     *
     * @param userId 当前登录用户 ID
     * @param billId 账单 ID
     * @return 完整账单视图
     */
    private BillVO loadDetail(Long userId, Long billId) {
        // 查询条件同时包含用户 ID，防止读取到其他用户的同 ID 数据。
        BillRow row = billMapper.findOwned(userId, billId);

        if (row == null) {
            throw new BusinessException(ResultCode.BILL_NOT_FOUND);
        }

        // 细分同样按用户归属查询，再由组装器统一格式化金额并补齐系统项。
        List<BillBreakdown> breakdowns = breakdownMapper.findByBills(userId, Collections.singletonList(billId));

        return assembler.toVO(row, breakdowns);
    }


    //检查用户ID是否合法
    private void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
    }


}


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
 * 账单业务实现类：协调规则校验、主账单与细分持久化以及返回对象组装。
 * <p>
 * 所有公开方法都以 userId 作为第一参数，并在持久层查询条件中带上 userId，
 * 保证每个用户只能读写自己的账单数据（用户隔离）。
 */
@Service
public class BillServiceImpl implements BillService {

    // ==================== 依赖注入组件 ====================

    // 主账单持久层：负责主账单表的增删改查。
    private final BillMapper billMapper;
    // 账单细分持久层：负责细分表的增删改查。
    private final BillBreakdownMapper breakdownMapper;
    // 分类持久层：用于校验账单引用的分类是否存在。
    private final CategoryMapper categoryMapper;
    // 账单领域规则组件：统一处理日期边界、细分合计与金额格式校验。
    private final BillRules billRules;
    // 账单视图组装组件：把持久层查询结果组装成接口返回对象。
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
     * 新增账单：校验日期与分类 → 校验细分合计 → 保存主账单与细分 → 组装返回。
     *
     * @param userId 当前登录用户 ID
     * @param dto    账单写入数据
     * @return 新增后的完整账单信息
     */
    @Override
    // 主账单与细分必须同生共死，因此包在同一个事务里：
    // rollbackFor = Exception.class 保证业务异常也回滚，READ_COMMITTED 隔离级别防止脏读。
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BillVO create(Long userId, BillWriteDTO dto) {
        // 校验账单日期：不能晚于今天，也不能早于 1000-01-01。
        billRules.checkDate(dto.getBillDate());
        // 校验分类必须存在，防止账单引用无效分类。
        requireCategory(dto.getCategoryId());

        // 先汇总用户提交的细分金额（未传细分时按 0 处理）。
        BigDecimal breakdownTotal = sumInput(dto.getBreakdowns());
        // 校验细分金额合计不能超过账单总金额。
        billRules.checkSum(dto.getAmount(), breakdownTotal);

        // 构造主账单实体：绑定归属用户，并拷贝请求中的业务字段。
        Bill bill = new Bill();
        bill.setUserId(userId);
        applyFields(bill, dto);

        // 保存主账单，并核对受影响行数与数据库回填的主键。
        int insertedRows = billMapper.insert(bill);
        if (insertedRows != 1 || bill.getId() == null) {
            // 插入行数不为 1 或未回填主键说明写入异常，抛异常触发整体回滚。
            throw new IllegalStateException("新增账单未取得主键");
        }

        // 按用户填写顺序逐条写入细分，与主账单同事务，任一失败整体回滚。
        insertDetails(userId, bill.getId(), dto.getBreakdowns());

        // 重新查询刚写入的数据并组装完整视图返回。
        return loadDetail(userId, bill.getId());
    }

    /**
     * 分页查询当前用户的账单列表。
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
        // 校验用户 ID 必须为正数。
        requireUserId(userId);
        // 同时提供了起止日期时，校验开始日期不能晚于结束日期。
        if (query.getStart_date() != null && query.getEnd_date() != null
                && query.getStart_date().isAfter(query.getEnd_date())) {
            throw new BusinessException(ResultCode.PARAM_INVALID.getCode(),
                    "start_date 不能晚于 end_date");
        }

        // 计算分页偏移量：页码从 1 开始，第 page 页需跳过前 (page-1)*page_size 条记录；
        // 转成 long 计算，避免 int 相乘溢出。
        long offset = ((long) query.getPage() - 1L) * query.getPage_size();

        // 一次 SQL 统计账单总数、收入总额和支出总额。
        BillSummary summary = billMapper.summarize(userId, query);

        // 查询当前页账单；offset 已超出总条数时直接返回空列表，省掉无意义的 SQL。
        List<BillRow> rows = offset >= summary.getTotal()
                ? Collections.emptyList()
                : billMapper.findPage(userId, query, offset);

        // 用账单 ID 对细分记录分组，Map 结构：{账单ID1: [细分1, 细分2], 账单ID2: [细分1]}。
        Map<Long, List<BillBreakdown>> grouped = Collections.emptyMap();
        // 只有调用方明确要求包含细分且当前页有账单时才查询细分，避免不必要的查询。
        if (Boolean.TRUE.equals(query.getInclude_breakdowns()) && !rows.isEmpty()) {
            // 收集当前页所有账单 ID，供一次 IN 查询批量取细分。
            List<Long> ids = rows.stream()
                    .map(BillRow::getId)
                    .collect(Collectors.toList());
            // 一次性查询当前页所有账单的细分，再按 billId 分组，避免 N+1 查询。
            grouped = breakdownMapper.findByBills(userId, ids)
                    .stream()
                    .collect(Collectors.groupingBy(BillBreakdown::getBillId));
        }

        // 逐条把数据库行模型组装成 BillVO 列表。
        List<BillVO> list = new ArrayList<>();
        for (BillRow row : rows) {
            // 请求要求包含细分时，从 grouped 取当前账单的细分，没有则用空列表；
            // 未要求时传 null，让组装器在响应中保留“未加载细分”的语义。
            List<BillBreakdown> details =
                    Boolean.TRUE.equals(query.getInclude_breakdowns())
                            ? grouped.getOrDefault(
                            row.getId(),
                            Collections.emptyList()
                    )
                            : null;
            // 交给组装器把数据库行转换为接口对象，并统一格式化金额、补齐剩余金额。
            list.add(assembler.toVO(row, details));
        }

        // 汇总统计信息与当前页数据，组装最终分页响应。
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
     * 根据账单 ID 查询当前用户的账单详情。
     * <p>
     * 主要流程：
     * 1. 校验用户 ID；
     * 2. 查询账单；
     * 3. 查询账单细分；
     * 4. 组装账单视图；
     * 5. 返回账单视图。
     */
    @Override
    public BillVO get(Long userId, Long billId) {
        // 校验用户 ID 必须为正数。
        requireUserId(userId);
        // 查询主账单；条件同时带用户 ID，防止越权读取他人账单。
        BillRow row = billMapper.findOwned(userId, billId);
        // 查询该账单下的全部细分记录。
        List<BillBreakdown> details = breakdownMapper.findByBills(userId, Collections.singletonList(billId));
        // 组装完整账单视图（含金额格式化与剩余金额补齐）。
        BillVO vo = assembler.toVO(row, details);
        // 返回账单视图。
        return vo;
    }

    /**
     * 批量删除当前用户名下的账单及其关联细分。
     * <p>
     * 先删细分、再删主账单：细分通过 bill_id 关联主账单，
     * 不先清理细分会留下孤儿数据；两步同处一个事务，保证原子性。
     *
     * @param userId  当前登录用户 ID
     * @param billIds 待删除的账单 ID 列表
     * @return 实际删除的主账单行数
     */
    @Override
    // 与新增/更新一致：整个删除过程包在一个事务里，任一步失败整体回滚。
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int delete(Long userId, List<Long> billIds) {
        // 第一步：删除这些账单下的全部细分；同时带 userId 条件，防止误删他人数据。
        breakdownMapper.delete(Wrappers.<BillBreakdown>lambdaQuery()
                .eq(BillBreakdown::getUserId, userId)
                .in(BillBreakdown::getBillId, billIds));
        // 第二步：删除主账单，受影响行数即为删除数量。
        return billMapper.delete(Wrappers.<Bill>lambdaQuery()
                .eq(Bill::getUserId, userId)
                .in(Bill::getId, billIds)
        );
    }

    /**
     * 更新账单及其细分：主表修改与细分替换在同一事务中完成。
     * <p>
     * 调用前提：userId 是可信身份，billId 是正数，dto 已通过 BillWriteDTO 校验。
     * <p>
     * 细分更新语义：dto.breakdowns 为 null 保留旧细分，为空列表清空，否则整体替换。
     *
     * @param userId 当前登录用户 ID
     * @param billId 待更新的账单 ID
     * @param dto    账单写入数据
     * @return 更新后的完整账单信息
     */
    @Override
    // 主表更新与细分替换必须同时成功或同时失败，因此包在同一个事务里。
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public BillVO update(Long userId, Long billId, BillWriteDTO dto) {
        // 加锁查询主账单：确认账单存在且属于当前用户，行级锁防止并发修改互相覆盖。
        Bill bill = billMapper.lockOwned(userId, billId);
        if (bill == null) {
            // 不存在或不属于当前用户，统一报“账单不存在”，不泄露数据归属信息。
            throw new BusinessException(ResultCode.BILL_NOT_FOUND);
        }

        // 校验新日期与新分类，与新增接口使用同一套规则。
        billRules.checkDate(dto.getBillDate());
        requireCategory(dto.getCategoryId());

        // 细分更新语义：传 null 保留旧细分；传空列表清空；否则整体替换。
        List<BillBreakdownDTO> details = dto.getBreakdowns();
        BigDecimal sum = BigDecimal.ZERO;
        if (details == null) {
            // 保留旧细分：查出旧细分并累加合计，供下面的金额校验使用。
            List<BillBreakdown> olddetails = breakdownMapper.selectList(
                    Wrappers.<BillBreakdown>lambdaQuery()
                            .eq(BillBreakdown::getUserId, userId)
                            .eq(BillBreakdown::getBillId, billId)
                            .orderByAsc(BillBreakdown::getSortOrder)
                            .orderByAsc(BillBreakdown::getId));
            for (BillBreakdown b : olddetails) {
                sum = sum.add(b.getAmount());
            }
        } else {
            // 替换/清空：只汇总本次输入；空列表合计为 0，无需查询旧细分。
            sum = sumInput(details);
        }
        // 校验细分合计不能超过新的账单总金额。
        billRules.checkSum(dto.getAmount(), sum);

        // 校验通过后才写库：只更新分类、金额、描述、日期四个业务字段，
        // 不覆盖 id 与 userId，并保留 userId 条件守住用户隔离。
        int updated = billMapper.update(null, Wrappers.<Bill>lambdaUpdate()
                .eq(Bill::getId, bill.getId())
                .eq(Bill::getUserId, userId)              // 用户隔离条件保留
                .set(Bill::getCategoryId, dto.getCategoryId())
                .set(Bill::getAmount, dto.getAmount())
                .set(Bill::getDescription, dto.getDescription())
                .set(Bill::getBillDate, dto.getBillDate()));

        // 只有请求里带了细分字段才动细分表：先删旧、再插新，与主表更新同事务。
        if (details != null) {
            // 删除旧细分项。
            breakdownMapper.delete(Wrappers.<BillBreakdown>lambdaQuery()
                    .eq(BillBreakdown::getUserId, userId)
                    .eq(BillBreakdown::getBillId, billId));
            // 按用户填写顺序插入新细分项。
            insertDetails(userId, billId, details);

        }
        // 重新读取更新后的完整数据（含细分）并返回。
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
        // DTO 校验已保证单项金额有效，此处只用 BigDecimal 精确累加。
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
        // 只复制分类、金额、描述、日期四个业务字段，id 与 userId 由调用方控制。
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
            // 单条明细必须恰好写入一行，否则抛出异常触发整个事务回滚。
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

    /**
     * 校验用户 ID 必须为正数；非法值视为未登录身份。
     *
     * @param userId 待校验的用户 ID
     */
    private void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
    }
}

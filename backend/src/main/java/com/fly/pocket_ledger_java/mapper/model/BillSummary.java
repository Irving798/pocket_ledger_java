package com.fly.pocket_ledger_java.mapper.model;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BillSummary {
    private long total;
    private BigDecimal incomeTotal;
    private BigDecimal expenseTotal;
}
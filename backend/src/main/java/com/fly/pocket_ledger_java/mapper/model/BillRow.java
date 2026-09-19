package com.fly.pocket_ledger_java.mapper.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BillRow {
    private Long id;
    private Long categoryId;
    private String categoryName;
    private String type;
    private BigDecimal amount;
    private String description;
    private LocalDate billDate;
}
package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.dto.BillQueryDTO;
import com.fly.pocket_ledger_java.exception.GlobalExceptionHandler;
import com.fly.pocket_ledger_java.service.BillService;
import com.fly.pocket_ledger_java.util.LoginUser;
import com.fly.pocket_ledger_java.util.UserContext;
import com.fly.pocket_ledger_java.vo.BillPageVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账单列表接口切片测试。当前列表查询仅接收 snake_case 参数，
 * camelCase 写法不生效；非法参数按契约返回 422，不进入 Service。
 */
class BillControllerWebTest {

    private MockMvc mockMvc;

    private BillService billService;

    @BeforeEach
    void setUp() {
        billService = mock(BillService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new BillController(billService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        UserContext.set(new LoginUser(7L, "reed"));
        when(billService.list(eq(7L), any(BillQueryDTO.class)))
                .thenReturn(new BillPageVO(0, 1, 25, "0.00", "0.00", Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void bindsSnakeCaseQueryParameters() throws Exception {
        mockMvc.perform(get("/bills/list")
                        .param("start_date", "2026-09-01")
                        .param("end_date", "2026-09-09")
                        .param("bill_type", "expense")
                        .param("category_id", "3")
                        .param("page", "2")
                        .param("page_size", "25")
                        .param("order", "asc")
                        .param("include_breakdowns", "true"))
                .andExpect(status().isOk());

        BillQueryDTO query = captureListQuery();
        assertEquals(LocalDate.of(2026, 9, 1), query.getStart_date());
        assertEquals(LocalDate.of(2026, 9, 9), query.getEnd_date());
        assertEquals("expense", query.getBill_type());
        assertEquals(Long.valueOf(3), query.getCategory_id());
        assertEquals(Integer.valueOf(2), query.getPage());
        assertEquals(Integer.valueOf(25), query.getPage_size());
        assertEquals("asc", query.getOrder());
        assertEquals(Boolean.TRUE, query.getInclude_breakdowns());
    }

    @Test
    void appliesDefaultsWhenNoParametersGiven() throws Exception {
        mockMvc.perform(get("/bills/list"))
                .andExpect(status().isOk());

        BillQueryDTO query = captureListQuery();
        assertNull(query.getStart_date());
        assertNull(query.getEnd_date());
        assertNull(query.getCategory_id());
        assertNull(query.getBill_type());
        assertEquals(Integer.valueOf(1), query.getPage());
        assertEquals(Integer.valueOf(10), query.getPage_size());
        assertEquals("desc", query.getOrder());
        assertEquals(Boolean.FALSE, query.getInclude_breakdowns());
    }

    @Test
    void ignoresCamelCaseQueryFields() throws Exception {
        mockMvc.perform(get("/bills/list")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-09")
                        .param("categoryId", "3")
                        .param("billType", "expense")
                        .param("pageSize", "25")
                        .param("includeBreakdowns", "true"))
                .andExpect(status().isOk());

        BillQueryDTO query = captureListQuery();
        assertNull(query.getStart_date());
        assertNull(query.getEnd_date());
        assertNull(query.getCategory_id());
        assertNull(query.getBill_type());
        assertEquals(Integer.valueOf(10), query.getPage_size());
        assertEquals(Boolean.FALSE, query.getInclude_breakdowns());
    }

    @Test
    void readsOnlySnakeCaseWhenBothNamingStylesArePresent() throws Exception {
        mockMvc.perform(get("/bills/list")
                        .param("startDate", "2026-09-02")
                        .param("start_date", "2026-09-01")
                        .param("billType", "income")
                        .param("bill_type", "expense")
                        .param("categoryId", "2")
                        .param("category_id", "3")
                        .param("pageSize", "20")
                        .param("page_size", "25")
                        .param("includeBreakdowns", "false")
                        .param("include_breakdowns", "true"))
                .andExpect(status().isOk());

        BillQueryDTO query = captureListQuery();
        assertEquals(LocalDate.of(2026, 9, 1), query.getStart_date());
        assertEquals("expense", query.getBill_type());
        assertEquals(Long.valueOf(3), query.getCategory_id());
        assertEquals(Integer.valueOf(25), query.getPage_size());
        assertEquals(Boolean.TRUE, query.getInclude_breakdowns());
    }

    @Test
    void rejectsInvalidQueryParametersWith422() throws Exception {
        String[][] invalidParams = {
                {"bill_type", "other"},
                {"page", "0"},
                {"page_size", "101"},
                {"category_id", "0"},
                {"start_date", "not-a-date"},
        };
        for (String[] pair : invalidParams) {
            mockMvc.perform(get("/bills/list")
                            .accept(MediaType.APPLICATION_JSON)
                            .param(pair[0], pair[1]))
                    .andExpect(status().isUnprocessableEntity());
        }

        verify(billService, never()).list(any(), any());
    }

    private BillQueryDTO captureListQuery() {
        ArgumentCaptor<BillQueryDTO> captor = ArgumentCaptor.forClass(BillQueryDTO.class);
        verify(billService).list(eq(7L), captor.capture());
        return captor.getValue();
    }
}

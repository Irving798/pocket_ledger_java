package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.interceptor.AuthInterceptor;
import com.fly.pocket_ledger_java.service.CategoryService;
import com.fly.pocket_ledger_java.vo.CategoryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller 层切片测试（契约对齐后：code=200、message="操作成功"、校验失败 422、路径 /categories）。
 * Boot 2.x 用 @MockBean（org.springframework.boot.test.mock.mockito.MockBean）；
 * Boot 3.4 起改为 @MockitoBean，两个注解不要混用。
 * MockMvc 传 query 参数用 .param()（.queryParam() 是 Spring 6 才有的）。
 *
 * @WebMvcTest 会加载 WebMvcConfig，进而要求 AuthInterceptor Bean，
 * 所以必须 @MockBean 并 stub preHandle 返回 true（Mockito 的 boolean 默认值是 false，
 * 不 stub 的话拦截器会把所有请求 401 掉——见 docs/auth-guide.md 步骤 12）。
 */
@WebMvcTest(CategoryController.class)
class CategoryControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CategoryService categoryService;

    @MockBean
    private AuthInterceptor authInterceptor;

    @BeforeEach
    void letRequestsPassThrough() throws Exception {
        when(authInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void listsAllCategoriesWhenTypeIsAbsent() throws Exception {
        when(categoryService.listCategories(null)).thenReturn(Arrays.asList(
                new CategoryVO(1L, "餐饮", "expense", "food", 10)
        ));

        mockMvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("餐饮"));

        verify(categoryService).listCategories(null);
    }

    @Test
    void filtersCategoriesByType() throws Exception {
        when(categoryService.listCategories("income")).thenReturn(Arrays.asList(
                new CategoryVO(2L, "工资", "income", "salary", 20)
        ));

        mockMvc.perform(get("/categories").param("type", "income"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("income"));

        verify(categoryService).listCategories("income");
    }

    @Test
    void rejectsUnsupportedCategoryType() throws Exception {
        mockMvc.perform(get("/categories").param("type", "other"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message").value("参数错误：type 必须为 income 或 expense"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(categoryService, never()).listCategories("other");
    }

    @Test
    void allowsCrossOriginRequestsFromAnyOrigin() throws Exception {
        when(categoryService.listCategories(null)).thenReturn(Arrays.asList(
                new CategoryVO(1L, "餐饮", "expense", "food", 10)
        ));

        mockMvc.perform(get("/categories")
                        .header(HttpHeaders.ORIGIN, "https://demo.example"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"));
    }

    @Test
    void allowsPreflightForAnyMethodAndHeaders() throws Exception {
        mockMvc.perform(options("/categories")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("DELETE")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Content-Type")));
    }
}

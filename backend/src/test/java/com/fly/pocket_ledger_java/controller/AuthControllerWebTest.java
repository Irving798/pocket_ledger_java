package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.exception.BusinessException;
import com.fly.pocket_ledger_java.interceptor.AuthInterceptor;
import com.fly.pocket_ledger_java.service.AuthService;
import com.fly.pocket_ledger_java.vo.TokenVO;
import com.fly.pocket_ledger_java.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口切片测试，按契约（docs/pocket-ledger-api.html）断言响应结构：
 * 注册只返回 {id, username}、登录返回 {access_token, token_type}、
 * 账号密码错误 401、参数校验失败 422（"参数错误：字段 原因"）。
 * 拦截器同 CategoryControllerWebTest：@MockBean + stub 放行。
 */
@WebMvcTest(AuthController.class)
class AuthControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private AuthInterceptor authInterceptor;

    @BeforeEach
    void letRequestsPassThrough() throws Exception {
        when(authInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void registerReturnsUserWithoutToken() throws Exception {
        when(authService.register(any())).thenReturn(new UserVO(1L, "reed"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reed\",\"password\":\"12345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("注册成功"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value("reed"))
                // 契约：注册只建号，不返回任何 token 字段
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.access_token").doesNotExist());
    }

    @Test
    void registerConflictReturns409() throws Exception {
        when(authService.register(any()))
                .thenThrow(new BusinessException(ResultCode.USERNAME_OCCUPIED));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reed\",\"password\":\"12345678\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("用户名已被占用"));
    }

    @Test
    void registerValidationFailureReturns422() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"password\":\"12345\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.message", containsString("参数错误")));
    }

    @Test
    void loginReturnsAccessToken() throws Exception {
        when(authService.login(any())).thenReturn(new TokenVO("jwt-token"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reed\",\"password\":\"12345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.access_token").value("jwt-token"))
                .andExpect(jsonPath("$.data.token_type").value("bearer"));
    }

    @Test
    void loginBadCredentialsReturns401() throws Exception {
        when(authService.login(any()))
                .thenThrow(new BusinessException(ResultCode.BAD_CREDENTIALS));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reed\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void removedLogoutEndpointReturns404() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isNotFound());
    }

    @Test
    void meReturnsOnlyIdAndUsername() throws Exception {
        when(authService.getCurrentUser()).thenReturn(new UserVO(1L, "reed"));

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value("reed"))
                // 契约：当前用户只暴露 id、username
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
    }
}

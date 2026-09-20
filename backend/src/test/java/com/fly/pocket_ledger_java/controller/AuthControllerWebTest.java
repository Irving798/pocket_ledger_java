package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.exception.BusinessException;
import com.fly.pocket_ledger_java.interceptor.AuthInterceptor;
import com.fly.pocket_ledger_java.service.AuthService;
import com.fly.pocket_ledger_java.vo.TokenVO;
import com.fly.pocket_ledger_java.vo.UserVO;
// 捕获真实绑定结果，避免只验证预设的 mock 响应。
import com.fly.pocket_ledger_java.dto.RegisterDTO;
import com.fly.pocket_ledger_java.dto.UserProfileUpdateDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.mock.web.MockMultipartFile;
import org.mockito.InOrder;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
        when(authService.register(any())).thenReturn(new UserVO(1L, "reed","xxx","null","null"));

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
        when(authService.getCurrentUser()).thenReturn(new UserVO(1L, "reed","xxx","null","null" ));

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value("reed"))
                // 契约：当前用户只暴露 id、username
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
    }

    @Test
    void registerBindsOptionalProfile() throws Exception {
        when(authService.register(any())).thenReturn(
                new UserVO(1L, "reed", "小飞", null, "Reed@example.com"));
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reed\",\"password\":\"12345678\","
                                + "\"nickname\":\" 小飞 \",\"email\":\" Reed@example.com \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("小飞"));
        ArgumentCaptor<RegisterDTO> captor = ArgumentCaptor.forClass(RegisterDTO.class);
        verify(authService).register(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("小飞");
        assertThat(captor.getValue().getEmail()).isEqualTo("Reed@example.com");
    }

    @Test
    void emptyProfileClearsBothFields() throws Exception {
        when(authService.updateCurrentUser(any())).thenReturn(
                new UserVO(1L, "reed", null, null, null));
        String response = mockMvc.perform(put("/auth/me")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ArgumentCaptor<UserProfileUpdateDTO> captor =
                ArgumentCaptor.forClass(UserProfileUpdateDTO.class);
        verify(authService).updateCurrentUser(captor.capture());
        assertThat(captor.getValue().getNickname()).isNull();
        assertThat(captor.getValue().getEmail()).isNull();
        // has + isNull 能区分“字段缺失”和“字段存在且为 null”。
        com.fasterxml.jackson.databind.JsonNode data = new ObjectMapper().readTree(response).get("data");
        assertThat(data.has("avatar_url")).isTrue();
        assertThat(data.get("avatar_url").isNull()).isTrue();
        assertThat(data.has("password_hash")).isFalse();
        assertThat(data.has("avatar_object_key")).isFalse();
    }

    @Test
    void invalidEmailNeverReachesService() throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reed\",\"password\":\"12345678\",\"email\":\"bad\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/auth/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad\"}"))
                .andExpect(status().isUnprocessableEntity());
        verify(authService, never()).register(any());
        verify(authService, never()).updateCurrentUser(any());
    }

    @Test
    void forbiddenFieldsAreRejected() throws Exception {
        // 包括任意未知字段；不能只禁止某一个 user_id 写法。
        for (String field : new String[]{"id", "user_id", "avatar_url", "avatar_object_key", "other"}) {
            mockMvc.perform(put("/auth/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"" + field + "\":\"x\"}"))
                    .andExpect(status().isUnprocessableEntity());
            mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"reed\",\"password\":\"12345678\",\"" + field + "\":\"x\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }
        verify(authService, never()).register(any());
        verify(authService, never()).updateCurrentUser(any());
    }

    @Test
    void avatarWritesBeforeReadingResponse() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        when(authService.getCurrentUser()).thenReturn(
                new UserVO(1L, "reed", "小飞", "https://avatar.example.com/a.png", null));
        mockMvc.perform(multipart("/auth/me/avatar").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatar_url").value("https://avatar.example.com/a.png"))
                .andExpect(jsonPath("$.data.avatarObjectKey").doesNotExist());
        InOrder order = inOrder(authService);
        order.verify(authService).replaceAvatar(any());
        order.verify(authService).getCurrentUser();
    }

    @Test
    void multipartRejectsMissingRepeatedAndExtraFields() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        mockMvc.perform(multipart("/auth/me/avatar")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(multipart("/auth/me/avatar").file(file).file(file))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(multipart("/auth/me/avatar").file(file)
                        .file(new MockMultipartFile("other", new byte[]{1})))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(multipart("/auth/me/avatar").file(file).param("user_id", "2"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/auth/me/avatar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        verify(authService, never()).replaceAvatar(any());
    }

    @Test
    void uploadFailureDoesNotReadSuccessResponse() throws Exception {
        doThrow(new BusinessException(ResultCode.AVATAR_STORAGE_UNAVAILABLE))
                .when(authService).replaceAvatar(any());
        mockMvc.perform(multipart("/auth/me/avatar")
                        .file(new MockMultipartFile("file", new byte[]{1})))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
        verify(authService, never()).getCurrentUser();
    }

    @Test
    void removalWritesBeforeReadingResponse() throws Exception {
        when(authService.getCurrentUser()).thenReturn(new UserVO(1L, "reed", null, null, null));
        mockMvc.perform(delete("/auth/me/avatar")).andExpect(status().isOk());
        InOrder order = inOrder(authService);
        order.verify(authService).removeAvatar();
        order.verify(authService).getCurrentUser();
    }
}

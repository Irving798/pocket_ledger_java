package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.dto.LoginDTO;
import com.fly.pocket_ledger_java.dto.RegisterDTO;
import com.fly.pocket_ledger_java.service.AuthService;
import com.fly.pocket_ledger_java.vo.ApiResponse;
import com.fly.pocket_ledger_java.vo.TokenVO;
import com.fly.pocket_ledger_java.vo.UserVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 认证接口，路径对齐契约：/auth/register、/auth/login、/auth/me（无 /api 前缀）。
 * register、login 在 auth.ignore-urls 白名单里，无需登录。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * 注入认证业务服务。
     *
     * @param authService 认证业务服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册（公开接口）：契约只建号，返回 {id, username}，成功文案"注册成功"
     */
    @PostMapping("/register")
    public ApiResponse<UserVO> register(@Valid @RequestBody RegisterDTO dto) {
        return ApiResponse.success("注册成功", authService.register(dto));
    }

    /**
     * 登录（公开接口）：返回 {access_token, token_type:"bearer"}
     */
    @PostMapping("/login")
    public ApiResponse<TokenVO> login(@Valid @RequestBody LoginDTO dto) {
        return ApiResponse.success(authService.login(dto));
    }

    /**
     * 当前登录用户（需登录）：演示如何取用鉴权上下文
     */
    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.success(authService.getCurrentUser());
    }
}

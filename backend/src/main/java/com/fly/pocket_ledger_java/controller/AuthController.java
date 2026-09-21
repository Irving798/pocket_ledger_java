package com.fly.pocket_ledger_java.controller;

import com.fly.pocket_ledger_java.dto.LoginDTO;
import com.fly.pocket_ledger_java.dto.RegisterDTO;
import com.fly.pocket_ledger_java.service.AuthService;
import com.fly.pocket_ledger_java.vo.ApiResponse;
import com.fly.pocket_ledger_java.vo.TokenVO;
import com.fly.pocket_ledger_java.vo.UserVO;
import com.fly.pocket_ledger_java.dto.UserProfileUpdateDTO;
import com.fly.pocket_ledger_java.exception.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.PutMapping;
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
     * 获取当前登录用户（需登录）
     */
    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.success(authService.getCurrentUser());
    }

    /** 更新昵称和邮箱，身份由认证拦截器提供。 */
    @PutMapping("/me")
    public ApiResponse<UserVO> updateMe(@Valid @RequestBody UserProfileUpdateDTO dto) {
        return ApiResponse.success("资料更新成功", authService.updateCurrentUser(dto));
    }

    /** 上传更新用户信息的头像 */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserVO> uploadAvatar(MultipartHttpServletRequest request) {
        MultipartFile file = requireSingleAvatar(request);
        authService.replaceAvatar(file);
        return ApiResponse.success("头像更新成功", authService.getCurrentUser());
    }
    /** 删除用户信息的头像 */
    @DeleteMapping("/me/avatar")
    public ApiResponse<UserVO> removeAvatar() {
        // 先完成变更再读取；变更失败不得继续生成成功响应。
        authService.removeAvatar();
        return ApiResponse.success("已恢复默认头像", authService.getCurrentUser());
    }










    // ==================== 私有辅助方法 ====================

    //校验头像
    private MultipartFile requireSingleAvatar(MultipartHttpServletRequest request) {
        List<MultipartFile> files = request.getFiles("file");
        if (request.getMultiFileMap().size() != 1 || files.size() != 1
                || !request.getParameterMap().isEmpty()) {
            // 拒绝重名文件、额外文件字段和文本字段（包括 URL 查询参数）。
            throw new BusinessException(422, "只允许上传一个名为 file 的头像文件，不接受额外字段");
        }
        if (files.get(0).isEmpty()) throw new BusinessException(422, "请选择非空头像文件");
        return files.get(0);
    }
}

package com.fly.pocket_ledger_java.service;

import com.fly.pocket_ledger_java.dto.LoginDTO;
import com.fly.pocket_ledger_java.dto.RegisterDTO;
import com.fly.pocket_ledger_java.vo.TokenVO;
import com.fly.pocket_ledger_java.vo.UserVO;

/**
 * 认证业务接口，定义注册、登录及当前用户查询。
 */
public interface AuthService {

    /**
     * 注册新用户；仅创建账号，不签发访问令牌。
     *
     * @param dto 注册信息
     * @return 新建用户的公开信息
     */
    UserVO register(RegisterDTO dto);

    /**
     * 校验登录凭据并签发访问令牌。
     *
     * @param dto 登录凭据
     * @return 访问令牌信息
     */
    TokenVO login(LoginDTO dto);

    /**
     * 查询当前鉴权上下文对应的用户。
     *
     * @return 当前用户的公开信息
     */
    UserVO getCurrentUser();
}

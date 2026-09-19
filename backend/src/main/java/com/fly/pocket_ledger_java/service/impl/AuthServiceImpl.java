package com.fly.pocket_ledger_java.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.dto.LoginDTO;
import com.fly.pocket_ledger_java.dto.RegisterDTO;
import com.fly.pocket_ledger_java.entity.User;
import com.fly.pocket_ledger_java.exception.BusinessException;
import com.fly.pocket_ledger_java.mapper.UserMapper;
import com.fly.pocket_ledger_java.service.AuthService;
import com.fly.pocket_ledger_java.util.JwtUtils;
import com.fly.pocket_ledger_java.util.UserContext;
import com.fly.pocket_ledger_java.vo.TokenVO;
import com.fly.pocket_ledger_java.vo.UserVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证业务实现，协调用户持久化、密码校验与令牌签发。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    /**
     * 注入认证流程所需的持久化与安全组件。
     *
     * @param userMapper 用户持久层
     * @param passwordEncoder 密码编码器
     * @param jwtUtils 令牌生成工具
     */
    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder,
                           JwtUtils jwtUtils) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
    }

    // ==================== 注册 ====================
    /**
     * 完成用户名查重、密码加密与用户入库，并返回不含敏感字段的用户信息。
     *
     * @param dto 注册信息
     * @return 新建用户的公开信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterDTO dto) {
        // 1. 业务查重：先给用户一个友好的"用户名已被占用"
        Long count = userMapper.selectCount(
                Wrappers.lambdaQuery(User.class).eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException(ResultCode.USERNAME_OCCUPIED);
        }

        // 2. bcrypt 加密后入库。encode 每次产出不同哈希（内含随机盐），切勿自己比对哈希字符串
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 3. 并发兜底：两个请求同时通过查重、同时 insert，靠 uniq_username 唯一索引拦截
            throw new BusinessException(ResultCode.USERNAME_OCCUPIED);
        }

        // 4. 契约：注册只建号，返回 {id, username}，不发 token（用户注册后走登录接口）
        return toVO(user);
    }

    // ==================== 登录 ====================
    /**
     * 校验用户名与密码，并为合法用户签发访问令牌。
     *
     * @param dto 登录凭据
     * @return 新签发的访问令牌
     */
    @Override
    @Transactional(readOnly = true)
    public TokenVO login(LoginDTO dto) {
        User user = userMapper.selectOne(
                Wrappers.lambdaQuery(User.class).eq(User::getUsername, dto.getUsername()));
        // 契约：401。用户不存在和密码错误返回同一句话：防止攻击者靠不同报错探测"哪些用户名已注册"
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.BAD_CREDENTIALS);
        }
        return new TokenVO(jwtUtils.generateToken(user.getId(), user.getUsername()));
    }

    // ==================== 当前用户 ====================
    /**
     * 根据鉴权上下文中的用户 ID 查询当前用户。
     *
     * @return 当前用户的公开信息
     */
    @Override
    @Transactional(readOnly = true)
    public UserVO getCurrentUser() {
        // userId 来自拦截器写入的 ThreadLocal 上下文——这就是"鉴权工具"在业务里的日常用法
        User user = userMapper.selectById(UserContext.getUserId());
        // 契约：token 有效但用户已不存在也统一 401，不向外透露多余信息
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return toVO(user);
    }

    // ==================== 私有辅助方法 ====================
    /**
     * 将用户实体转换为公开视图，仅保留 ID 与用户名，避免暴露密码哈希。
     *
     * @param user 用户实体
     * @return 用户公开视图
     */
    private UserVO toVO(User user) {
        return new UserVO(user.getId(), user.getUsername());
    }
}

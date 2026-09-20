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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fly.pocket_ledger_java.dto.UserProfileUpdateDTO;
import org.springframework.beans.factory.annotation.Value;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.model.ObjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 认证业务实现，协调用户持久化、密码校验与令牌签发。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    /** 浏览器可访问的公共域名，不能配置为 OSS 内网 Endpoint。 */
    @Value("${aliyun.oss.public-base-url:}")
    private String publicBaseUrl;
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private final OSS ossClient;

    /** Bucket 与前缀只来自服务端配置。 */
    @Value("${aliyun.oss.bucket}")
    private String bucket;

    @Value("${aliyun.oss.avatar-prefix:avatars/}")
    private String avatarPrefix;

    /**
     * 注入认证流程所需的持久化与安全组件。
     *
     * @param userMapper 用户持久层
     * @param passwordEncoder 密码编码器
     * @param jwtUtils 令牌生成工具
     */
    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder,
                           JwtUtils jwtUtils, OSS ossClient) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.ossClient = ossClient;
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
        user.setNickname(dto.getNickname());
        user.setEmail(dto.getEmail());
        user.setAvatarObjectKey(null);

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
        // 注册、查询、更新共用同一个响应转换入口。
        return toVO(requireCurrentUser());
    }


    //更新邮箱或昵称
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateCurrentUser(UserProfileUpdateDTO dto) {
        User user = requireCurrentUser();
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
        update.eq(User::getId, user.getId())
                .set(User::getNickname, dto.getNickname())
                .set(User::getEmail, dto.getEmail());
        // 不能 updateById(旧实体)，否则会覆盖并发修改的头像；显式 null 表示清空。
        userMapper.update(null, update);
        // 原样保存可能影响 0 行，因此不直接把 0 当成失败；重新查询确认用户存在。
        return toVO(requireCurrentUser());
    }


    //更新头像
    @Override
    public void replaceAvatar(MultipartFile file) {
        User user = requireCurrentUser();
        byte[] content = readAvatar(file);
        String extension = avatarExtension(content);
        String operationId = UUID.randomUUID().toString();
        String oldKey = user.getAvatarObjectKey();
        String newKey = avatarPrefix + user.getId() + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;

        // 先上传成功再写数据库；原始字节不会被解码或重编码。
        putAvatarObject(user.getId(), operationId, newKey, content, extension);
        try {
            LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
            update.eq(User::getId, user.getId()).set(User::getAvatarObjectKey, newKey);
            if (userMapper.update(null, update) == 0) {
                // newKey 永远全新；影响 0 行通常代表账号已被删除。
                throw new BusinessException(ResultCode.UNAUTHORIZED);
            }
        } catch (RuntimeException exception) {
            // 数据库异常也可能是提交结果不确定，不能立即删除新图或盲目恢复旧 Key。
            LOGGER.error("AVATAR_LINK_FAILED operationId={} userId={} key={} stage=update",
                    operationId, user.getId(), newKey, exception);
            throw exception;
        }
        deleteOldAvatar(user.getId(), oldKey, operationId);
    }

    //删除头像
    @Override
    public void removeAvatar() {
        User user = requireCurrentUser();
        String oldKey = user.getAvatarObjectKey();
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
        update.eq(User::getId, user.getId()).set(User::getAvatarObjectKey, null);
        int affected = userMapper.update(null, update);
        // 原来就是 NULL 时 0 行属于正常幂等结果，但已删除的用户仍应返回 401。
        if (affected == 0) requireCurrentUser();
        deleteOldAvatar(user.getId(), oldKey, UUID.randomUUID().toString());
    }
















    // ==================== 私有辅助方法 ====================


    /** 所有写操作的用户 ID 都只能来自认证上下文。 */
    private User requireCurrentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        return user;
    }

    /**
     * 将用户实体转换为公开视图，仅保留 ID 与用户名，避免暴露密码哈希。
     *
     * @param user 用户实体
     * @return 用户公开视图
     */
    /** 只做字段转换和地址拼接，不请求 OSS，不把默认昵称写回数据库。 */
    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        if (user.getAvatarObjectKey() != null) {
            if (publicBaseUrl == null || publicBaseUrl.trim().isEmpty()) {
                throw new IllegalStateException("尚未配置 OSS 公共访问域名");
            }
            String base = publicBaseUrl.trim().replaceAll("/+$", "");
            vo.setAvatarUrl(base + "/" + user.getAvatarObjectKey());
        }
        return vo;
    }

    /** 文件大小在 Servlet 和业务两层检查 */
    private byte[] readAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(422, "请选择非空头像文件");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ResultCode.AVATAR_TOO_LARGE);
        }
        try {
            byte[] content = file.getBytes();
            if (content.length == 0) throw new BusinessException(422, "请选择非空头像文件");
            if (content.length > MAX_AVATAR_BYTES) {
                throw new BusinessException(ResultCode.AVATAR_TOO_LARGE);
            }
            return content;
        } catch (IOException exception) {
            // 文件尚未上传，读取失败时保留当前关联。
            LOGGER.warn("AVATAR_READ_FAILED userId={}", UserContext.getUserId(), exception);
            throw new BusinessException(422, "读取头像失败，请重新选择文件");
        }
    }

    /** 按已确认的最小文件头规则识别类型，不相信扩展名或请求 Content-Type。 */
    private String avatarExtension(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (content.length >= 4 && (content[0] & 0xff) == 0x89
                && content[1] == 0x50 && content[2] == 0x4e && content[3] == 0x47) {
            return "png";
        }
        throw new BusinessException(ResultCode.AVATAR_INVALID);
    }

    /** 上传为私有写、公共读 Bucket 中的新对象，地址不含签名。 */
    private void putAvatarObject(Long userId, String operationId, String key,
                                 byte[] content, String extension) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType("jpg".equals(extension) ? "image/jpeg" : "image/png");
        metadata.setCacheControl("public, max-age=31536000");
        try {
            // 内存输入流没有外部句柄；SDK 同步消费内容后方法才返回。
            ossClient.putObject(bucket, key, new ByteArrayInputStream(content), metadata);
        } catch (OSSException | ClientException exception) {
            String requestId = exception instanceof OSSException
                    ? ((OSSException) exception).getRequestId() : "-";
            // 响应丢失时对象可能已存在，保留生成的 Key 供人工排查。
            LOGGER.error("AVATAR_UPLOAD_FAILED operationId={} userId={} key={} requestId={} stage=upload",
                    operationId, userId, key, requestId, exception);
            throw new BusinessException(ResultCode.AVATAR_STORAGE_UNAVAILABLE);
        }
    }

    /** 清理失败不推翻已经提交的关联更新；不建立重试队列。 */
    private void deleteOldAvatar(Long userId, String oldKey, String operationId) {
        if (oldKey == null) return;
        try {
            String expected = Pattern.quote(avatarPrefix + userId + "/")
                    + "[0-9a-f]{32}\\.(jpg|png)";
            if (!oldKey.matches(expected)) {
                throw new IllegalStateException("旧头像 Key 不属于当前用户允许的路径");
            }
            // 再核对所有用户的引用；异常时保守保留，交给维护人员确认。
            Long references = userMapper.selectCount(
                    Wrappers.lambdaQuery(User.class).eq(User::getAvatarObjectKey, oldKey));
            if (references != null && references > 0) return;
            ossClient.deleteObject(bucket, oldKey);
        } catch (RuntimeException exception) {
            String requestId = exception instanceof OSSException
                    ? ((OSSException) exception).getRequestId() : "-";
            LOGGER.warn("AVATAR_DELETE_FAILED operationId={} userId={} key={} requestId={} stage=delete",
                    operationId, userId, oldKey, requestId, exception);
        }
    }
}

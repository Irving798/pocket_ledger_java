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
 * 认证业务实现类：负责注册、登录、当前用户资料维护与头像管理。
 * <p>
 * 密码只保存 BCrypt 哈希，明文不落库；头像文件上传到阿里云 OSS，
 * 数据库仅保存对象 Key，展示时用 publicBaseUrl 拼接出可访问 URL。
 */
@Service
public class AuthServiceImpl implements AuthService {

    // ==================== 依赖注入组件与配置 ====================

    // 用户持久层：负责用户表的增删改查。
    private final UserMapper userMapper;
    // 密码编码器：注册时做 BCrypt 哈希，登录时做密文比对。
    private final PasswordEncoder passwordEncoder;
    // JWT 工具：为登录成功的用户签发访问令牌。
    private final JwtUtils jwtUtils;
    // OSS 公共访问域名前缀，用于拼接头像可访问 URL。
    @Value("${aliyun.oss.public-base-url:}")
    private String publicBaseUrl;
    // 日志记录器：记录头像上传/删除等关键操作的失败日志。
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthServiceImpl.class);
    // 头像文件大小上限：2MB，防止超大文件占用带宽与存储。
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    // 阿里云 OSS 客户端：负责头像对象的上传与删除。
    private final OSS ossClient;
    // OSS 存储桶名称，来自 application.yml 配置。
    @Value("${aliyun.oss.bucket}")
    private String bucket;
    // OSS 中头像对象 Key 的目录前缀，默认 avatars/。
    @Value("${aliyun.oss.avatar-prefix:avatars/}")
    private String avatarPrefix;

    /**
     * 构造方法：注入认证流程所需的持久层、密码编码器、JWT 工具与 OSS 客户端。
     *
     * @param userMapper      用户持久层
     * @param passwordEncoder 密码编码器
     * @param jwtUtils        JWT 工具
     * @param ossClient       阿里云 OSS 客户端
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
     * 注册新用户：用户名查重 → 密码 BCrypt 哈希 → 用户入库 → 返回不含敏感字段的用户信息。
     *
     * @param dto 注册请求数据
     * @return 注册成功后的用户信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterDTO dto) {
        // 先按用户名查重，已存在时直接拒绝，防止重复注册。
        Long count = userMapper.selectCount(
                Wrappers.lambdaQuery(User.class).eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException(ResultCode.USERNAME_OCCUPIED);
        }

        // 构造用户实体：密码只保存 BCrypt 哈希；新用户头像为空。
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setEmail(dto.getEmail());
        user.setAvatarObjectKey(null);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册时可能“查重通过但插入撞唯一键”，同样按用户名占用处理。
            throw new BusinessException(ResultCode.USERNAME_OCCUPIED);
        }

        return toVO(user);
    }

    // ==================== 登录 ====================

    /**
     * 登录：校验用户名与密码，为合法用户签发 JWT 访问令牌。
     *
     * @param dto 登录请求数据
     * @return 包含访问令牌的响应
     */
    @Override
    @Transactional(readOnly = true)
    public TokenVO login(LoginDTO dto) {
        User user = userMapper.selectOne(
                Wrappers.lambdaQuery(User.class).eq(User::getUsername, dto.getUsername()));
        // 用户名不存在或密码不匹配时统一返回凭据错误，不泄露具体是哪一项不对。
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ResultCode.BAD_CREDENTIALS);
        }
        // 校验通过后按用户 ID 与用户名签发令牌。
        return new TokenVO(jwtUtils.generateToken(user.getId(), user.getUsername()));
    }

    // ==================== 获取当前用户 ====================

    /**
     * 获取当前登录用户的资料。
     *
     * @return 当前用户的公开信息
     */
    @Override
    @Transactional(readOnly = true)
    public UserVO getCurrentUser() {
        // 从 ThreadLocal 取当前身份并查出用户，缺失时抛未授权。
        return toVO(requireCurrentUser());
    }

    // ==================== 资料与头像维护 ====================

    /**
     * 更新当前登录用户的昵称与邮箱。
     *
     * @param dto 资料更新数据
     * @return 更新后的用户信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateCurrentUser(UserProfileUpdateDTO dto) {
        // 先取当前身份，再按主键更新，防止修改到其他用户。
        User user = requireCurrentUser();
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
        update.eq(User::getId, user.getId())
                .set(User::getNickname, dto.getNickname())
                .set(User::getEmail, dto.getEmail());
        userMapper.update(null, update);
        // 重新查询一遍，返回落库后的最新资料。
        return toVO(requireCurrentUser());
    }

    /**
     * 替换当前用户的头像：读取并校验文件 → 上传新对象到 OSS → 更新数据库 Key → 删除旧对象。
     * <p>
     * 上传成功但数据库更新失败时记录 error 日志并抛异常；旧头像删除失败只告警，不阻断主流程。
     *
     * @param file 上传的头像文件
     */
    @Override
    public void replaceAvatar(MultipartFile file) {
        // 当前登录用户；未登录或用户不存在时抛未授权。
        User user = requireCurrentUser();
        // 校验并读取头像文件字节（非空、不超过 2MB）。
        byte[] content = readAvatar(file);
        // 根据文件头魔数识别图片真实格式，得到扩展名 jpg/png。
        String extension = avatarExtension(content);
        // 生成操作 ID，用于把本次上传、写库、删除旧头像的日志串起来。
        String operationId = UUID.randomUUID().toString();
        // 旧头像 Key，用于最后清理。
        String oldKey = user.getAvatarObjectKey();
        // 新对象 Key：目录前缀 + 用户ID + 随机 UUID（去横线），保证唯一且归属明确。
        String newKey = avatarPrefix + user.getId() + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;

        // 第一步：把新头像对象上传到 OSS。
        putAvatarObject(user.getId(), operationId, newKey, content, extension);
        try {
            // 第二步：上传成功后再把新 Key 写回用户表；更新 0 行说明用户已不存在。
            LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
            update.eq(User::getId, user.getId()).set(User::getAvatarObjectKey, newKey);
            if (userMapper.update(null, update) == 0) {
                throw new BusinessException(ResultCode.UNAUTHORIZED);
            }
        } catch (RuntimeException exception) {
            // 数据库更新失败时记录 error 日志并继续抛出，供上层感知本次更换失败。
            LOGGER.error("AVATAR_LINK_FAILED operationId={} userId={} key={} stage=update",
                    operationId, user.getId(), newKey, exception);
            throw exception;
        }
        // 第三步：清理旧头像对象；失败仅告警，不影响本次更换结果。
        deleteOldAvatar(user.getId(), oldKey, operationId);
    }

    /**
     * 删除当前用户的头像：清空数据库中的对象 Key，并删除 OSS 上的旧对象。
     */
    @Override
    public void removeAvatar() {
        // 当前登录用户；未登录或用户不存在时抛未授权。
        User user = requireCurrentUser();
        String oldKey = user.getAvatarObjectKey();
        // 先把用户头像 Key 置空。
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
        update.eq(User::getId, user.getId()).set(User::getAvatarObjectKey, null);
        int affected = userMapper.update(null, update);
        // 更新 0 行时再次校验用户是否存在（不存在则抛未授权）。
        if (affected == 0) requireCurrentUser();
        // 最后删除 OSS 旧对象；失败仅告警。
        deleteOldAvatar(user.getId(), oldKey, UUID.randomUUID().toString());
    }









    // ==================== 私有辅助方法 ====================

    /**
     * 从 ThreadLocal 取当前登录用户 ID 并查出用户；身份缺失或用户不存在时抛未授权。
     *
     * @return 当前登录用户实体
     */
    private User requireCurrentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        return user;
    }

    /**
     * 将用户实体转换为公开视图：保留基础资料，拼接头像 URL，绝不暴露密码哈希。
     *
     * @param user 用户实体
     * @return 用户公开信息
     */
    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        if (user.getAvatarObjectKey() != null) {
            // 未配置 OSS 公共域名时无法拼出头像地址，直接失败提醒配置缺失。
            if (publicBaseUrl == null || publicBaseUrl.trim().isEmpty()) {
                throw new IllegalStateException("尚未配置 OSS 公共访问域名");
            }
            // 去掉域名尾部多余的斜杠，避免拼出双斜杠。
            String base = publicBaseUrl.trim().replaceAll("/+$", "");
            vo.setAvatarUrl(base + "/" + user.getAvatarObjectKey());
        }
        return vo;
    }

    /**
     * 校验并读取上传的头像文件字节：必须非空且不超过 2MB。
     *
     * @param file 上传的头像文件
     * @return 头像文件字节内容
     */
    private byte[] readAvatar(MultipartFile file) {
        // 未选择文件或空文件直接拒绝。
        if (file == null || file.isEmpty()) {
            throw new BusinessException(422, "请选择非空头像文件");
        }
        // 先按文件声明大小拦截，超过 2MB 直接拒绝。
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ResultCode.AVATAR_TOO_LARGE);
        }
        try {
            byte[] content = file.getBytes();
            // 再按实际读取到的字节数复核一次，防止声明大小与实际不符。
            if (content.length == 0) throw new BusinessException(422, "请选择非空头像文件");
            if (content.length > MAX_AVATAR_BYTES) {
                throw new BusinessException(ResultCode.AVATAR_TOO_LARGE);
            }
            return content;
        } catch (IOException exception) {
            // 读取失败时保留用户当前头像关联，仅提示重新选择。
            LOGGER.warn("AVATAR_READ_FAILED userId={}", UserContext.getUserId(), exception);
            throw new BusinessException(422, "读取头像失败，请重新选择文件");
        }
    }

    /**
     * 根据文件头魔数识别头像图片格式，返回对应的扩展名。
     * <p>
     * 只允许 jpg（FF D8 FF 开头）与 png（89 50 4E 47 开头），其他格式视为非法头像文件。
     *
     * @param content 头像文件字节内容
     * @return 图片扩展名：jpg 或 png
     */
    private String avatarExtension(byte[] content) {
        // JPEG 文件头魔数：FF D8 FF。
        if (content.length >= 3 && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff) {
            return "jpg";
        }
        // PNG 文件头魔数：89 50 4E 47。
        if (content.length >= 4 && (content[0] & 0xff) == 0x89
                && content[1] == 0x50 && content[2] == 0x4e && content[3] == 0x47) {
            return "png";
        }
        // 不支持的格式直接拒绝，防止任意文件被当作头像上传。
        throw new BusinessException(ResultCode.AVATAR_INVALID);
    }

    /**
     * 把头像字节流上传到 OSS：设置文件长度、MIME 类型与一年缓存。
     * <p>
     * 上传失败时记录 error 日志，并转换为稳定的业务错误码。
     *
     * @param userId      当前用户 ID
     * @param operationId 本次操作 ID，用于日志串联
     * @param key         OSS 对象 Key
     * @param content     头像文件字节内容
     * @param extension   图片扩展名（jpg/png），决定 Content-Type
     */
    private void putAvatarObject(Long userId, String operationId, String key,
                                 byte[] content, String extension) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        // 根据扩展名设置正确的 MIME 类型。
        metadata.setContentType("jpg".equals(extension) ? "image/jpeg" : "image/png");
        // 头像基本不变，设置一年强缓存减少重复下载。
        metadata.setCacheControl("public, max-age=31536000");
        try {
            ossClient.putObject(bucket, key, new ByteArrayInputStream(content), metadata);
        } catch (OSSException | ClientException exception) {
            // OSS 异常统一转成存储不可用错误码，不向调用方暴露云厂商细节。
            String requestId = exception instanceof OSSException
                    ? ((OSSException) exception).getRequestId() : "-";
            LOGGER.error("AVATAR_UPLOAD_FAILED operationId={} userId={} key={} requestId={} stage=upload",
                    operationId, userId, key, requestId, exception);
            throw new BusinessException(ResultCode.AVATAR_STORAGE_UNAVAILABLE);
        }
    }

    /**
     * 删除 OSS 上的旧头像对象：先校验 Key 属于当前用户路径，再确认无人引用后删除。
     * <p>
     * 清理失败只告警不抛错，不影响头像更换/删除主流程。
     *
     * @param userId      当前用户 ID
     * @param oldKey      旧头像对象 Key；为空表示原本没有头像，无需删除
     * @param operationId 本次操作 ID，用于日志串联
     */
    private void deleteOldAvatar(Long userId, String oldKey, String operationId) {
        // 原本没有头像时无需删除。
        if (oldKey == null) return;
        try {
            // 只允许删除“当前用户目录下 UUID 命名的 jpg/png”，防止误删其他对象。
            String expected = Pattern.quote(avatarPrefix + userId + "/")
                    + "[0-9a-f]{32}\\.(jpg|png)";
            if (!oldKey.matches(expected)) {
                throw new IllegalStateException("旧头像 Key 不属于当前用户允许的路径");
            }
            // 还有用户引用该对象时跳过删除，避免被误清。
            Long references = userMapper.selectCount(
                    Wrappers.lambdaQuery(User.class).eq(User::getAvatarObjectKey, oldKey));
            if (references != null && references > 0) return;
            ossClient.deleteObject(bucket, oldKey);
        } catch (RuntimeException exception) {
            // 删除失败仅告警，不向调用方抛错，保证主流程不受影响。
            String requestId = exception instanceof OSSException
                    ? ((OSSException) exception).getRequestId() : "-";
            LOGGER.warn("AVATAR_DELETE_FAILED operationId={} userId={} key={} requestId={} stage=delete",
                    operationId, userId, oldKey, requestId, exception);
        }
    }
}

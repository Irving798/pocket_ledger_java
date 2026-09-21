package com.fly.pocket_ledger_java.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.nio.charset.StandardCharsets;


/**
 * 用户注册请求入参 DTO。
 *
 * <p>前端提交注册表单时使用本类接收 JSON 请求体，字段上带有
 * javax.validation 校验注解，由框架统一校验；校验失败时由全局异常处理器
 * 返回 422 响应。</p>
 */
@Data
public class RegisterDTO {
    /** 用户名：必填，长度 2~50 个字符 */
    @NotBlank(message = "不能为空")
    @Size(min = 2, max = 50, message = "长度需在 2~50 个字符之间")
    private String username;

    /** 密码：必填，长度 8~64 个字符（BCrypt 另有 72 字节上限校验，见 {@link #isPasswordWithinBcryptLimit()}） */
    @NotBlank(message = "不能为空")
    @Size(min = 8, max = 64, message = "长度需在 8~64 个字符之间")
    private String password;


    /** 昵称：选填，长度不超过 50 个字符 */
    @Size(max = 50, message = "长度不能超过 50")
    private String nickname;

    /** 邮箱：选填，需符合邮箱格式，长度不超过 254 个字符 */
    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "长度不能超过 254")
    private String email;


    /**
     * 校验密码经 UTF-8 编码后的字节数不超过 72 字节。
     *
     * <p>BCrypt 算法最多只处理 72 字节，超长密码会被静默截断，存在
     * “不同密码哈希相同”的安全隐患，因此在此前置拦截。</p>
     *
     * <p>该方法是返回 boolean 的 getter 风格方法，满足 {@code @AssertTrue}
     * 的约束要求；{@code @JsonIgnore} 表示它只参与校验，不序列化到 JSON。</p>
     *
     * @return 密码为 null（未填写，由 @NotBlank 另行拦截）或字节数不超过 72 时返回 true
     */
    @JsonIgnore
    @AssertTrue(message = "密码 UTF-8 编码后不能超过 72 字节")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    /** 设置用户名：去除首尾空白，null 保持不变（由 @NotBlank 拦截） */
    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    /** 设置密码：去除首尾空白，null 保持不变（由 @NotBlank 拦截） */
    public void setPassword(String password) {
        this.password = password == null ? null : password.trim();
    }

    /** 设置昵称：去除首尾空白，空白串视为未填写（归为 null） */
    public void setNickname(String nickname) {
        this.nickname = normalizeOptional(nickname);
    }

    /** 设置邮箱：去除首尾空白，空白串视为未填写（归为 null） */
    public void setEmail(String email) {
        this.email = normalizeOptional(email);
    }


    /**
     * 规范化选填字段：null 原样返回；去除首尾空白后若为空串则归为 null，
     * 使“未填”与“只填了空格”在语义上等价。
     *
     * @param value 原始入参值
     * @return 规范化后的值，可能为 null
     */
    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }


    /**
     * 拒绝 JSON 中出现的未知字段。
     *
     * <p>Jackson 反序列化时遇到本类未声明的属性会回调此方法，直接抛出异常，
     * 确保前端不会静默提交无效字段。</p>
     *
     * @param name  未知字段名
     * @param value 未知字段值
     * @throws IllegalArgumentException 固定抛出，提示不支持的字段名
     */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("不支持的字段：" + name);
    }
}

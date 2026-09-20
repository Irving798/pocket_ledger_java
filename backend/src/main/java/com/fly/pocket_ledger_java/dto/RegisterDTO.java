package com.fly.pocket_ledger_java.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

/** 注册只提交文本；头像在登录后使用 multipart 单独上传。 */
@Data
public class RegisterDTO {
    @NotBlank(message = "不能为空")
    @Size(min = 2, max = 50, message = "长度需在 2~50 个字符之间")
    private String username;

    @NotBlank(message = "不能为空")
    @Size(min = 8, max = 64, message = "长度需在 8~64 个字符之间")
    private String password;

    /** 昵称可省略或清空，不使用 @NotBlank。 */
    @Size(max = 50, message = "长度不能超过 50")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "长度不能超过 254")
    private String email;

    /** 避免派生校验属性参与 JSON，同时保留原有密码字节限制。 */
    @JsonIgnore
    @AssertTrue(message = "密码 UTF-8 编码后不能超过 72 字节")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    public void setPassword(String password) {
        this.password = password == null ? null : password.trim();
    }

    public void setNickname(String nickname) {
        this.nickname = normalizeOptional(nickname);
    }

    public void setEmail(String email) {
        this.email = normalizeOptional(email);
    }

    /** 只规范化可选字段，不改变邮箱大小写。 */
    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** 沿用 BillWriteDTO 的规则，拒绝客户端指定头像或目标用户。 */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("不支持的字段：" + name);
    }
}
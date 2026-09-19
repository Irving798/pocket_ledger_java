package com.fly.pocket_ledger_java.dto;

import lombok.Data;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

/**
 * 注册参数，规则对齐契约（docs/pocket-ledger-api.html#register）：
 * username 去首尾空白、2~50；password 去首尾空白、8~64。
 * message 只写"原因"，全局异常处理器会拼成"参数错误：字段 原因"（422）。
 */
@Data
public class RegisterDTO {

    /** 用户名，去首尾空白后 2~50 个字符 */
    @NotBlank(message = "不能为空")
    @Size(min = 2, max = 50, message = "长度需在 2~50 个字符之间")
    private String username;

    /** 明文密码，去首尾空白后 8~64 个字符，且 UTF-8 字节数不超过 72 */
    @NotBlank(message = "不能为空")
    @Size(min = 8, max = 64, message = "长度需在 8~64 个字符之间")
    private String password;

    /**
     * bcrypt 只处理前 72 字节；@Size 统计的是字符数，因此还要单独限制 UTF-8 字节数。
     */
    @AssertTrue(message = "密码 UTF-8 编码后不能超过 72 字节")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    /**
     * 对齐契约的 strip_whitespace（Pydantic 在校验前去首尾空白），Java 侧统一在 setter 里 trim。
     * 手写 setter 后 Lombok @Data 不再生成同名方法。
     */
    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    public void setPassword(String password) {
        this.password = password == null ? null : password.trim();
    }
}

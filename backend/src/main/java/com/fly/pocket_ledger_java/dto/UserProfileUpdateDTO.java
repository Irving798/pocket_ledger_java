package com.fly.pocket_ledger_java.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import javax.validation.constraints.Email;
import javax.validation.constraints.Size;

/** PUT 整体替换这两个字段，缺失、null、空白都表示清空。 */
@Data
public class UserProfileUpdateDTO {
    @Size(max = 50, message = "长度不能超过 50")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "长度不能超过 254")
    private String email;

    public void setNickname(String nickname) {
        this.nickname = normalizeOptional(nickname);
    }

    public void setEmail(String email) {
        this.email = normalizeOptional(email);
    }

    /** 两个 DTO 各保留简单的字段规范化，不为三行逻辑新建 Utils。 */
    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("不支持的字段：" + name);
    }
}
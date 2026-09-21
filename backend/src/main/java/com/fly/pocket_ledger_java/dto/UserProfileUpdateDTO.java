package com.fly.pocket_ledger_java.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import javax.validation.constraints.Email;
import javax.validation.constraints.Size;


/**
 * 当前用户资料更新请求体：昵称与邮箱均可选。
 * <p>
 * 通过手写 setter（覆盖 Lombok 生成版本）把空白字符串统一归一化为 null（视作“未提供”），
 * 并拒绝请求体中出现任何未声明的字段，保证接口只接受契约内的参数。
 */
@Data
public class UserProfileUpdateDTO {

    /** 昵称；可选，最长 50 字符 */
    @Size(max = 50, message = "长度不能超过 50")
    private String nickname;

    /** 邮箱；可选，需符合邮箱格式，最长 254 字符 */
    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "长度不能超过 254")
    private String email;

    /**
     * 设置昵称：写入前把空白串归一化为 null，空输入按“未提供”处理。
     *
     * @param nickname 用户提交的昵称
     */
    public void setNickname(String nickname) {
        this.nickname = normalizeOptional(nickname);
    }

    /**
     * 设置邮箱：写入前把空白串归一化为 null，空输入按“未提供”处理。
     *
     * @param email 用户提交的邮箱
     */
    public void setEmail(String email) {
        this.email = normalizeOptional(email);
    }


    /**
     * 把可选字段归一化：null 原样保留，trim 后为空串的按 null 处理。
     *
     * @param value 原始输入值
     * @return 归一化后的值：null 或非空白字符串
     */
    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 拒绝请求体中的未知字段：Jackson 反序列化遇到未声明属性时回调此方法。
     *
     * @param name  未知字段名
     * @param value 未知字段值（本方法不使用，仅用于匹配回调签名）
     */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("不支持的字段：" + name);
    }
}

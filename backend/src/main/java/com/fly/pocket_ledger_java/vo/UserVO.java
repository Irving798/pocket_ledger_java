package com.fly.pocket_ledger_java.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户公开信息视图：认证相关接口的统一返回对象。
 * 只携带基础资料与头像地址，绝不包含密码哈希等敏感字段；
 * 序列化字段名与接口契约对齐（avatarUrl 输出为 avatar_url）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
// 始终序列化所有字段：字段为 null 也照常输出，保证响应结构稳定。
@JsonInclude(JsonInclude.Include.ALWAYS)
public class UserVO {

    /** 用户 ID */
    private Long id;

    /** 用户名（登录账号） */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 头像访问 URL；JSON 字段名按接口契约输出为 avatar_url */
    @JsonProperty("avatar_url")
    private String avatarUrl;

    /** 邮箱 */
    private String email;
}

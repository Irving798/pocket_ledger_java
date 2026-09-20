package com.fly.pocket_ledger_java.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 公开资料视图；绝不包含密码哈希、对象 Key 或云端凭证。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class UserVO {
    private Long id;
    private String username;
    private String nickname;
    /** JSON 使用统一风格的下划线字段名，空值也必须保留。 */
    @JsonProperty("avatar_url")
    private String avatarUrl;
    private String email;
}
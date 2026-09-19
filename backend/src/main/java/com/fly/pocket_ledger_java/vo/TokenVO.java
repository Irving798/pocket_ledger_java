package com.fly.pocket_ledger_java.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录凭证视图对象，对齐契约：{access_token, token_type:"bearer"}（docs/pocket-ledger-api.html#login）。
 * snake_case 用字段级 @JsonProperty 映射，不用全局命名策略，避免波及其他接口的驼峰字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenVO {

    /** 契约固定值，不由调用方传入 */
    private static final String TOKEN_TYPE_BEARER = "bearer";

    /** JWT，有效期 24 小时 */
    @JsonProperty("access_token")
    private String accessToken;

    /** 令牌类型，契约固定为 bearer */
    @JsonProperty("token_type")
    private String tokenType;

    /**
     * 登录成功时的便捷构造：只需传入 JWT，tokenType 自动固定为 bearer。
     *
     * @param accessToken 签发的 JWT
     */
    public TokenVO(String accessToken) {
        this.accessToken = accessToken;
        this.tokenType = TOKEN_TYPE_BEARER;
    }
}

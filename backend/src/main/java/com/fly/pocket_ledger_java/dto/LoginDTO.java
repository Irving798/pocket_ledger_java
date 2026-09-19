package com.fly.pocket_ledger_java.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 登录参数：契约只要求非空，格式校验交给注册——否则将来改了注册规则，老用户连登录都登录不上。
 * 契约未要求 trim，保持原样提交。
 */
@Data
public class LoginDTO {

    /** 用户名，只校验非空 */
    @NotBlank(message = "不能为空")
    private String username;

    /** 明文密码，只校验非空，正确性由登录逻辑核对 */
    @NotBlank(message = "不能为空")
    private String password;
}

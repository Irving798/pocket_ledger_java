package com.fly.pocket_ledger_java.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户信息视图对象：对齐契约只暴露 id、username（docs/pocket-ledger-api.html#me）。
 * 实体里的 passwordHash、createdAt 等字段不出 Controller。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    /** 用户 ID */
    private Long id;

    /** 用户名 */
    private String username;
}

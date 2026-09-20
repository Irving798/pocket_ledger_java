package com.fly.pocket_ledger_java.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 fly_user。
 */
@Data
@TableName("fly_user")
public class User {
    /** 用户 ID，自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户名，唯一 */
    private String username;

    /** 密码哈希（bcrypt），绝不通过接口返回 */
    private String passwordHash;

    /** 展示昵称；空值由前端回退为用户名。 */
    private String nickname;

    /** OSS 对象标识；不保存完整 URL，不接收客户端直接赋值。 */
    private String avatarObjectKey;

    /** 联系邮箱；只校验格式，允许重复。 */
    private String email;

    /** 注册时间 */
    private LocalDateTime createdAt;

    /** 最近修改时间 */
    private LocalDateTime updatedAt;
}

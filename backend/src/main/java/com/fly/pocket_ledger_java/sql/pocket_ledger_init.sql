-- PocketLedger 数据库初始化脚本
-- 可直接在 MySQL 8.0+ 中一次性完整执行

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =========================================================
-- 1. 删除旧表
--    按依赖关系从子表到主表删除，便于重复执行本脚本
-- =========================================================
DROP TABLE IF EXISTS `fly_bill_breakdown`;
DROP TABLE IF EXISTS `fly_bill`;
DROP TABLE IF EXISTS `fly_category`;
DROP TABLE IF EXISTS `fly_user`;

-- =========================================================
-- 2. 用户表
-- =========================================================
CREATE TABLE `fly_user`
(
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`      VARCHAR(50)      NOT NULL COMMENT '登录名，唯一',
    `password_hash`     VARCHAR(255) NOT NULL COMMENT '密码哈希（bcrypt 输出 60 字符；255 预留算法迁移空间），绝不存明文',
    `nickname`          VARCHAR(50)  NULL COMMENT '展示昵称；空值由前端回退为用户名',
    `avatar_object_key` VARCHAR(255) NULL COMMENT 'OSS 头像对象键（avatars/ 前缀），不保存完整 URL',
    `email`             VARCHAR(254) NULL COMMENT '联系邮箱；只校验格式，允许重复',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（注册时间）',
    `updated_at`    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（资料或密码的最后修改时间）',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '用户表';

-- =========================================================
-- 3. 账单分类字典表
-- =========================================================
CREATE TABLE `fly_category`
(
    `id`   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(50)     NOT NULL COMMENT '分类名称，如：餐饮、交通、工资',
    `type` VARCHAR(10)     NOT NULL COMMENT '账单类型：income-收入，expense-支出（全库唯一的类型事实源，永不修改）',
    `icon` VARCHAR(50)     DEFAULT NULL COMMENT '图标标识，供前端渲染（如 food/transport/salary）',
    `sort` INT             NOT NULL DEFAULT 0 COMMENT '排序权重，越小越靠前',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_type_name` (`type`, `name`),
    CONSTRAINT `idx_category_type`
        CHECK (`type` IN ('income', 'expense'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '账单分类字典表（只读，开发者维护，用户不可增删改）';

-- =========================================================
-- 4. 账单表
-- =========================================================
CREATE TABLE `fly_bill`
(
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID（逻辑外键 -> fly_user.id）',
    `amount`      DECIMAL(12, 2)  NOT NULL COMMENT '账单金额',
    `category_id` BIGINT UNSIGNED NOT NULL COMMENT '分类ID（逻辑外键 -> fly_category.id；账单类型 income/expense 由分类决定）',
    `description` VARCHAR(200)     DEFAULT NULL COMMENT '账单备注',
    `bill_date`   DATE             NOT NULL COMMENT '账单发生日期',
    `created_at`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),
    KEY `idx_user_date` (`user_id`, `bill_date`),
    KEY `idx_user_category_date` (`user_id`, `category_id`, `bill_date`),
    CONSTRAINT `idx_bill_amount`
        CHECK (`amount` > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '账单记录表';

-- =========================================================
-- 5. 账单细分表
-- =========================================================
CREATE TABLE `fly_bill_breakdown`
(
    `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID（逻辑关联 -> fly_user.id）',
    `bill_id`    BIGINT UNSIGNED NOT NULL COMMENT '所属账单ID（逻辑关联 -> fly_bill.id）',
    `item_name`  VARCHAR(50)     NOT NULL COMMENT '细分名称',
    `amount`     DECIMAL(12, 2)  NOT NULL COMMENT '细分金额',
    `sort_order` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '展示顺序',
    `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),
    KEY `idx_user_bill_sort` (`user_id`, `bill_id`, `sort_order`, `id`),
    CONSTRAINT `idx_bill_breakdown_amount`
        CHECK (`amount` > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '账单显式细分';

-- =========================================================
-- 6. 初始化账单分类
-- =========================================================
INSERT INTO `fly_category` (`id`, `name`, `type`, `icon`, `sort`)
VALUES
    (1,  '工资',     'income',  'salary',        1),
    (2,  '奖金',     'income',  'bonus',         2),
    (3,  '理财收益', 'income',  'investment',    3),
    (4,  '兼职',     'income',  'parttime',      4),
    (5,  '其他收入', 'income',  'other_income', 99),
    (6,  '餐饮',     'expense', 'food',          1),
    (7,  '交通',     'expense', 'transport',     2),
    (8,  '购物',     'expense', 'shopping',      3),
    (9,  '居住',     'expense', 'housing',       4),
    (10, '娱乐',     'expense', 'entertainment', 5),
    (11, '医疗',     'expense', 'medical',       6),
    (12, '教育',     'expense', 'education',     7),
    (13, '人情',     'expense', 'gift',          8),
    (14, '其他支出', 'expense', 'other_expense', 99);

SET FOREIGN_KEY_CHECKS = 1;

# 口袋账本退出登录：逐单元手敲代码指导书

> 面向亲自编码的开发者。本文是实施指导，**不是已执行的验收结果**；文中代码示例及其预期输出都不能当作"测试已通过"的证据。子代理仅可用于探索、检索和核验，业务代码修改与最终验证由主执行者负责。

**目标：** 保留现有 JWT 登录方式，实现"退出时把当前 Token 的 `jti` 写入 Redis 黑名单，TTL 为 Token 剩余有效期；后续请求统一拒绝该 Token"。前端先等服务端注销，再清本地状态并整页重载。

**架构：** 登录签发带独立 `jti` 的 JWT；`AuthInterceptor` 验签通过后查 Redis 黑名单；新增 `TokenBlacklist` 专职组件管理 Key 与 TTL；`POST /auth/logout` 复用鉴权上下文完成撤销；静态前端等待退出请求结束后再刷新。

**技术基线：** Java 8、Spring Boot 2.3.0.RELEASE、JJWT 0.11.5、Spring Data Redis（版本由父 POM 管理）、原生 HTML/CSS/JavaScript 静态前端。不升级框架、不引入 Spring Security 过滤器链。

**需求依据：** [口袋账本退出登录功能详细实施方案](2026-09-19-logout-implementation-guide.md)（下称"实施方案"）。本文是它的编码展开，接口契约、重复退出取舍、Redis Key 与 TTL 设计均以实施方案为准，不再重复论证。

**核对日期：** 2026-09-19。后文行号来自这个日期的基线，编码后会漂移，操作时优先按"定位锚点"搜索确认。相对路径均从项目根目录 `C:/fly_develop/java_ai_project/pocket_ledger_java_fly` 开始。

## 阅读与执行方式

每个单元按"文件清单 → 定位 → 改动 → 测试验收"执行。**一次只做一个单元，验收通过再进入下一个**。代码块标注"完整文件"时录入整个文件；标注"片段"时只把内容放到指定位置，不重复包名、类声明或已有大括号。

- import 统一放在 package 下方、类注释上方的 import 区；字段放在类的字段区；方法放在类结束大括号之前。
- 本指导书只交付这一个 Markdown 文件，**不修改任何业务代码**。真正实施涉及多个文件，开始编码前仍须按仓库规则向使用者确认中文实施方案。
- 全程不查阅、不修改 `frontend_vue`（含其测试、依赖与构建）。
- 当前工作区已有未提交改动（`BillServiceImpl.java`、用户资料 OSS 两份文档），实施时保留，不得用本文覆盖。
- 所有新增代码注释一律使用中文，与现有代码风格一致。

## 单元导航与文件职责

| 单元 | 可验收结果 | 本单元主要文件 |
| --- | --- | --- |
| 0 | 基线明确：分支、工作区改动、现有测试通过 | 只读检查 |
| 1 | 登录 Token 带唯一 `jti`；解析强制要求 `jti`、`exp` | `LoginUser`、`JwtUtils`、`JwtUtilsTest`（新增） |
| 2 | Redis 依赖与连接配置就绪；503 状态码入枚举 | `pom.xml`、`application.yml`、`ResultCode` |
| 3 | 黑名单读写组件完成，单测全绿 | `TokenBlacklist`（新增）、`TokenBlacklistTest`（新增） |
| 4 | 拦截器拒绝已撤销 Token；JWT 失败不触碰 Redis | `AuthInterceptor`、`AuthInterceptorTest` |
| 5 | `POST /auth/logout` 真实组合链路全绿（含 503、幂等、405） | `AuthService`、`AuthServiceImpl`、`AuthController`、`AuthControllerWebTest`、`AuthLogoutWebTest`（新增） |
| 6 | 静态前端退出闭环：等服务端注销 → 清本地 → 重载 | `fronted_static/js/api.js`、`fronted_static/js/app.js` |
| 7 | 全量回归 + 真实 Redis 联调验收 | 验收执行 |

生产 Java 代码只新增 1 个文件：`service/support/TokenBlacklist.java`。其余 4 个 Java 文件（`JwtUtilsTest`、`TokenBlacklistTest`、`AuthLogoutWebTest`、既有两个测试的扩展）均为测试文件。

### 已有实现与复用依据

- `JwtUtils.generateToken()`（原 47 行）已按 sub / username / iat / exp 构建，只差 `setId()`；`parseToken()`（原 67 行）已做验签与过期校验，只差必要字段校验与新字段装配。
- `LoginUser` 用 `@Data + @NoArgsConstructor + @AllArgsConstructor`；`BillControllerWebTest` 依赖其双参构造 `new LoginUser(7L, "reed")`，扩展时必须保留。
- `AuthInterceptor.preHandle()` 的失败输出统一走私有方法 `writeUnauthorized()`，黑名单拒绝复用它，不新写响应代码。
- `ResultCode` 的 `UNAUTHORIZED(401)` 注释本就写着"已拉黑"，本次只是让代码真正具备该能力；503 是新增枚举项。
- `GlobalExceptionHandler.handleBusinessException()` 按异常 code 决定 HTTP 状态码，503 无需新增处理器。
- `ApiResponse.success(String message, T data)` 已支持自定义成功文案（"退出成功"）。
- 前端 `api.js` 的 `request()` 已解包统一响应体、401 时给 Error 打 `.auth` 标记；`app.js` 的退出按钮监听在原 905 行附近。
- 项目没有 `RedisUtils`，也不新建；黑名单职责单一，放 `service/support/`（与 `BillRules`、`BillAssembler` 同级）。

---

## 单元 0：固定基线

不改任何文件，只确认环境，避免把历史问题算到本次头上。

- [ ] 在项目根目录执行只读检查并记录结果：

```powershell
# 记录当前分支与工作区已有改动（当前基线：feat/add_userinfo 分支，
# BillServiceImpl.java 与两份 docs 文档有未提交修改，属正常保留项）
git status --short
git branch --show-current
```

- [ ] 在 `backend` 目录设置本会话 JDK 8 并跑认证相关基线测试：

```powershell
Set-Location 'C:\fly_develop\java_ai_project\pocket_ledger_java_fly\backend'
# 只在当前会话生效，不改系统环境变量
$env:JAVA_HOME = 'C:\fly_develop\Java\temurin-jdk8u504-b01'
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' '-Dtest=AuthControllerWebTest,AuthInterceptorTest,BillControllerWebTest' test
```

**通过标准：** Java 1.8、Maven 3.8.6；上述三个测试类全部通过。若有失败，先记录并区分是历史遗留还是环境问题，不得为了继续而删除或跳过失败测试。

---

## 单元 1：令牌带上唯一身份（`jti`）

### 1.1 扩展 LoginUser

**修改：** `backend/src/main/java/com/fly/pocket_ledger_java/util/LoginUser.java`。文件很小，直接整文件替换为（完整文件）：

```java
package com.fly.pocket_ledger_java.util;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户：由 JwtUtils 解析 token 得到，拦截器放入 UserContext。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private Long id;

    private String username;

    /** 令牌唯一编号（JWT 的 jti）：退出登录时用它作为黑名单 Key */
    private String tokenId;

    /** 令牌过期时间（毫秒时间戳）：退出时用它计算黑名单记录的剩余存活时间 */
    private long expiresAtMillis;

    /**
     * 兼容旧调用方的双参构造（BillControllerWebTest 等仍在使用）。
     * 鉴权链路必须使用四参构造：否则退出业务拿不到 tokenId，无法撤销令牌。
     */
    public LoginUser(Long id, String username) {
        this.id = id;
        this.username = username;
    }
}
```

说明：`@AllArgsConstructor` 此时生成的是**四参**构造；手写双参构造与其并存，`@NoArgsConstructor` 的无参构造不受影响。

### 1.2 签发时写入 jti、解析时强制校验

**修改：** `backend/src/main/java/com/fly/pocket_ledger_java/util/JwtUtils.java`。共 4 处改动。

**① import 区新增 3 行。** 定位锚点：`import java.util.Date;`（原 12 行）一带。新增：

```java
import io.jsonwebtoken.JwtException;
```

放在 `import io.jsonwebtoken.Claims;` 之后；

```java
import org.springframework.util.StringUtils;
```

放在 `import org.springframework.stereotype.Component;` 之后；

```java
import java.util.UUID;
```

放在 `import java.util.Date;` 之后。

**② `generateToken()` 构建链加一行。** 定位锚点：方法内 `return Jwts.builder()`（原 50 行）。在 `.setSubject(...)` 之前插入 `.setId(...)`，并同步更新方法 Javadoc：

```java
    /**
     * 签发 token：载荷写入 userId（sub）、username 与唯一编号 jti，不写敏感信息。
     *
     * @param userId   用户 ID，写入标准字段 sub（JWT 规定为字符串，解析时再转回 Long）
     * @param username 自定义字段，便于日志排查与前端展示
     * @return header.payload.signature 三段式令牌字符串
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + expireMillis);
        return Jwts.builder()
                // jti：令牌唯一编号，退出登录时作为黑名单 Key。
                // 必须用随机 UUID；禁止用用户 ID、用户名或时间戳充当（同账号多次登录会重复）
                .setId(UUID.randomUUID().toString())
                .setSubject(String.valueOf(userId))          // sub：用户 ID
                .claim("username", username)                 // 自定义业务字段
                .setIssuedAt(now)                            // iat：签发时间
                .setExpiration(expiresAt)                    // exp：过期时间
                .signWith(secretKey, SignatureAlgorithm.HS256) // HS256 签名，载荷被改则验签失败
                .compact();
    }
```

**③ `parseToken()` 的字段校验与装配。** 定位锚点：方法内 `Claims claims = ...`（原 69 行）。保留验签链不动，把取得 `Claims` 之后到 `return` 的旧转换代码整体替换为：

```java
        // 验签链保持原样（Jwts.parserBuilder() ... parseClaimsJws ... getBody()）

        // 必要字段校验：缺 jti / exp / sub / username 的令牌一律拒绝。
        // 历史旧令牌没有 jti，会在这里返回 401，用户重新登录即可（见实施方案"旧 Token 迁移策略"）
        String tokenId = claims.getId();
        Date expiresAt = claims.getExpiration();
        String username = claims.get("username", String.class);
        if (!StringUtils.hasText(tokenId)
                || expiresAt == null
                || !StringUtils.hasText(claims.getSubject())
                || !StringUtils.hasText(username)) {
            // 抛 JwtException：与签名失败同类，由拦截器统一转 401
            throw new JwtException("令牌缺少必要字段");
        }
        return new LoginUser(
                Long.valueOf(claims.getSubject()),
                username,
                tokenId,
                expiresAt.getTime()   // Date 转毫秒：黑名单 TTL 要用毫秒计算，禁止拿秒级值直接相减
        );
```

同时把方法 Javadoc 中"解析出当前用户"一句补为"解析出当前用户与令牌标识（jti、过期毫秒值）"。

**④ 删除旧装配。** 原 75-79 行的旧 `return new LoginUser(Long.valueOf(...), claims.get(...))` 两参装配已被上面替换，确认没有残留。

> 注意：`Long.valueOf` 遇到非法数字会抛 `NumberFormatException`（`IllegalArgumentException` 的子类），现有拦截器的 `catch (JwtException | IllegalArgumentException ...)` 分支本就能兜住它转 401，无需改动。

### 1.3 新增 JwtUtilsTest

**新增：** `backend/src/test/java/com/fly/pocket_ledger_java/util/JwtUtilsTest.java`（完整文件）：

```java
package com.fly.pocket_ledger_java.util;

import com.fly.pocket_ledger_java.config.JwtProperties;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JwtUtils 单元测试：覆盖 jti 唯一性、四字段解析、缺少必要字段的拒绝。
 * 手工构造的"字段缺失令牌"使用与被测 JwtUtils 相同的密钥，保证签名可通过验签，
 * 从而证明拒绝确实发生在必要字段校验，而不是签名校验。
 */
class JwtUtilsTest {

    /** 测试密钥：HS256 要求至少 32 字节，本字符串满足 */
    private static final String SECRET = "jwt-utils-test-secret-32-bytes-minimum!";

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpireMinutes(60);
        jwtUtils = new JwtUtils(properties);
    }

    @Test
    void consecutiveTokensHaveDifferentTokenIds() {
        // 同一用户连续签发两次：jti 必须不同，否则第二次登录会"撤销"第一次的令牌
        String first = jwtUtils.generateToken(1L, "reed");
        String second = jwtUtils.generateToken(1L, "reed");
        assertNotEquals(jwtUtils.parseToken(first).getTokenId(),
                jwtUtils.parseToken(second).getTokenId());
    }

    @Test
    void parseTokenReturnsAllFields() {
        LoginUser user = jwtUtils.parseToken(jwtUtils.generateToken(7L, "reed"));
        assertEquals(Long.valueOf(7L), user.getId());
        assertEquals("reed", user.getUsername());
        // jti 是非空字符串；过期毫秒值必须在未来
        assertNotNull(user.getTokenId());
        assertTrue(user.getTokenId().length() > 0);
        assertTrue(user.getExpiresAtMillis() > System.currentTimeMillis());
    }

    @Test
    void tokenWithoutTokenIdIsRejected() {
        // 签名有效但没有 jti 的令牌（模拟历史旧令牌）：必须拒绝
        assertThrows(JwtException.class,
                () -> jwtUtils.parseToken(handmadeToken(false, true)));
    }

    @Test
    void tokenWithoutExpirationIsRejected() {
        // 签名有效但没有 exp 的令牌：必须拒绝（JJWT 只在 exp 存在时才校验过期）
        assertThrows(JwtException.class,
                () -> jwtUtils.parseToken(handmadeToken(true, false)));
    }

    /**
     * 用与被测对象相同的密钥手工签发"字段缺失"的合法签名令牌。
     *
     * @param withTokenId    true 时写入 jti
     * @param withExpiration true 时写入 exp
     * @return 三段式令牌字符串
     */
    private String handmadeToken(boolean withTokenId, boolean withExpiration) {
        JwtBuilder builder = Jwts.builder()
                .setSubject("1")
                .claim("username", "reed")
                .setIssuedAt(new Date());
        if (withTokenId) {
            builder.setId("handmade-token-id");
        }
        if (withExpiration) {
            builder.setExpiration(new Date(System.currentTimeMillis() + 60_000L));
        }
        return builder
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
    }
}
```

> `JwtBuilder` 接口由 `jjwt-api` 提供（编译期可见），实际实现在 `jjwt-impl`（运行期），与生产代码 `JwtUtils` 用 `Jwts.builder()` 是同一机制，无需额外依赖。

### 1.4 单元验收

```powershell
# 仍在 backend 目录、JAVA_HOME 已设置的前提下执行
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' '-Dtest=JwtUtilsTest' test
# 回归：确认 LoginUser 改造没有破坏既有测试（重点是双参构造的兼容性）
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' '-Dtest=AuthInterceptorTest,AuthControllerWebTest,BillControllerWebTest' test
```

**通过标准：** `JwtUtilsTest` 4 个用例全绿；回归三个测试类全绿（此时 `generateToken` 已带 jti，旧用例不受影响）。对应实施方案行为矩阵 B01、B02 的前半段。

---

## 单元 2：Redis 依赖、连接配置与 503 状态码

本单元只做"接线"，不写业务逻辑，改完编译通过即可。

### 2.1 pom.xml 添加 Redis starter

**修改：** `backend/pom.xml`。定位锚点：`jjwt-jackson` 依赖块（原 93-99 行）的 `</dependency>` 之后、"测试：Boot 2.3 的 starter-test..."注释之前，空一行插入：

```xml
        <!-- Redis：退出登录的令牌黑名单存储（spring-boot-starter-data-redis，版本由父 POM 管理，禁止手写） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```

不要写 `<version>`：Boot 2.3.0.RELEASE 父 POM 已托管版本，手写反而可能引入不兼容版本。

### 2.2 application.yml 合并 redis 配置

**修改：** `backend/src/main/resources/application.yml`。定位锚点：`spring:` 节点下 `datasource:` 的 `hikari:` 子块结束行（原 16 行 `max-lifetime: 1800000`）之后、顶格的 `mybatis-plus:` 之前。插入内容与 `datasource:` 同缩进（2 个空格）：

```yaml
  # Redis：退出登录的令牌黑名单存储。
  # 注意是 Boot 2.3 的 spring.redis.*，不是 Boot 3 的 spring.data.redis.*
  redis:
    host: ${REDIS_HOST:127.0.0.1}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    database: ${REDIS_DATABASE:0}
    timeout: 2s
```

两个易错点：① 不得新增第二个顶层 `spring:` 节点，必须合并进现有节点；② `redis:` 前是 2 空格缩进，与 `datasource:` 平级，错一格 YAML 就解析不到。

### 2.3 ResultCode 新增 503

**修改：** `backend/src/main/java/com/fly/pocket_ledger_java/common/ResultCode.java`。定位锚点：枚举末尾的 `INTERNAL_ERROR(500, "服务器内部错误");`（原 40 行）。把它前面加一个枚举项，**同时把 INTERNAL_ERROR 行尾的分号改成逗号位置关系不变**——具体是把原：

```java
    WRITE_CONFLICT(409, "数据正在被修改，请稍后重试"),

    /** 未处理的服务端异常 */
    INTERNAL_ERROR(500, "服务器内部错误");
```

改为：

```java
    WRITE_CONFLICT(409, "数据正在被修改，请稍后重试"),

    /** 认证依赖（Redis 黑名单）暂不可用：宁可拒绝服务，也不放行状态不确定的令牌 */
    AUTH_SERVICE_UNAVAILABLE(503, "认证服务暂不可用，请稍后重试"),

    /** 未处理的服务端异常 */
    INTERNAL_ERROR(500, "服务器内部错误");
```

只有 `AUTH_SERVICE_UNAVAILABLE` 一行是新增的，分号仍只在 `INTERNAL_ERROR` 行尾。

### 2.4 单元验收

```powershell
# 编译 + 上下文加载：确认依赖与配置不破坏启动。
# Lettuce 客户端是懒连接：Bean 创建时不连 Redis，本机没装 Redis 也能通过本项。
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' '-Dtest=PocketLedgerJavaApplicationTests' test
```

**通过标准：** `contextLoads` 结果与单元 0 基线一致（该测试若在基线就依赖本机 MySQL，保持同样行为即可，环境差异不算本次失败）。对应实施方案 5.1 表中 pom.xml / application.yml / ResultCode 三行。

---

## 单元 3：TokenBlacklist 黑名单组件

### 3.1 新增组件

**新增：** `backend/src/main/java/com/fly/pocket_ledger_java/service/support/TokenBlacklist.java`（完整文件）：

```java
package com.fly.pocket_ledger_java.service.support;

import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 登录令牌黑名单：负责 Redis Key 拼接、TTL 计算与读写异常转译。
 *
 * <p>Key 约定：auth:revoked:{jti}，String 类型，Value 固定 "1"。
 * TTL 为令牌剩余有效期（毫秒）：记录自然失效时 JWT 本身也已过期，无需定时清理任务。</p>
 *
 * <p>只接受已验签的内部参数（tokenId 来自 JwtUtils 解析结果），不解析 HTTP 请求，
 * 也不做通用 Redis 工具类。读写失败一律转 BusinessException(503)：
 * 撤销状态是认证安全数据，宁可拒绝服务，不能当作"未撤销"放行。</p>
 */
@Slf4j
@Component
public class TokenBlacklist {

    /** 黑名单 Key 前缀，与全量联调时的 Redis CLI 检查命令保持一致 */
    private static final String KEY_PREFIX = "auth:revoked:";

    /** String 专用的 Redis 模板：黑名单只存字符串 "1"，不需要序列化配置 */
    private final StringRedisTemplate redisTemplate;

    public TokenBlacklist(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 查询令牌是否已被撤销。
     *
     * @param tokenId 令牌唯一编号（jti）
     * @return true 表示已撤销，请求必须拒绝
     */
    public boolean isRevoked(String tokenId) {
        try {
            Boolean exists = redisTemplate.hasKey(KEY_PREFIX + tokenId);
            // 普通同步调用应返回 true/false；null 说明结果不确定，不能当成未撤销
            if (exists == null) {
                throw new BusinessException(ResultCode.AUTH_SERVICE_UNAVAILABLE);
            }
            return exists;
        } catch (DataAccessException exception) {
            // 连接失败等异常：记录服务端日志，对客户端统一 503
            log.error("读取令牌撤销状态失败", exception);
            throw new BusinessException(ResultCode.AUTH_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * 撤销令牌：写入黑名单，TTL 为令牌剩余有效期。
     *
     * @param tokenId          令牌唯一编号（jti）
     * @param expiresAtMillis  令牌过期时间（毫秒时间戳，来自 JWT 的 exp）
     */
    public void revoke(String tokenId, long expiresAtMillis) {
        long remainingMillis = expiresAtMillis - System.currentTimeMillis();
        // 剩余有效期 <= 0：令牌已自然失效，无需黑名单记录。
        // 该分支也覆盖"拦截器放行后、到达 Service 前刚好过期"的边界情况
        if (remainingMillis <= 0) {
            return;
        }
        try {
            // SET 与 TTL 必须一次完成：拆成先 SET 再 EXPIRE，两步之间失败会留下永不过期的 Key
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + tokenId,
                    "1",
                    remainingMillis,
                    TimeUnit.MILLISECONDS
            );
        } catch (DataAccessException exception) {
            log.error("写入令牌撤销状态失败", exception);
            throw new BusinessException(ResultCode.AUTH_SERVICE_UNAVAILABLE);
        }
    }
}
```

不添加 `@Transactional`：MySQL 事务管不了 Redis 写入的原子性；也不使用 pipeline / Redis 事务。

### 3.2 新增单元测试

**新增：** `backend/src/test/java/com/fly/pocket_ledger_java/service/support/TokenBlacklistTest.java`（完整文件）。全部用 Mockito mock `StringRedisTemplate`，不连接真实 Redis：

```java
package com.fly.pocket_ledger_java.service.support;

import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TokenBlacklist 单元测试：TTL 毫秒语义、过期跳过、异常一律 503、绝不放行不确定状态。
 */
class TokenBlacklistTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private TokenBlacklist blacklist;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        // opsForValue() 是模板入口方法，先 stub 掉，后续 verify 落在 values 上
        when(redis.opsForValue()).thenReturn(values);
        blacklist = new TokenBlacklist(redis);
    }

    @Test
    void writesRemainingLifetimeInMilliseconds() {
        long before = System.currentTimeMillis();
        long expiresAt = before + 60_000L;   // 剩余约 60 秒

        blacklist.revoke("token-a", expiresAt);

        long after = System.currentTimeMillis();
        // 捕获实际传给 Redis 的 TTL，断言落在 [expiresAt - after, expiresAt - before] 区间：
        // 既证明用了"剩余有效期"而不是固定 24 小时，也证明没有把毫秒错算成秒
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(values).set(eq("auth:revoked:token-a"), eq("1"),
                ttl.capture(), eq(TimeUnit.MILLISECONDS));
        assertTrue(ttl.getValue() > 0);
        assertTrue(ttl.getValue() <= expiresAt - before);
        assertTrue(ttl.getValue() >= expiresAt - after);
    }

    @Test
    void expiredTokenSkipsRedisWrite() {
        // 过期时间在过去：令牌已自然失效，不应产生任何 Redis 写入
        blacklist.revoke("token-a", System.currentTimeMillis() - 1_000L);
        verifyNoInteractions(values);
    }

    @Test
    void isRevokedReflectsKeyExistence() {
        when(redis.hasKey("auth:revoked:token-a")).thenReturn(true);
        when(redis.hasKey("auth:revoked:token-b")).thenReturn(false);
        assertTrue(blacklist.isRevoked("token-a"));
        assertFalse(blacklist.isRevoked("token-b"));
    }

    @Test
    void uncertainKeyResultIsTreatedAsUnavailable() {
        // 同步调用返回 null：状态不确定，必须 503，不能当"未撤销"
        when(redis.hasKey("auth:revoked:token-a")).thenReturn(null);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> blacklist.isRevoked("token-a"));
        assertEquals(ResultCode.AUTH_SERVICE_UNAVAILABLE.getCode(), exception.getCode());
    }

    @Test
    void redisReadFailureReturns503() {
        when(redis.hasKey("auth:revoked:token-a"))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> blacklist.isRevoked("token-a"));
        assertEquals(ResultCode.AUTH_SERVICE_UNAVAILABLE.getCode(), exception.getCode());
    }

    @Test
    void redisWriteFailureReturns503() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> blacklist.revoke("token-a", System.currentTimeMillis() + 60_000L));
        assertEquals(ResultCode.AUTH_SERVICE_UNAVAILABLE.getCode(), exception.getCode());
    }
}
```

### 3.3 单元验收

```powershell
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' '-Dtest=TokenBlacklistTest' test
```

**通过标准：** 6 个用例全绿。注意本单元结束后 `mvn test` 全量会启动 Spring 上下文的测试但仍不会连 Redis（懒连接），可顺手跑一次确认无编译问题。对应实施方案行为矩阵 B07（过期跳过）、B08（TTL 语义）、B09/B10 的组件级前半段。

---

## 单元 4：拦截器接入黑名单校验

### 4.1 修改 AuthInterceptor

**修改：** `backend/src/main/java/com/fly/pocket_ledger_java/interceptor/AuthInterceptor.java`。共 4 处改动。

**① import 新增 1 行。** 定位锚点：`import com.fly.pocket_ledger_java.common.ResultCode;` 之后插入：

```java
import com.fly.pocket_ledger_java.service.support.TokenBlacklist;
```

**② 字段与构造器。** 定位锚点：`private final ObjectMapper objectMapper;`（原 57 行）到构造器结束（原 62 行）。把构造器整体替换为：

```java
    /** 负责把统一的认证失败对象序列化为 JSON 响应。 */
    private final ObjectMapper objectMapper;

    /** 负责查询令牌是否已被退出登录撤销（Redis 黑名单）。 */
    private final TokenBlacklist tokenBlacklist;

    public AuthInterceptor(JwtUtils jwtUtils, ObjectMapper objectMapper,
                           TokenBlacklist tokenBlacklist) {
        this.jwtUtils = jwtUtils;
        this.objectMapper = objectMapper;
        this.tokenBlacklist = tokenBlacklist;
    }
```

**③ 类 Javadoc 的执行顺序列表插入一步。** 定位锚点：类注释 `<ol>` 中 `<li>认证成功后，把解析出的当前用户保存到`（原 30 行）之前插入一行：

```java
 *     <li>校验通过后查询令牌黑名单，已撤销的令牌按未认证处理；</li>
```

**④ preHandle 插入黑名单检查。** 定位锚点：JWT 解析的 `catch (JwtException | IllegalArgumentException invalid)` 块结束（原 115 行）之后、原"第 3 步"注释（原 117 行）之前插入，并把原"第 3 步"注释里的"第 3 步"改为"第 4 步"：

```java
        /*
         * 第 3 步：查询令牌黑名单。
         *
         * 已撤销的令牌虽然签名和有效期都正常，但用户已主动退出，按未认证处理。
         * 注意：这段代码刻意不放进上面的 try/catch —— Redis 故障抛出的 BusinessException
         * 属于"认证依赖不可用"，语义是 503，不能被吞成"token 无效"的 401，
         * 让它向上抛给 GlobalExceptionHandler 统一处理。
         */
        if (tokenBlacklist.isRevoked(loginUser.getTokenId())) {
            log.warn("认证失败：token 已撤销 [{} {}]",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorized(response);
            return false;
        }
```

其余全部保留：OPTIONS 放行、非 Controller 放行、白名单由 `WebMvcConfig` 管理不改、`afterCompletion()` 清理不动。日志只记方法与路径，不输出原始 Token。

> `WebMvcConfig` 无需改动：它注入的是 `AuthInterceptor` 这个 Bean，构造参数变多由 Spring 自动装配。

### 4.2 修改 AuthInterceptorTest

**修改：** `backend/src/test/java/com/fly/pocket_ledger_java/interceptor/AuthInterceptorTest.java`。共 5 处改动。

**① import 区新增。** 定位锚点：现有 import 区（原 3-14 行）。补充：

```java
import com.fly.pocket_ledger_java.service.support.TokenBlacklist;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
```

（`io.jsonwebtoken.*` 三个与 `java.*` 两个按现有分组位置插入；`org.mockito` 三个静态导入放在现有 `org.junit.jupiter.api.Assertions.*` 静态导入之后。）

**② 抽出密钥常量。** 定位锚点：`private JwtUtils jwtUtils;`（原 20 行）一带，在字段区加：

```java
    /** 测试密钥：手工构造"字段缺失令牌"时必须与 jwtUtils() 用同一密钥，签名才能通过验签 */
    private static final String SECRET = "auth-interceptor-test-secret-32-bytes-minimum";
```

并把原 `jwtUtils(long)` 辅助方法里的 `properties.setSecret("auth-interceptor-test-secret-32-bytes-minimum");` 改为 `properties.setSecret(SECRET);`。

**③ setUp 构造 mock 并换三参构造。** 定位锚点：字段区的 `private AuthInterceptor interceptor;` 与 `setUp()`（原 21-29 行）。改为：

```java
    private JwtUtils jwtUtils;
    private TokenBlacklist tokenBlacklist;   // 黑名单组件用 mock，本测试不连 Redis
    private AuthInterceptor interceptor;
    private HandlerMethod handler;

    @BeforeEach
    void setUp() throws Exception {
        jwtUtils = jwtUtils(60);
        // Mockito 默认让 boolean 方法返回 false（未撤销），多数用例无需显式 stub
        tokenBlacklist = mock(TokenBlacklist.class);
        interceptor = new AuthInterceptor(jwtUtils, new ObjectMapper(), tokenBlacklist);
        handler = new HandlerMethod(this, getClass().getMethod("endpoint"));
    }
```

**④ 现有三个拒绝用例各追加一行。** `missingTokenIsRejected()`、`malformedTokenIsRejected()`、`expiredTokenIsRejected()` 的 `assertUnauthorized();` 之后各加：

```java
        // JWT 层面就失败的请求，不应触碰 Redis（黑名单查询在验签之后）
        verifyNoInteractions(tokenBlacklist);
```

**⑤ 新增两个用例与一个辅助方法。** 放在 `expiredTokenIsRejected()` 之后：

```java
    @Test
    void revokedTokenIsRejected() throws Exception {
        String token = jwtUtils.generateToken(1L, "reed");
        String tokenId = jwtUtils.parseToken(token).getTokenId();
        // stub：该 jti 已被撤销
        when(tokenBlacklist.isRevoked(tokenId)).thenReturn(true);
        bearer(token);
        assertUnauthorized();
    }

    @Test
    void tokenWithoutTokenIdIsRejectedBeforeBlacklist() throws Exception {
        // 签名有效但缺 jti 的旧令牌：在解析阶段就被拒，不应查询 Redis
        bearer(tokenWithoutTokenId());
        assertUnauthorized();
        verifyNoInteractions(tokenBlacklist);
    }

    /** 用测试密钥手工签发"缺 jti"的合法签名令牌：验签能过，卡在必要字段校验 */
    private String tokenWithoutTokenId() {
        return Jwts.builder()
                .setSubject("1")
                .claim("username", "reed")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
    }
```

### 4.3 单元验收

```powershell
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' '-Dtest=AuthInterceptorTest' test
```

**通过标准：** 7 个用例全绿（原 5 个 + 新 2 个）。重点核对：`revokedTokenIsRejected` 返回 401 且 `UserContext` 为空；三个 JWT 失败用例与缺 jti 用例都验证了 `verifyNoInteractions(tokenBlacklist)`。对应实施方案行为矩阵 B02（不访问 Redis）、B03、B04 的拦截器级验证。

> 503 链路（Redis 异常 → `GlobalExceptionHandler` → 503）本单元不测：`preHandle()` 直调无法证明 HTTP 响应正确，统一放到单元 5 的真实 MVC 组合测试 `AuthLogoutWebTest` 中验证。

---

## 单元 5：退出业务与 /auth/logout 接口

### 5.1 AuthService 接口加方法

**修改：** `backend/src/main/java/com/fly/pocket_ledger_java/service/AuthService.java`。定位锚点：`getCurrentUser()` 声明（原 34 行）之后、接口结束大括号之前插入，并把类注释"定义注册、登录及当前用户查询"改为"定义注册、登录、当前用户查询及退出"：

```java
    /**
     * 撤销当前请求使用的访问令牌（写入黑名单）。
     * 令牌信息从鉴权上下文获取，不接受调用方传入的 jti 或过期时间。
     */
    void logout();
```

### 5.2 AuthServiceImpl 实现退出

**修改：** `backend/src/main/java/com/fly/pocket_ledger_java/service/impl/AuthServiceImpl.java`。共 4 处改动。

**① import 新增 3 行。** 定位锚点：`import com.fly.pocket_ledger_java.service.AuthService;`（原 10 行）一带插入：

```java
import com.fly.pocket_ledger_java.service.support.TokenBlacklist;
```

（放在 AuthService 导入之后）；`import com.fly.pocket_ledger_java.util.JwtUtils;`（原 11 行）一带插入：

```java
import com.fly.pocket_ledger_java.util.LoginUser;
```

（放在 JwtUtils 与 UserContext 之间）；`import org.springframework.transaction.annotation.Transactional;`（原 18 行）之前插入：

```java
import org.springframework.util.StringUtils;
```

**② 字段与构造器加一个依赖。** 定位锚点：`private final JwtUtils jwtUtils;`（原 28 行）与构造器（原 37-42 行），替换为：

```java
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    /** 令牌黑名单：退出登录时写入撤销记录 */
    private final TokenBlacklist tokenBlacklist;

    /**
     * 注入认证流程所需的持久化、安全与令牌组件。
     *
     * @param userMapper 用户持久层
     * @param passwordEncoder 密码编码器
     * @param jwtUtils 令牌生成工具
     * @param tokenBlacklist 令牌黑名单
     */
    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder,
                           JwtUtils jwtUtils, TokenBlacklist tokenBlacklist) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.tokenBlacklist = tokenBlacklist;
    }
```

**③ 新增 logout 实现。** 定位锚点：`getCurrentUser()` 方法结束（原 112 行）之后、`// ==================== 私有辅助方法 ====================` 注释之前插入：

```java
    // ==================== 退出登录 ====================
    /**
     * 撤销当前请求使用的访问令牌。
     *
     * <p>令牌信息只取自拦截器写入的鉴权上下文：不读 HTTP 头、不重新解析 JWT、
     * 不查用户表，也不把清 ThreadLocal 当作撤销实现（那只是本线程的内存）。</p>
     */
    @Override
    public void logout() {
        LoginUser user = UserContext.get();
        // 防御性校验：正常请求一定带着完整令牌信息进入这里；缺失说明链路异常，按未认证处理
        if (user == null || !StringUtils.hasText(user.getTokenId())
                || user.getExpiresAtMillis() <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        tokenBlacklist.revoke(user.getTokenId(), user.getExpiresAtMillis());
    }
```

**④ 类注释更新。** 类 Javadoc"协调用户持久化、密码校验与令牌签发"补为"协调用户持久化、密码校验、令牌签发与令牌撤销"。注意 logout 方法**不加** `@Transactional`：撤销状态在 Redis，MySQL 事务无意义。

### 5.3 AuthController 暴露接口

**修改：** `backend/src/main/java/com/fly/pocket_ledger_java/controller/AuthController.java`。共 2 处改动。

**① 类注释路径列表更新。** 原"路径对齐契约：/auth/register、/auth/login、/auth/me（无 /api 前缀）"改为：

```java
 * 认证接口，路径对齐契约：/auth/register、/auth/login、/auth/me、/auth/logout（无 /api 前缀）。
 * register、login 在 auth.ignore-urls 白名单里，无需登录；logout 需要登录（复用拦截器鉴权）。
```

**② 新增端点。** 定位锚点：`me()` 方法（原 55-58 行）之后、类结束大括号之前插入：

```java
    /**
     * 退出登录（需登录）：撤销当前请求携带的令牌。
     * 不接收请求体：jti 与过期时间由鉴权上下文提供，防止客户端伪造撤销目标。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.success("退出成功", null);
    }
```

同时确认：**不要**把 `/auth/logout` 加进 `application.yml` 的 `auth.ignore-urls`——退出接口必须先过鉴权。

### 5.4 替换 AuthControllerWebTest 的 404 测试

**修改：** `backend/src/test/java/com/fly/pocket_ledger_java/controller/AuthControllerWebTest.java`。共 2 处改动。

**① 静态导入补充。** 定位锚点：原 17-19 行的静态导入区，补充：

```java
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
```

**② 替换用例。** 删除原 `removedLogoutEndpointReturns404()`（原 116-119 行），原位置替换为：

```java
    @Test
    void logoutReturnsSuccess() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("退出成功"))
                // 契约：退出成功 data 固定为 null
                .andExpect(jsonPath("$.data").value(nullValue()));
        // Controller 只做转发：必须真正调用了 Service 的退出方法
        verify(authService).logout();
    }
```

> 该切片测试沿用 mock 拦截器直通，"不带 Token 也能 200"只是切片假象，不代表真实接口允许匿名调用——真实链路由 5.5 的 `AuthLogoutWebTest` 验证。

### 5.5 新增 AuthLogoutWebTest（真实组合链路）

**新增：** `backend/src/test/java/com/fly/pocket_ledger_java/controller/AuthLogoutWebTest.java`（完整文件）。装配真实 `JwtUtils` + 真实拦截器 + 真实 Service + 真实 Controller + 真实 `GlobalExceptionHandler`，只 mock 黑名单与持久层：

```java
package com.fly.pocket_ledger_java.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fly.pocket_ledger_java.common.ResultCode;
import com.fly.pocket_ledger_java.config.JwtProperties;
import com.fly.pocket_ledger_java.entity.User;
import com.fly.pocket_ledger_java.exception.BusinessException;
import com.fly.pocket_ledger_java.exception.GlobalExceptionHandler;
import com.fly.pocket_ledger_java.interceptor.AuthInterceptor;
import com.fly.pocket_ledger_java.mapper.UserMapper;
import com.fly.pocket_ledger_java.service.impl.AuthServiceImpl;
import com.fly.pocket_ledger_java.service.support.TokenBlacklist;
import com.fly.pocket_ledger_java.util.JwtUtils;
import com.fly.pocket_ledger_java.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 退出登录真实组合链路测试：真实 JwtUtils / 拦截器 / AuthServiceImpl / Controller /
 * GlobalExceptionHandler，仅 mock 黑名单与持久层。
 * 重点验证 @WebMvcTest 覆盖不到的两件事：拦截器真的拦、拦截器抛出的
 * BusinessException 真的能被 ControllerAdvice 转成 HTTP 503。
 */
class AuthLogoutWebTest {

    private static final String SECRET = "auth-logout-web-test-secret-32-bytes-min!";

    private TokenBlacklist blacklist;
    private MockMvc mvc;
    private String token;
    private String tokenId;

    @BeforeEach
    void setUp() {
        // 真实 JwtUtils：与生产同一套签发、解析逻辑
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpireMinutes(60);
        JwtUtils jwtUtils = new JwtUtils(properties);

        blacklist = mock(TokenBlacklist.class);

        // /auth/me 通过拦截器后会查库：stub 返回固定用户，
        // 保证用例里的 401 只可能来自拦截器拒绝，而不是"用户不存在"
        UserMapper userMapper = mock(UserMapper.class);
        User sampleUser = new User();
        sampleUser.setId(1L);
        sampleUser.setUsername("reed");
        when(userMapper.selectById(any())).thenReturn(sampleUser);

        AuthServiceImpl service = new AuthServiceImpl(
                userMapper, mock(PasswordEncoder.class), jwtUtils, blacklist);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(service))
                .addInterceptors(new AuthInterceptor(jwtUtils, new ObjectMapper(), blacklist))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // 每个用例用自己的 60 分钟令牌，互不干扰
        token = jwtUtils.generateToken(1L, "reed");
        tokenId = jwtUtils.parseToken(token).getTokenId();
    }

    @AfterEach
    void cleanUp() {
        // MockMvc 独立线程之外的兜底清理，防止上下文串到其他测试
        UserContext.clear();
    }

    @Test
    void logoutRevokesTokenAndRepeatLogoutIsUnauthorized() throws Exception {
        // 用 AtomicBoolean 模拟真实黑名单行为：revoke 执行后，同一 jti 的查询变为 true
        AtomicBoolean revoked = new AtomicBoolean(false);
        when(blacklist.isRevoked(tokenId)).thenAnswer(call -> revoked.get());
        doAnswer(call -> {
            revoked.set(true);
            return null;
        }).when(blacklist).revoke(eq(tokenId), anyLong());

        // 第一次退出：200 + 契约文案
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("退出成功"));
        // 请求结束后上下文必须已清理
        assertNull(UserContext.get());

        // 同一 Token 再访问受保护接口：401（B04）
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        assertNull(UserContext.get());

        // 重复退出：401（B05，状态幂等的第二次退出）
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // 第二次请求在拦截器就被拒：撤销动作真正只执行了一次
        verify(blacklist, times(1)).revoke(eq(tokenId), anyLong());
    }

    @Test
    void anotherTokenOfSameUserIsUnaffected() throws Exception {
        // B06：同账号另一处登录的独立 Token 不受本次退出影响
        AtomicBoolean revoked = new AtomicBoolean(false);
        when(blacklist.isRevoked(tokenId)).thenAnswer(call -> revoked.get());
        doAnswer(call -> {
            revoked.set(true);
            return null;
        }).when(blacklist).revoke(eq(tokenId), anyLong());
        String otherToken = jwtUtils().generateToken(1L, "reed");
        // otherToken 的 tokenId 未被 stub，Mockito 默认返回 false（未撤销）

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 退出的 Token 已失效
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        // 另一个 Token 仍然可用
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk());
    }

    @Test
    void blacklistReadFailureReturns503() throws Exception {
        // B09：Redis 读失败 → 503，不进入业务方法、不建立用户上下文
        when(blacklist.isRevoked(tokenId))
                .thenThrow(new BusinessException(ResultCode.AUTH_SERVICE_UNAVAILABLE));
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("认证服务暂不可用，请稍后重试"));
        assertNull(UserContext.get());
    }

    @Test
    void blacklistWriteFailureReturns503() throws Exception {
        // B10：Redis 写失败 → 503，不得返回"退出成功"
        doThrow(new BusinessException(ResultCode.AUTH_SERVICE_UNAVAILABLE))
                .when(blacklist).revoke(eq(tokenId), anyLong());
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
        // 请求结束（含异常路径）后上下文已清理
        assertNull(UserContext.get());
    }

    @Test
    void logoutWithoutTokenIsUnauthorized() throws Exception {
        // B11：不带 Token 的退出请求被拦截器拒绝
        mvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized());
        verify(blacklist, times(0)).revoke(anyString(), anyLong());
    }

    @Test
    void getLogoutIsMethodNotAllowed() throws Exception {
        // B13：GET 退出地址不执行退出业务，按现有 MVC 行为返回 405
        mvc.perform(get("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
        verify(blacklist, times(0)).revoke(anyString(), anyLong());
    }

    /** 用 setUp 相同配置再建一个 JwtUtils（签发另一个独立 Token 用） */
    private JwtUtils jwtUtils() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpireMinutes(60);
        return new JwtUtils(properties);
    }
}
```

### 5.6 单元验收

```powershell
# 定向：本单元新增与改动的三个测试类
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' '-Dtest=AuthControllerWebTest,AuthLogoutWebTest' test
# 后端至此全部完成：跑一次全量
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' test
```

**通过标准：** `AuthControllerWebTest` 7 个用例（其中 1 个由 404 测试替换为成功响应测试）与 `AuthLogoutWebTest` 6 个用例全绿；全量测试与单元 0 基线一致无新增失败。对应实施方案行为矩阵 B03-B06、B09-B11、B13 的 HTTP 层验证。

---

## 单元 6：静态前端退出闭环

### 6.1 修改 api.js

**修改：** `fronted_static/js/api.js`。共 3 处改动。

**① 文件头注释更新。** 定位锚点：第 3 行 `认证：POST /auth/register、POST /auth/login、GET /auth/me`，改为：

```js
   认证：POST /auth/register、POST /auth/login、GET /auth/me、POST /auth/logout
```

**② 401 分支防止旧请求误删新 Token。** 定位锚点：`request()` 内 `if (res.status === 401 && !path.startsWith("/auth/login"))` 块（原 68-71 行）。把其中：

```js
        if (token) clearToken();
```

改为：

```js
        // 只清除"本次请求发出时"的那个 token：请求期间用户可能已重新登录写入新 token
        if (token && getToken() === token) clearToken();
```

**③ logout 改为调用后端。** 定位锚点：原 99 行 `function logout() { clearToken(); }` 整行替换为：

```js
  /**
   * 退出登录：请求服务端注销当前 token，完成后清本地 token。
   * - 无 token：无需发请求，直接返回（本地本来就未登录）
   * - 401（error.auth）：token 已失效（含重复退出），视为注销完成，静默处理
   * - 503 / 超时 / 断网等：继续向上抛，由调用方提示"服务端注销未确认"
   */
  async function logout() {
    const token = getToken();
    if (!token) return;
    try {
      await request("/auth/logout", { method: "POST" });
    } catch (error) {
      if (!error.auth) throw error;
    } finally {
      // 请求期间 token 被换成新值时不清，防止误删新会话
      if (!getToken() || getToken() === token) clearToken();
    }
  }
```

保持导出对象里的 `logout`、`clearToken` 与存储键 `pl_token` 不变。

### 6.2 修改 app.js

**修改：** `fronted_static/js/app.js`。共 2 处改动。

**① 退出按钮监听改为异步等待。** 定位锚点：原 904-908 行（搜索注释"退出登录：清 token 后整页重载"），整体替换为：

```js
    // 退出登录：先等服务端注销完成，再整页重载清空所有内存状态。
    // 不能发完请求立刻刷新——浏览器可能直接取消尚未完成的注销请求
    logoutBtn.addEventListener("click", async () => {
      if (logoutBtn.disabled) return;   // pending 期间防重复点击（F05）
      logoutBtn.disabled = true;
      try {
        await API.logout();
      } catch {
        // 503 / 断网 / 超时：本地仍要退出，但如实告知服务端未确认
        window.alert("本地已退出，服务端注销未确认");
      } finally {
        location.reload();
      }
    });
```

**② 启动恢复失败不再调用退出接口。** 定位锚点：文件末尾（原 1258-1266 行）`enterApp().catch(...)` 块。把其中的 `API.logout();` 改为 `API.clearToken();`：

```js
  // 有 token 先验证（过期会被踢回登录页）；没有就直接进登录页
  if (API.getToken()) {
    enterApp().catch(() => {
      // 恢复失败只做本地清理：失效 token 不该再拿去调退出接口（F04）
      API.clearToken();
      showLogin();
    });
  } else {
    showLogin();
  }
```

不使用 `localStorage.clear()`：`pl_api_base`、`pl_theme` 必须保留（F08）。

### 6.3 单元验收（浏览器手动）

先启动后端与本地 Redis，再打开 `fronted_static/index.html`。可先用 `node --check` 做语法自检：

```powershell
node --check 'C:\fly_develop\java_ai_project\pocket_ledger_java_fly\fronted_static\js\api.js'
node --check 'C:\fly_develop\java_ai_project\pocket_ledger_java_fly\fronted_static\js\app.js'
```

浏览器开发者工具开 Network（勾选 Preserve log）与 Application 面板，逐项核对：

| 编号 | 操作 | 预期 |
| --- | --- | --- |
| F01 | 正常登录后点击退出 | 发出一次 `POST /auth/logout` 且携带 Bearer Token；**响应完成后**才刷新，回到登录入口 |
| F02 | 刷新后检查存储 | `pl_token` 已删除；`pl_api_base`、`pl_theme` 仍在 |
| F03 | 控制台手动 `API.logout()`（已撤销 Token） | 401 被当作已失效静默处理，无报错弹窗、无循环请求 |
| F04 | 无 Token 时 `API.logout()` | 不发任何请求 |
| F05 | 网络限速下快速连点退出按钮 | pending 期间按钮禁用，只发一次请求 |
| F06 | 人为让 enterApp 失败（如改错 API 地址后刷新） | 只执行本地清理，无 `/auth/logout` 请求 |

**通过标准：** 6 项全部符合；每项记录操作、HTTP 状态与页面表现。

---

## 单元 7：全量回归与真实联调验收

### 7.1 后端全量回归

```powershell
Set-Location 'C:\fly_develop\java_ai_project\pocket_ledger_java_fly\backend'
$env:JAVA_HOME = 'C:\fly_develop\Java\temurin-jdk8u504-b01'
# 定向五个测试类（与实施方案 11.3 一致）
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' '-Dtest=JwtUtilsTest,TokenBlacklistTest,AuthInterceptorTest,AuthControllerWebTest,AuthLogoutWebTest' test
# 项目完整测试
& 'C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd' test
```

**通过标准：** 定向 5 类 30 个用例全绿（JwtUtilsTest 4 + TokenBlacklistTest 6 + AuthInterceptorTest 7 + AuthControllerWebTest 7 + AuthLogoutWebTest 6）；全量与基线一致。真实数据库集成测试按 `backend/README.md` 开关处理并如实记录，不删除失败测试。

### 7.2 真实 Redis 与 HTTP 联调

隔离环境启动 Redis、MySQL、后端（前端 API 地址指向 Java 服务）。核心步骤（完整清单见实施方案 11.4）：

1. 登录拿 Token A；再次登录拿 Token B。
2. A 调 `/auth/me` → 200；A 调 `POST /auth/logout` → 200。
3. Redis CLI 核对：`GET auth:revoked:<A的jti>` 为 `"1"`；`PTTL auth:revoked:<A的jti>` 为正数且接近 `exp 毫秒值 - 当前时间`（`-1` 表示永不过期，是失败；`-2` 表示 Key 不存在）。
4. A 再调 `/auth/me` → 401；B 调同接口 → 200（B06）。
5. A 重复退出 → 401（B05）。
6. 临时把 `jwt.expire-minutes` 改为 1 分钟，验证自然到期后黑名单 Key 自动消失且旧 Token 仍被拒；测完恢复配置。
7. 停掉专用测试 Redis：受保护请求与退出请求均应 503，前端提示"服务端注销未确认"。
8. 重启后端，黑名单仍生效（Redis 持有状态，不随应用重启丢失）。

> 安全边界：`<A的jti>` 通过本地测试程序 `JwtUtils.parseToken(A)` 获取，**不要**把真实 Token 贴到在线 JWT 解码网站；故障模拟只允许在专用测试 Redis 上做，禁止对共享/生产实例执行清库或停服。

**最终验收的核心证据（与实施方案第 13 节一致）：** 退出成功后，复制出的旧 Token 无法再访问任何受保护接口；另一次登录的 Token 不受影响；Redis 黑名单 Key 带有正确的剩余 TTL。

---

## 附录：常见报错对照

| 报错现象 | 通俗解释 | 处理 |
| --- | --- | --- |
| `Unable to connect to Redis` / `RedisConnectionFailureException` | 后端找不到 Redis 服务（未启动或端口不对） | 启动本机 Redis 或设 `REDIS_HOST/REDIS_PORT`；单元测试全程 mock，不应出现此错 |
| 改完后旧用户全部 401 | 旧 Token 没有 `jti`，被必要字段校验拒绝 | 这是**预期行为**（实施方案"旧 Token 迁移策略"）：重新登录即得新 Token |
| `cannot find symbol: class TokenBlacklist`（编译错） | import 漏了或包路径不对 | 确认类在 `service.support` 包，且拦截器/实现类都加了 import |
| `AuthInterceptorTest` 构造器红线 | 还在用旧的双参构造 `new AuthInterceptor(jwtUtils, new ObjectMapper())` | 换成单元 4.2 ③ 的三参构造 |
| YAML 解析失败 / Redis 配置不生效 | `redis:` 缩进不对或出现了第二个 `spring:` 节点 | 对照单元 2.2 的缩进说明，与 `datasource:` 严格平级 |
| 退出返回 200 但旧 Token 仍可用 | 黑名单 Key 没写进去或 TTL 配置错 | 用 Redis CLI 核对 `GET/PTTL auth:revoked:<jti>`；确认没有把 `/auth/logout` 加进白名单导致绕过鉴权上下文 |

## 完成自查清单

- [ ] 登录 Token 带唯一 `jti`，解析强制要求 `jti` 与 `exp`（单元 1）
- [ ] TTL 为令牌实际剩余有效期，毫秒语义，SET 与 TTL 一次完成（单元 3）
- [ ] 所有受保护请求检查黑名单；Redis 不可用时 503 不放行（单元 4、5）
- [ ] 退出只影响当前 Token，不影响其他登录（单元 5 `anotherTokenOfSameUserIsUnaffected`）
- [ ] 前端等待注销完成后才刷新；失败如实提示"服务端注销未确认"（单元 6）
- [ ] 旧请求不清除新 Token；`pl_api_base`、`pl_theme` 不被清除（单元 6）
- [ ] `frontend_vue` 未被修改；后端生产代码只新增 `TokenBlacklist` 一个文件
- [ ] 旧 404 测试已替换；真实 MVC 异常链路、真实 Redis TTL、浏览器验收均有执行记录
- [ ] 哪些测试实际通过、哪些因环境未执行，均已如实记录——本文参考代码本身不算测试通过

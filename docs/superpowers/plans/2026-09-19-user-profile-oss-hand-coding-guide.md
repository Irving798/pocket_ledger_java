# 用户资料与 OSS 头像：逐单元手敲代码指导书

> 面向亲自编码的开发者。自动化执行时可参考 executing-plans 技能；本仓库子代理只用于探索和核验，不负责写代码。本文只提供实施指导，不能把代码示例或预期输出当成实际通过的验收结果。

**目标：** 从当前项目出发，逐步完成注册可选资料、资料编辑、公共读 OSS 头像上传、替换与恢复默认，并在每个单元结束时验收。

**架构：** 继续使用 controller → AuthService/AuthServiceImpl → UserMapper。OSS 客户端由 OssConfig 管理；数据库只保存 Object Key，接口通过已有 toVO 方法生成永久 URL。静态前端沿用现有 IIFE、API、modal 和 toast。

**技术基线：** Java 8、Spring Boot 2.3.0.RELEASE、javax.validation、MyBatis-Plus 3.4.3.4、MySQL 8、原生 HTML/CSS/JavaScript、OSS Java SDK 3.18.4。

**需求依据：** [用户资料与阿里云 OSS 头像上传开发指导书 v2.0](2026-09-17-user-profile-oss-development-guide.md)。本文是它的编码展开，不采用旧版私有读、签名地址或独立头像 Service 方案。

**核对日期：** 2026-09-19。后文行号来自这个日期的基线，修改后会漂移；操作时优先搜索“定位锚点”。文中相对路径均从项目根目录 C:/fly_develop/java_ai_project/pocket_ledger_java_fly 开始。

**编写阶段核验：** 已把本文代码片段应用到仓库外的临时副本，在 Java 8 / Maven 3.8.6 下编译并运行普通测试：48 项通过、0 失败、0 错误，2 项真实数据库测试按开关跳过。前端重建文件通过 Node 语法检查，并通过 DOM 模拟环境中的请求编码、控件完整性、注册恢复、头像重试和草稿保留核验。尚未执行真实数据库迁移、真实 OSS 调用或真实浏览器视觉验收；这些仍须按单元 10 实施。实际仓库业务代码未因编写本文而修改。

## 阅读与执行方式

每个单元按“文件清单 → 定位 → 修改 → 测试 → 验收”执行。一次只完成一个单元，测试通过再继续。代码块标注“完整文件”时可录入整个文件；标注“新增方法/片段”时只放到指定类或函数内，不重复包名、类声明或大括号。import 放在 package 下方，字段放在类的字段区，方法放在类结束大括号之前。

本指导书本身只新增一个 Markdown 文件，不修改业务代码。真正实施会涉及多个文件，开始实施前仍须按 AGENTS.md 确认中文方案。保留工作区已有修改，尤其不要用本文覆盖已发生变化的同名方法。

全程不查阅、不修改 frontend_vue，不执行其安装或构建。不得把真实 AccessKey、数据库密码写入示例、Git 或浏览器。公共读、原始字节上传以及元数据公开的取舍沿用原手册；不新增图片处理、清理任务表或后台重试。

## 单元导航与文件职责

| 单元 | 可验收结果 | 本单元主要文件 |
| --- | --- | --- |
| 0 | 基线和运行环境明确 | 只读检查 |
| 1 | 用户表可保存新字段 | 增量 SQL、初始化 SQL |
| 2 | 注册、查询、修改和清空文本资料 | User、RegisterDTO、UserProfileUpdateDTO、UserVO、AuthService、AuthServiceImpl、AuthController、测试 |
| 3 | OSS 客户端配置和生命周期明确 | pom.xml、application.yml、OssConfig、上下文测试 |
| 4 | 头像上传和删除可通过服务测试 | AuthService、AuthServiceImpl、ResultCode、服务测试 |
| 5 | HTTP 上传契约与异常正确 | AuthController、GlobalExceptionHandler、Web 测试 |
| 6 | API 同时支持 JSON 和 FormData | fronted_static/js/api.js |
| 7 | 页面控件与样式完整 | fronted_static/index.html、css/styles.css |
| 8 | 资料编辑、预览及会话保护完整 | fronted_static/js/app.js |
| 9 | 注册分阶段提交、恢复与重试完整 | fronted_static/js/app.js |
| 10 | 数据库、认证、OSS 和浏览器联调通过 | 接口文档、集成测试与验收脚本 |

生产 Java 只新增两个文件：dto/UserProfileUpdateDTO.java、config/OssConfig.java。测试文件和 SQL 不计入此数量。

### 已有实现与复用依据

- AuthServiceImpl.java：register（原 53 行）、getCurrentUser（104 行）、toVO（121 行）。所有资料和头像行为都扩展这里，不再建立转发 Service。
- AuthController.java：现有三个端点只依赖 AuthService，新端点继续沿用。
- dto/BillWriteDTO.java：setDescription 和 @JsonAnySetter 已体现“setter 规范化、拒绝未知字段”的项目风格；资料 DTO 沿用该模式。
- UserMapper 继承 BaseMapper，不新增 Mapper/XML。局部更新使用 LambdaUpdateWrapper，显式 set(null) 实现清空。
- ResultCode、BusinessException、GlobalExceptionHandler、ApiResponse 继续统一处理状态与响应。
- api.js 的 request、app.js 的 showError/hideError/toast、modal-overlay/btn/input 样式可直接复用。资料表单不能调用账单专用 clearErrors 或 setSubmitting。
- 已在维护范围内按资料字段、OSS、MultipartFile、构造器与更新模式检索；排除了 frontend_vue、构建产物。没有发现可直接复用的头像实现。新增 DTO 是因为注册包含必填用户名密码，不能用于只允许昵称邮箱的 PUT；新增 OssConfig 是为了管理 SDK Bean 生命周期。

## 单元 0：先固定基线，避免边写边修环境

- [ ] 阅读根 AGENTS.md；如存在适用的 settings.json，先检查 deny。命中禁令立即停止，不能绕过；修改该文件必须先获许可。
- [ ] 在根目录执行下列只读检查，记录已有修改。

~~~powershell
# 在项目根目录执行；不要清除或覆盖已有的工作区修改。
git status --short
git branch --show-current
git diff --stat
~~~

- [ ] 在 backend 目录设置当前 PowerShell 会话的 JDK 8，并确认 Wrapper 的 Maven 版本。

~~~powershell
# 本机已知路径；先验证存在，再临时设置，不修改系统环境变量。
$projectJdk8 = 'C:\fly_develop\Java\temurin-jdk8u504-b01'
if (-not (Test-Path -LiteralPath "$projectJdk8\bin\java.exe")) {
    throw '没有找到 JDK 8，请先将 projectJdk8 改为本机实际 JDK 8 目录'
}
$env:JAVA_HOME = $projectJdk8
.\mvnw.cmd -version
.\mvnw.cmd '-Dtest=AuthControllerWebTest,AuthInterceptorTest' test
~~~

**通过标准：** Java 为 1.8，Maven 为 3.8.6，认证基线测试通过。若失败先记录原有失败，不能将后续失败混为本次功能造成。不要执行真实数据库初始化脚本来“修测试”。

## 单元 1：先让数据库具备存储能力

### 1.1 新增增量迁移文件

**新增：** backend/docs/migrations/2026-09-17-user-profile-oss.sql。目录不存在时创建目录。完整内容：

~~~sql
-- 仅用于已有数据库的增量升级，执行前备份并检查三列均不存在。
-- 本迁移只执行一次；MySQL DDL 会隐式提交，不能靠 ROLLBACK 撤销。
ALTER TABLE fly_user
    ADD COLUMN nickname VARCHAR(50) NULL DEFAULT NULL COMMENT '展示昵称，允许重复',
    ADD COLUMN avatar_object_key VARCHAR(255) NULL DEFAULT NULL COMMENT 'OSS头像对象标识',
    ADD COLUMN email VARCHAR(254) NULL DEFAULT NULL COMMENT '联系邮箱，仅校验格式';
~~~

### 1.2 修改新环境初始化结构

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/sql/pocket_ledger_init.sql。

**定位：** CREATE TABLE fly_user 内的 password_hash 列（原 23 行）。在它后面、created_at 前面新增下列三列，保留逗号。其他表和种子数据不改，不删除旧列。

~~~sql
    -- 可选资料不设置唯一索引，也不写入伪造的默认昵称或头像地址。
    `nickname`          VARCHAR(50)  NULL DEFAULT NULL COMMENT '展示昵称，允许重复',
    `avatar_object_key` VARCHAR(255) NULL DEFAULT NULL COMMENT 'OSS头像对象标识',
    `email`             VARCHAR(254) NULL DEFAULT NULL COMMENT '联系邮箱，仅校验格式',
~~~

原手册的 sql/pocket_ledger_init.sql 是简写，以上才是当前仓库实际路径。已有库只执行 1.1 的 ALTER，不执行包含 DROP TABLE 的整个初始化文件。

### 1.3 单元验收 SQL

在已备份的专用测试库同一会话执行。先运行“执行前”，人工确认目标库及三列不存在，再执行迁移，再运行“执行后”。如已存在部分列，停止并核对历史迁移，不能重复 ALTER。

~~~sql
-- 执行前：记录目标库、表结构及数据数量。
SELECT DATABASE() AS target_database;
SHOW CREATE TABLE fly_user;
SHOW INDEX FROM fly_user;
SELECT COUNT(*) INTO @users_before FROM fly_user;
SELECT COUNT(*) INTO @bills_before FROM fly_bill;

-- 在此处单独执行 1.1 已核对的迁移文件。

-- 执行后：行数不得减少，新增列应均可空，旧用户三个值均为 NULL。
SELECT COUNT(*) = @users_before AS user_count_unchanged FROM fly_user;
SELECT COUNT(*) = @bills_before AS bill_count_unchanged FROM fly_bill;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fly_user'
  AND COLUMN_NAME IN ('nickname', 'avatar_object_key', 'email');
SELECT COUNT(*) AS unexpected_non_null_rows
FROM fly_user
WHERE nickname IS NOT NULL OR avatar_object_key IS NOT NULL OR email IS NOT NULL;
SHOW INDEX FROM fly_user;
~~~

**通过标准：** 两个 unchanged 均为 1；返回三列、长度分别 50/255/254、可空；刚升级的旧数据 unexpected_non_null_rows 为 0；用户名唯一索引仍在，昵称和邮箱没有唯一索引。留存备份、执行时间及查询结果。

## 单元 2：先完成文本资料，不接 OSS 网络

本单元提供 nickname/email 的注册、查询和 PUT。头像 URL 拼接先做成纯字符串逻辑；无头像时不需要 OSS 服务。

### 2.1 给实体新增三个字段

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/entity/User.java。

**定位：** passwordHash 后、createdAt 前。只新增以下字段，保留其余内容。

~~~java
    /** 展示昵称；空值由前端回退为用户名。 */
    private String nickname;

    /** OSS 对象标识；不保存完整 URL，不接收客户端直接赋值。 */
    private String avatarObjectKey;

    /** 联系邮箱；只校验格式，允许重复。 */
    private String email;
~~~

### 2.2 完整替换 RegisterDTO

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/dto/RegisterDTO.java。该文件较小，完整替换如下；没有改变现有密码 trim 与 BCrypt 72 字节限制。

~~~java
package com.fly.pocket_ledger_java.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

/** 注册只提交文本；头像在登录后使用 multipart 单独上传。 */
@Data
public class RegisterDTO {
    @NotBlank(message = "不能为空")
    @Size(min = 2, max = 50, message = "长度需在 2~50 个字符之间")
    private String username;

    @NotBlank(message = "不能为空")
    @Size(min = 8, max = 64, message = "长度需在 8~64 个字符之间")
    private String password;

    /** 昵称可省略或清空，不使用 @NotBlank。 */
    @Size(max = 50, message = "长度不能超过 50")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "长度不能超过 254")
    private String email;

    /** 避免派生校验属性参与 JSON，同时保留原有密码字节限制。 */
    @JsonIgnore
    @AssertTrue(message = "密码 UTF-8 编码后不能超过 72 字节")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    public void setUsername(String username) {
        this.username = username == null ? null : username.trim();
    }

    public void setPassword(String password) {
        this.password = password == null ? null : password.trim();
    }

    public void setNickname(String nickname) {
        this.nickname = normalizeOptional(nickname);
    }

    public void setEmail(String email) {
        this.email = normalizeOptional(email);
    }

    /** 只规范化可选字段，不改变邮箱大小写。 */
    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** 沿用 BillWriteDTO 的规则，拒绝客户端指定头像或目标用户。 */
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("不支持的字段：" + name);
    }
}
~~~

### 2.3 新增 UserProfileUpdateDTO

**新增完整文件：** backend/src/main/java/com/fly/pocket_ledger_java/dto/UserProfileUpdateDTO.java。

~~~java
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
~~~

### 2.4 完整替换 UserVO

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/vo/UserVO.java。

~~~java
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

    /** JSON 使用契约规定的下划线字段名，空值也必须保留。 */
    @JsonProperty("avatar_url")
    private String avatarUrl;

    private String email;
}
~~~

原两参数构造器不再存在。当前仅 AuthServiceImpl 和 AuthControllerWebTest 使用过它：前者在下一步改 setter；后者两处 new UserVO(1L, "reed") 改成下面这一表达式（不要新增另一个兼容构造器）。

~~~java
// 参数顺序：ID、用户名、昵称、头像地址、邮箱；旧用户的可选项全部为空。
new UserVO(1L, "reed", null, null, null)
~~~

### 2.5 扩展 Service 接口和实现

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/service/AuthService.java。新增 import 和接口方法；已有三个方法保留。

~~~java
// 放在 import 区。
import com.fly.pocket_ledger_java.dto.UserProfileUpdateDTO;
~~~

~~~java
    /** 只更新当前用户的昵称、邮箱，不改变头像。 */
    UserVO updateCurrentUser(UserProfileUpdateDTO dto);
~~~

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/service/impl/AuthServiceImpl.java。

1. import 区新增：

~~~java
// 显式 set(null) 才能可靠清空字段；域名配置仅用于字符串拼接。
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fly.pocket_ledger_java.dto.UserProfileUpdateDTO;
import org.springframework.beans.factory.annotation.Value;
~~~

2. 在 jwtUtils 字段之后新增 publicBaseUrl。此时尚无头像，空默认值允许先独立完成文本单元；单元 3 配置真实域名，非空 Key 遇到缺配置会明确失败。

~~~java
    /** 浏览器可访问的公共域名，不能配置为 OSS 内网 Endpoint。 */
    @Value("${aliyun.oss.public-base-url:}")
    private String publicBaseUrl;
~~~

3. register 内紧接 user.setPasswordHash(...) 新增三行，并将“只返回 id、username”的旧注释改为“返回公开资料，不发 token”。

~~~java
        // 与账号在同一次 INSERT 中保存，不能先建号再补写资料。
        user.setNickname(dto.getNickname());
        user.setEmail(dto.getEmail());
        user.setAvatarObjectKey(null);
~~~

4. 将 getCurrentUser 整个方法替换为以下内容；在后面新增 updateCurrentUser 和 requireCurrentUser。

~~~java
    @Override
    @Transactional(readOnly = true)
    public UserVO getCurrentUser() {
        // 注册、查询、更新共用同一个响应转换入口。
        return toVO(requireCurrentUser());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateCurrentUser(UserProfileUpdateDTO dto) {
        User user = requireCurrentUser();
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
        update.eq(User::getId, user.getId())
                .set(User::getNickname, dto.getNickname())
                .set(User::getEmail, dto.getEmail());
        // 不能 updateById(旧实体)，否则会覆盖并发修改的头像；显式 null 表示清空。
        userMapper.update(null, update);
        // 原样保存可能影响 0 行，因此不直接把 0 当成失败；重新查询确认用户存在。
        return toVO(requireCurrentUser());
    }

    /** 所有写操作的用户 ID 都只能来自认证上下文。 */
    private User requireCurrentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        return user;
    }
~~~

5. 删除原 toVO 方法，替换为以下方法；旧注释“仅保留 ID 与用户名”同步删除。

~~~java
    /** 只做字段转换和地址拼接，不请求 OSS，不把默认昵称写回数据库。 */
    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        if (user.getAvatarObjectKey() != null) {
            if (publicBaseUrl == null || publicBaseUrl.trim().isEmpty()) {
                throw new IllegalStateException("尚未配置 OSS 公共访问域名");
            }
            String base = publicBaseUrl.trim().replaceAll("/+$", "");
            vo.setAvatarUrl(base + "/" + user.getAvatarObjectKey());
        }
        return vo;
    }
~~~

### 2.6 增加 PUT Controller

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/controller/AuthController.java。import 新增 UserProfileUpdateDTO 与 PutMapping，在 me 方法后新增 updateMe。注册方法旧注释改为“创建账号，返回公开资料，不发 token”。

~~~java
// 放在 import 区，@Valid 已存在，不要重复导入。
import com.fly.pocket_ledger_java.dto.UserProfileUpdateDTO;
import org.springframework.web.bind.annotation.PutMapping;
~~~

~~~java
    /** 只接收昵称和邮箱，身份由认证拦截器提供。 */
    @PutMapping("/me")
    public ApiResponse<UserVO> updateMe(@Valid @RequestBody UserProfileUpdateDTO dto) {
        return ApiResponse.success("资料更新成功", authService.updateCurrentUser(dto));
    }
~~~

### 2.7 单元验收：请求绑定与参数拒绝

**修改：** backend/src/test/java/com/fly/pocket_ledger_java/controller/AuthControllerWebTest.java。保留现有全部用例、MockBean 和 BeforeEach；先按 2.4 修改两处 UserVO 构造器，并把 meReturnsOnlyIdAndUsername 改名为 meReturnsPublicProfile。

在 import 区新增以下内容：

~~~java
// 捕获真实绑定结果，避免只验证预设的 mock 响应。
import com.fly.pocket_ledger_java.dto.RegisterDTO;
import com.fly.pocket_ledger_java.dto.UserProfileUpdateDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
~~~

在类末尾增加这些完整方法：

~~~java
    @Test
    void registerBindsOptionalProfile() throws Exception {
        when(authService.register(any())).thenReturn(
                new UserVO(1L, "reed", "小飞", null, "Reed@example.com"));
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reed\",\"password\":\"12345678\","
                                + "\"nickname\":\" 小飞 \",\"email\":\" Reed@example.com \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("小飞"));
        ArgumentCaptor<RegisterDTO> captor = ArgumentCaptor.forClass(RegisterDTO.class);
        verify(authService).register(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("小飞");
        assertThat(captor.getValue().getEmail()).isEqualTo("Reed@example.com");
    }

    @Test
    void emptyProfileClearsBothFields() throws Exception {
        when(authService.updateCurrentUser(any())).thenReturn(
                new UserVO(1L, "reed", null, null, null));
        String response = mockMvc.perform(put("/auth/me")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ArgumentCaptor<UserProfileUpdateDTO> captor =
                ArgumentCaptor.forClass(UserProfileUpdateDTO.class);
        verify(authService).updateCurrentUser(captor.capture());
        assertThat(captor.getValue().getNickname()).isNull();
        assertThat(captor.getValue().getEmail()).isNull();
        // has + isNull 能区分“字段缺失”和“字段存在且为 null”。
        com.fasterxml.jackson.databind.JsonNode data = new ObjectMapper().readTree(response).get("data");
        assertThat(data.has("avatar_url")).isTrue();
        assertThat(data.get("avatar_url").isNull()).isTrue();
        assertThat(data.has("password_hash")).isFalse();
        assertThat(data.has("avatar_object_key")).isFalse();
    }

    @Test
    void invalidEmailNeverReachesService() throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reed\",\"password\":\"12345678\",\"email\":\"bad\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/auth/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad\"}"))
                .andExpect(status().isUnprocessableEntity());
        verify(authService, never()).register(any());
        verify(authService, never()).updateCurrentUser(any());
    }

    @Test
    void forbiddenFieldsAreRejected() throws Exception {
        // 包括任意未知字段；不能只禁止某一个 user_id 写法。
        for (String field : new String[]{"id", "user_id", "avatar_url", "avatar_object_key", "other"}) {
            mockMvc.perform(put("/auth/me").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"" + field + "\":\"x\"}"))
                    .andExpect(status().isUnprocessableEntity());
            mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"reed\",\"password\":\"12345678\",\"" + field + "\":\"x\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }
        verify(authService, never()).register(any());
        verify(authService, never()).updateCurrentUser(any());
    }
~~~

### 2.8 单元验收：Service 的真实插入参数

**新增完整测试文件：** backend/src/test/java/com/fly/pocket_ledger_java/service/AuthProfileServiceTest.java。

~~~java
package com.fly.pocket_ledger_java.service;

import com.fly.pocket_ledger_java.dto.RegisterDTO;
import com.fly.pocket_ledger_java.entity.User;
import com.fly.pocket_ledger_java.mapper.UserMapper;
import com.fly.pocket_ledger_java.service.impl.AuthServiceImpl;
import com.fly.pocket_ledger_java.util.JwtUtils;
import com.fly.pocket_ledger_java.util.LoginUser;
import com.fly.pocket_ledger_java.util.UserContext;
import com.fly.pocket_ledger_java.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 普通单测不启动 Spring、不连接 MySQL，也不调用 OSS。 */
class AuthProfileServiceTest {
    private final UserMapper mapper = mock(UserMapper.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(mapper, encoder, mock(JwtUtils.class));
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://avatar.example.com/");
    }

    @AfterEach
    void clearContext() {
        // ThreadLocal 不清理会污染同线程中的后续测试。
        UserContext.clear();
    }

    @Test
    void registerInsertsOptionalProfileTogether() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(encoder.encode("12345678")).thenReturn("encoded-password");
        when(mapper.insert(any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(9L);
            return 1;
        });
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("reed");
        dto.setPassword("12345678");
        dto.setNickname(" 小飞 ");
        dto.setEmail(" Reed@example.com ");
        UserVO result = service.register(dto);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("小飞");
        assertThat(captor.getValue().getEmail()).isEqualTo("Reed@example.com");
        assertThat(captor.getValue().getAvatarObjectKey()).isNull();
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("encoded-password");
        assertThat(result.getNickname()).isEqualTo("小飞");
        assertThat(result.getAvatarUrl()).isNull();
        // 只按用户名查重，不对昵称、邮箱进行额外唯一性查询。
        verify(mapper, times(1)).selectCount(any());
    }

    @Test
    void meBuildsPermanentUrlWithoutNetwork() {
        UserContext.set(new LoginUser(9L, "reed"));
        User user = new User();
        user.setId(9L);
        user.setUsername("reed");
        user.setAvatarObjectKey("avatars/9/test.png");
        when(mapper.selectById(9L)).thenReturn(user);
        assertThat(service.getCurrentUser().getAvatarUrl())
                .isEqualTo("https://avatar.example.com/avatars/9/test.png");
    }
}
~~~

运行命令（backend 目录）：

~~~powershell
# 本单元只验证 MVC 绑定和 Service 编排，数据库落库另在单元 10 验证。
.\mvnw.cmd '-Dtest=AuthControllerWebTest,AuthProfileServiceTest,AuthInterceptorTest' test
~~~

**通过标准：** 旧注册/登录测试继续通过；非法参数不调用 Service；实际 insert 参数含规范化的资料；null 字段仍序列化。出现“构造器参数数量不符”表示遗漏了 2.4 的两处测试调用，按文件定位修正，不给生产类补无用构造器。

### 2.9 补齐 DTO 长度、空白和密码字节边界

**新增完整测试文件：** backend/src/test/java/com/fly/pocket_ledger_java/dto/UserProfileValidationTest.java。它独立于 Spring，验证注册和 PUT 的规则保持一致。

~~~java
package com.fly.pocket_ledger_java.dto;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证两个入口的边界，不把“数据库能存下”当成“业务允许”。 */
class UserProfileValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    private RegisterDTO registration() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("reed");
        dto.setPassword("12345678");
        return dto;
    }

    private String repeat(String value, int count) {
        // Java 8 不支持 String.repeat，测试数据使用标准集合拼接。
        return String.join("", Collections.nCopies(count, value));
    }

    @Test
    void optionalFieldsCanBeMissingBlankOrPresentIndependently() {
        RegisterDTO register = registration();
        UserProfileUpdateDTO update = new UserProfileUpdateDTO();
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
        register.setNickname("  "); register.setEmail("\t ");
        update.setNickname("  "); update.setEmail("\t ");
        assertThat(register.getNickname()).isNull();
        assertThat(register.getEmail()).isNull();
        assertThat(update.getNickname()).isNull();
        assertThat(update.getEmail()).isNull();
        register.setNickname("只有昵称"); update.setNickname("只有昵称");
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
        register.setNickname(null); update.setNickname(null);
        register.setEmail("Only@example.com"); update.setEmail("Only@example.com");
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
    }

    @Test
    void nicknameBoundaryIsTheSameForBothEntrances() {
        RegisterDTO register = registration();
        UserProfileUpdateDTO update = new UserProfileUpdateDTO();
        register.setNickname(repeat("飞", 50)); update.setNickname(repeat("飞", 50));
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
        register.setNickname(repeat("飞", 51)); update.setNickname(repeat("飞", 51));
        assertThat(VALIDATOR.validate(register)).isNotEmpty();
        assertThat(VALIDATOR.validate(update)).isNotEmpty();
    }

    @Test
    void emailLengthAndFormatAreBothChecked() {
        RegisterDTO register = registration();
        UserProfileUpdateDTO update = new UserProfileUpdateDTO();
        // 64 字符本地部分 + @ + 189 字符域名，合计 254。
        String email = repeat("a", 64) + "@" + repeat("b", 63) + "." + repeat("c", 63) + "." + repeat("d", 61);
        register.setEmail(email); update.setEmail(email);
        assertThat(VALIDATOR.validate(register)).isEmpty();
        assertThat(VALIDATOR.validate(update)).isEmpty();
        register.setEmail(email + "d"); update.setEmail(email + "d");
        assertThat(VALIDATOR.validate(register)).isNotEmpty();
        assertThat(VALIDATOR.validate(update)).isNotEmpty();
        register.setEmail("invalid"); update.setEmail("invalid");
        assertThat(VALIDATOR.validate(register)).isNotEmpty();
        assertThat(VALIDATOR.validate(update)).isNotEmpty();
    }

    @Test
    void originalBcryptByteLimitStillApplies() {
        RegisterDTO dto = registration();
        dto.setPassword(repeat("密", 24)); // UTF-8 恰好 72 字节。
        assertThat(VALIDATOR.validate(dto)).isEmpty();
        dto.setPassword(repeat("密", 25)); // 字符数没超 64，但字节数超限。
        assertThat(VALIDATOR.validate(dto)).isNotEmpty();
    }
}
~~~

~~~powershell
# 在 backend 执行；完成本单元前，两个入口必须同时通过边界测试。
.\mvnw.cmd '-Dtest=UserProfileValidationTest,AuthControllerWebTest,AuthProfileServiceTest' test
~~~

**通过标准：** 四项测试全部通过。用户名和密码旧约束仍由原认证 Web 测试覆盖；昵称邮箱的缺失、空白、长度和格式规则在注册与修改入口一致。

## 单元 3：接入 OSS 客户端，但先不写上传接口

### 3.1 添加唯一一个 SDK 依赖

**修改：** backend/pom.xml。定位 spring-boot-starter-test 依赖前的注释，在该依赖之前插入：

~~~xml
<!-- 头像仅保存原始字节，不添加图片解码、缩放或 EXIF 处理依赖。 -->
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.18.4</version>
</dependency>
~~~

### 3.2 合并 application.yml

**修改：** backend/src/main/resources/application.yml。在现有 spring 下、application 同级位置加入 servlet，不新建第二个 spring。文件末尾新增 aliyun 根节点。其余数据库、JWT、白名单保持原样。

~~~yaml
  # 插入现有 spring 内，缩进为两个空格。
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 3MB
~~~

~~~yaml
# 文件末尾新增根节点；真实值只从后端进程的环境读取。
aliyun:
  oss:
    endpoint: ${OSS_ENDPOINT}
    region: ${OSS_REGION}
    bucket: ${OSS_BUCKET}
    public-base-url: ${OSS_PUBLIC_BASE_URL}
    avatar-prefix: avatars/
~~~

运行环境必须提供 OSS_ENDPOINT、OSS_REGION、OSS_BUCKET、OSS_PUBLIC_BASE_URL、OSS_ACCESS_KEY_ID、OSS_ACCESS_KEY_SECRET。通过 IDE 的本地运行环境或部署平台注入，勿提交明文配置。Endpoint 为 SDK HTTPS 域名；public-base-url 为浏览器公共 HTTPS 域名，只填域名/可选路径前缀，不带查询参数。Bucket 公共读、写私有，RAM 只允许该 Bucket 的 avatars/* 上 PutObject 和 DeleteObject。不得把服务密钥授予浏览器。

### 3.3 新建 OssConfig 完整文件

**新增：** backend/src/main/java/com/fly/pocket_ledger_java/config/OssConfig.java。

~~~java
package com.fly.pocket_ledger_java.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.comm.SignVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;

/** 管理一个共享 OSS 客户端，不在每次上传时新建连接池。 */
@Configuration
public class OssConfig {
    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(
            @Value("${aliyun.oss.endpoint}") String endpoint,
            @Value("${aliyun.oss.region}") String region,
            @Value("${aliyun.oss.bucket}") String bucket,
            @Value("${aliyun.oss.public-base-url}") String publicBaseUrl,
            @Value("${aliyun.oss.avatar-prefix}") String prefix) throws Exception {
        // 启动时发现配置错误，避免第一次上传才出现难定位的问题。
        requireHttps(endpoint);
        requireHttps(publicBaseUrl);
        if (region.trim().isEmpty() || bucket.trim().isEmpty()
                || !"avatars/".equals(prefix)) {
            throw new IllegalArgumentException("请配置 OSS 地域、Bucket，并保留 avatars/ 前缀");
        }
        ClientBuilderConfiguration config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setConnectionTimeout(2000);
        config.setSocketTimeout(4000);
        config.setConnectionRequestTimeout(1000);
        config.setMaxConnections(20);
        // 单次外部调用设置超时，不自动重发整个业务操作。
        config.setRequestTimeoutEnabled(true);
        config.setRequestTimeout(8000);
        config.setMaxErrorRetry(0);
        return OSSClientBuilder.create()
                .endpoint(endpoint)
                .region(region)
                .credentialsProvider(CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider())
                .clientConfiguration(config)
                .build();
    }

    /** URL 配置不接受凭证、查询串或片段；头像 URL 稍后直接拼接。 */
    private void requireHttps(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("OSS 地址必须为不带凭证和查询参数的 HTTPS 地址");
        }
    }
}
~~~

此配置采用原手册允许的环境变量凭证方案。V4 的 builder 和 Region 写法依据 [OSS 官方 SDK 文档](https://www.alibabacloud.com/help/zh/oss/developer-reference/oss-java-sdk/)；连接与请求超时方法核对自 [3.18.4 ClientConfiguration 源码](https://github.com/aliyun/aliyun-oss-java-sdk/blob/3.18.4/src/main/java/com/aliyun/oss/ClientConfiguration.java)。8000ms 是 SDK 调用预算，不是整个 HTTP 请求的硬性完成承诺；上传和旧图清理会先后调用 SDK，前端仍需处理结果不确定。

### 3.4 修正普通上下文测试，避免要求云密钥

**修改：** backend/src/test/java/com/fly/pocket_ledger_java/PocketLedgerJavaApplicationTests.java，完整替换为：

~~~java
package com.fly.pocket_ledger_java;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/** 上下文测试用 mock 替换 SDK Bean，不创建云端资源。 */
@SpringBootTest(properties = {
        "aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
        "aliyun.oss.region=cn-hangzhou",
        "aliyun.oss.bucket=test-bucket",
        "aliyun.oss.public-base-url=https://avatar.example.com"
})
class PocketLedgerJavaApplicationTests {
    @MockBean
    private OSS ossClient;

    @Test
    void contextLoads() {
        // Bean 能装配即通过；不会调用 OSS。
    }
}
~~~

**修改：** backend/src/test/java/com/fly/pocket_ledger_java/DatabaseConnectionIntegrationTest.java。已有数据源测试无需 OSS；把原 @SpringBootTest 替换为下面的注解，新增随后列出的 import 与类字段；保留原环境开关和测试方法。它原先硬编码数据库名 pocket_ledger_yihai，本单元不擅自改该断言。单元 10 的新测试只运行指定类，不借用该旧测试去连接其他库。

~~~java
// 替换原来的无参数 @SpringBootTest；下方环境开关注解保持不动。
@SpringBootTest(properties = {
        "aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
        "aliyun.oss.region=cn-hangzhou",
        "aliyun.oss.bucket=test-bucket",
        "aliyun.oss.public-base-url=https://avatar.example.com"
})
~~~

~~~java
// import 区新增。
import com.aliyun.oss.OSS;
import org.springframework.boot.test.mock.mockito.MockBean;
~~~

~~~java
    /** 数据库连通性验证不应依赖 OSS 凭证或网络。 */
    @MockBean
    private OSS ossClient;
~~~

### 3.5 单元验收命令

~~~powershell
# 在 backend 执行；先确认 SDK 坐标可解析及 Java 8 编译兼容。
.\mvnw.cmd '-DskipTests' compile
.\mvnw.cmd '-Dtest=PocketLedgerJavaApplicationTests,AuthControllerWebTest,AuthProfileServiceTest' test
.\mvnw.cmd dependency:tree '-Dincludes=com.aliyun.oss:aliyun-sdk-oss'
~~~

**通过标准：** SDK 只有固定的 3.18.4 版本；编译及指定测试通过。上下文测试没有因为 OSS 密钥缺失而失败。真实凭证下的启动和上传留到单元 10，不能用 mock 通过证明权限已正确配置。

## 单元 4：在现有 Service 内实现头像编排

### 4.1 新增业务错误码

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/common/ResultCode.java。在 INTERNAL_ERROR 之前插入以下枚举项，注意最后都用逗号：

~~~java
    /** 文件格式、数量或内容不符合头像规则。 */
    AVATAR_INVALID(422, "头像仅支持 JPG、PNG 格式"),
    /** 最大 2 MiB，HTTP 状态与响应 code 一致。 */
    AVATAR_TOO_LARGE(413, "头像不能超过 2 MB"),
    /** 必须成功的 OSS 上传暂不可用；删除旧图失败不使用此响应。 */
    AVATAR_STORAGE_UNAVAILABLE(503, "头像存储暂不可用，请稍后重试"),
~~~

### 4.2 扩展 AuthService

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/service/AuthService.java。

~~~java
// import 区新增。
import org.springframework.web.multipart.MultipartFile;
~~~

~~~java
    /** 上传新图、提交头像关联、尽力清理旧图。 */
    void replaceAvatar(MultipartFile file);

    /** 清空当前用户头像，重复调用仍成功。 */
    void removeAvatar();
~~~

### 4.3 修改 AuthServiceImpl 依赖

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/service/impl/AuthServiceImpl.java。import 区新增：

~~~java
// SDK 异常仅在上传/删除边界处理，不把任意数据库异常包装成 503。
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.model.ObjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
~~~

字段区新增：

~~~java
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private final OSS ossClient;

    /** Bucket 与前缀只来自服务端配置。 */
    @Value("${aliyun.oss.bucket}")
    private String bucket;

    @Value("${aliyun.oss.avatar-prefix:avatars/}")
    private String avatarPrefix;
~~~

把原三参数构造器完整替换为四参数构造器，并修改它上面的注释，补充 OSS 客户端参数：

~~~java
    /** 注入现有认证组件及共享 OSS 客户端，禁止在请求中创建客户端。 */
    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder,
                           JwtUtils jwtUtils, OSS ossClient) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.ossClient = ossClient;
    }
~~~

**同步修改：** AuthProfileServiceTest 的 setUp 中构造器调用，用完全限定名避免遗漏 import：

~~~java
// 普通资料测试也注入 mock，仍然不会访问 OSS。
service = new AuthServiceImpl(mapper, encoder, mock(JwtUtils.class), mock(com.aliyun.oss.OSS.class));
~~~

### 4.4 新增两个业务方法

在 AuthServiceImpl 的 updateCurrentUser 后面、私有辅助方法之前新增；这两个方法不添加 @Transactional，也不要给整个类添加事务注解。

~~~java
    @Override
    public void replaceAvatar(MultipartFile file) {
        User user = requireCurrentUser();
        byte[] content = readAvatar(file);
        String extension = avatarExtension(content);
        String operationId = UUID.randomUUID().toString();
        String oldKey = user.getAvatarObjectKey();
        String newKey = avatarPrefix + user.getId() + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;

        // 先上传成功再写数据库；原始字节不会被解码或重编码。
        putAvatarObject(user.getId(), operationId, newKey, content, extension);
        try {
            LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
            update.eq(User::getId, user.getId()).set(User::getAvatarObjectKey, newKey);
            if (userMapper.update(null, update) == 0) {
                // newKey 永远全新；影响 0 行通常代表账号已被删除。
                throw new BusinessException(ResultCode.UNAUTHORIZED);
            }
        } catch (RuntimeException exception) {
            // 数据库异常也可能是提交结果不确定，不能立即删除新图或盲目恢复旧 Key。
            LOGGER.error("AVATAR_LINK_FAILED operationId={} userId={} key={} stage=update",
                    operationId, user.getId(), newKey, exception);
            throw exception;
        }
        deleteOldAvatar(user.getId(), oldKey, operationId);
    }

    @Override
    public void removeAvatar() {
        User user = requireCurrentUser();
        String oldKey = user.getAvatarObjectKey();
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
        update.eq(User::getId, user.getId()).set(User::getAvatarObjectKey, null);
        int affected = userMapper.update(null, update);
        // 原来就是 NULL 时 0 行属于正常幂等结果，但已删除的用户仍应返回 401。
        if (affected == 0) requireCurrentUser();
        deleteOldAvatar(user.getId(), oldKey, UUID.randomUUID().toString());
    }
~~~

并发行为沿用设计：最后提交的一次头像更新生效，可能留下孤儿对象。每次都产生全新 Key、不允许旧 Key 重新绑定，是清理旧对象安全性的前提。数据库网络异常不能一概断言“必定没有提交”；本文进一步保留该不确定性，记录新 Key 后由人工核对，前端重新 GET 确认。

### 4.5 新增四个私有方法

仍放在 AuthServiceImpl 内，不新建存储类、图片处理类、Utils 或 Exception。

~~~java
    /** 文件大小在 Servlet 和业务两层检查，单元测试也能验证业务边界。 */
    private byte[] readAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(422, "请选择非空头像文件");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ResultCode.AVATAR_TOO_LARGE);
        }
        try {
            byte[] content = file.getBytes();
            if (content.length == 0) throw new BusinessException(422, "请选择非空头像文件");
            if (content.length > MAX_AVATAR_BYTES) {
                throw new BusinessException(ResultCode.AVATAR_TOO_LARGE);
            }
            return content;
        } catch (IOException exception) {
            // 文件尚未上传，读取失败时保留当前关联。
            LOGGER.warn("AVATAR_READ_FAILED userId={}", UserContext.getUserId(), exception);
            throw new BusinessException(422, "读取头像失败，请重新选择文件");
        }
    }

    /** 按已确认的最小文件头规则识别类型，不相信扩展名或请求 Content-Type。 */
    private String avatarExtension(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (content.length >= 4 && (content[0] & 0xff) == 0x89
                && content[1] == 0x50 && content[2] == 0x4e && content[3] == 0x47) {
            return "png";
        }
        throw new BusinessException(ResultCode.AVATAR_INVALID);
    }

    /** 上传为私有写、公共读 Bucket 中的新对象，地址不含签名。 */
    private void putAvatarObject(Long userId, String operationId, String key,
                                 byte[] content, String extension) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType("jpg".equals(extension) ? "image/jpeg" : "image/png");
        metadata.setCacheControl("public, max-age=31536000");
        try {
            // 内存输入流没有外部句柄；SDK 同步消费内容后方法才返回。
            ossClient.putObject(bucket, key, new ByteArrayInputStream(content), metadata);
        } catch (OSSException | ClientException exception) {
            String requestId = exception instanceof OSSException
                    ? ((OSSException) exception).getRequestId() : "-";
            // 响应丢失时对象可能已存在，保留生成的 Key 供人工排查。
            LOGGER.error("AVATAR_UPLOAD_FAILED operationId={} userId={} key={} requestId={} stage=upload",
                    operationId, userId, key, requestId, exception);
            throw new BusinessException(ResultCode.AVATAR_STORAGE_UNAVAILABLE);
        }
    }

    /** 清理失败不推翻已经提交的关联更新；不建立重试队列。 */
    private void deleteOldAvatar(Long userId, String oldKey, String operationId) {
        if (oldKey == null) return;
        try {
            String expected = Pattern.quote(avatarPrefix + userId + "/")
                    + "[0-9a-f]{32}\\.(jpg|png)";
            if (!oldKey.matches(expected)) {
                throw new IllegalStateException("旧头像 Key 不属于当前用户允许的路径");
            }
            // 再核对所有用户的引用；异常时保守保留，交给维护人员确认。
            Long references = userMapper.selectCount(
                    Wrappers.lambdaQuery(User.class).eq(User::getAvatarObjectKey, oldKey));
            if (references != null && references > 0) return;
            ossClient.deleteObject(bucket, oldKey);
        } catch (RuntimeException exception) {
            String requestId = exception instanceof OSSException
                    ? ((OSSException) exception).getRequestId() : "-";
            LOGGER.warn("AVATAR_DELETE_FAILED operationId={} userId={} key={} requestId={} stage=delete",
                    operationId, userId, oldKey, requestId, exception);
        }
    }
~~~

这里魔数只检查 JPEG 3 字节、PNG 4 字节，与原设计一致。仅有正确文件头的损坏图片也可能通过；后端不解码，不能声称已验证“完整可显示图片”。前端预览会额外尝试加载图片，显示失败回退默认头像。

### 4.6 单元验收完整测试文件

**新增：** backend/src/test/java/com/fly/pocket_ledger_java/service/AuthAvatarServiceTest.java。

~~~java
package com.fly.pocket_ledger_java.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.model.ObjectMetadata;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fly.pocket_ledger_java.entity.User;
import com.fly.pocket_ledger_java.exception.BusinessException;
import com.fly.pocket_ledger_java.mapper.UserMapper;
import com.fly.pocket_ledger_java.service.impl.AuthServiceImpl;
import com.fly.pocket_ledger_java.util.JwtUtils;
import com.fly.pocket_ledger_java.util.LoginUser;
import com.fly.pocket_ledger_java.util.UserContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.io.InputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过公共业务方法验收私有校验逻辑，不用反射调用私有方法。 */
class AuthAvatarServiceTest {
    private final UserMapper mapper = mock(UserMapper.class);
    private final OSS oss = mock(OSS.class);
    private AuthServiceImpl service;
    private final String oldKey = "avatars/9/00000000000000000000000000000000.png";

    @BeforeEach
    void setUp() {
        // 纯 Mockito 不会启动 MyBatis，需要初始化 Lambda 列名元数据。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), User.class);
        service = new AuthServiceImpl(mapper, mock(PasswordEncoder.class), mock(JwtUtils.class), oss);
        ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        ReflectionTestUtils.setField(service, "avatarPrefix", "avatars/");
        UserContext.set(new LoginUser(9L, "reed"));
        User user = new User();
        user.setId(9L);
        user.setAvatarObjectKey(oldKey);
        when(mapper.selectById(9L)).thenReturn(user);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectCount(any())).thenReturn(0L);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private MockMultipartFile png(int size) {
        // 这是魔数边界样本，不用于浏览器显示或证明完整 PNG 可解码。
        byte[] bytes = new byte[size];
        if (size >= 4) {
            bytes[0] = (byte) 0x89; bytes[1] = 0x50; bytes[2] = 0x4e; bytes[3] = 0x47;
        }
        return new MockMultipartFile("file", "untrusted.txt", "text/plain", bytes);
    }

    @Test
    void uploadThenUpdateThenDeleteOldObject() throws Exception {
        MockMultipartFile file = png(8);
        service.replaceAvatar(file);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InputStream> stream = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
        InOrder order = inOrder(oss, mapper);
        order.verify(oss).putObject(eq("test-bucket"), key.capture(), stream.capture(), metadata.capture());
        order.verify(mapper).update(isNull(), any());
        order.verify(oss).deleteObject("test-bucket", oldKey);
        assertThat(key.getValue()).matches("avatars/9/[0-9a-f]{32}\\.png");
        assertThat(metadata.getValue().getContentType()).isEqualTo("image/png");
        assertThat(metadata.getValue().getContentLength()).isEqualTo(8);
        assertThat(org.springframework.util.StreamUtils.copyToByteArray(stream.getValue())).isEqualTo(file.getBytes());
    }

    @Test
    void exactlyTwoMiBPassesButOneMoreByteFails() {
        service.replaceAvatar(png(2 * 1024 * 1024));
        clearInvocations(mapper, oss);
        assertThatThrownBy(() -> service.replaceAvatar(png(2 * 1024 * 1024 + 1)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(413));
        verifyNoInteractions(oss);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void renamedGifAndEmptyFileAreRejected() {
        MockMultipartFile fake = new MockMultipartFile("file", "fake.jpg", "image/jpeg", new byte[]{71, 73, 70, 56});
        assertThatThrownBy(() -> service.replaceAvatar(fake)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.replaceAvatar(png(0))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.replaceAvatar(null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(oss);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void jpegUsesDetectedMimeInsteadOfFileName() {
        service.replaceAvatar(new MockMultipartFile("file", "wrong.png", "image/png",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}));
        verify(oss).putObject(anyString(), endsWith(".jpg"), any(InputStream.class),
                argThat(metadata -> "image/jpeg".equals(metadata.getContentType())));
    }

    @Test
    void storageFailureDoesNotUpdateDatabase() {
        when(oss.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenThrow(new ClientException("simulated network failure"));
        assertThatThrownBy(() -> service.replaceAvatar(png(8)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        verify(mapper, never()).update(any(), any());
        verify(oss, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void databaseFailureDoesNotDeleteEitherObject() {
        when(mapper.update(isNull(), any())).thenThrow(new IllegalStateException("simulated database failure"));
        assertThatThrownBy(() -> service.replaceAvatar(png(8))).isInstanceOf(IllegalStateException.class);
        // 新 Key 会进入 AVATAR_LINK_FAILED 日志，由人工核对；不尝试网络补偿删除。
        verify(oss, never()).deleteObject(anyString(), anyString());
    }

    @Test
    void oldObjectDeletionFailureDoesNotFailReplacement() {
        doThrow(new ClientException("simulated delete failure")).when(oss).deleteObject("test-bucket", oldKey);
        assertThatCode(() -> service.replaceAvatar(png(8))).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void removalOnlySetsAvatarColumnToNull() {
        service.removeAvatar();
        ArgumentCaptor<LambdaUpdateWrapper<User>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("avatar_object_key").doesNotContain("nickname", "email");
        assertThat(captor.getValue().getParamNameValuePairs().values()).containsNull();
    }

    @Test
    void repeatedRemovalWithNoAvatarSucceeds() {
        User user = new User();
        user.setId(9L);
        when(mapper.selectById(9L)).thenReturn(user);
        when(mapper.update(isNull(), any())).thenReturn(0);
        service.removeAvatar();
        service.removeAvatar();
        verifyNoInteractions(oss);
    }

    @Test
    void networkMethodsDoNotStartTransactions() throws Exception {
        // 保护设计边界：禁止给整个类或两个网络编排方法加事务。
        assertThat(AuthServiceImpl.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(AuthServiceImpl.class.getMethod("replaceAvatar", org.springframework.web.multipart.MultipartFile.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(AuthServiceImpl.class.getMethod("removeAvatar").isAnnotationPresent(Transactional.class)).isFalse();
    }
}
~~~

~~~powershell
# 在 backend 执行；预期日志会出现模拟失败事件，但测试本身应全部通过。
.\mvnw.cmd '-Dtest=AuthAvatarServiceTest,AuthProfileServiceTest' test
~~~

**通过标准：** 原始字节及 MIME 正确；2 MiB 边界正确；上传失败不 UPDATE；UPDATE 失败不删任何对象；删除失败不向上抛；恢复默认只更新头像列；网络方法不在事务中。人工核对模拟日志含操作 ID、用户 ID、Key、阶段、Request ID 占位/实际值，不含密钥和邮箱。

## 单元 5：接通 HTTP 头像接口与框架异常

### 5.1 在 AuthController 增加 import 和端点

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/controller/AuthController.java。保留单一 AuthService 依赖，import 区加入：

~~~java
// 从 multipart 请求本身核验数量，不能只绑定一个 MultipartFile 就假定只有一个文件。
import com.fly.pocket_ledger_java.exception.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import java.util.List;
~~~

在 updateMe 后面新增以下完整方法。使用 MultipartHttpServletRequest 获取 file，因此缺失文件由这里主动返回 422，不依赖缺参默认异常。

~~~java
    /** 上传成功必须意味着对象上传和头像关联写入均已完成。 */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserVO> uploadAvatar(MultipartHttpServletRequest request) {
        MultipartFile file = requireSingleAvatar(request);
        authService.replaceAvatar(file);
        return ApiResponse.success("头像更新成功", authService.getCurrentUser());
    }

    @DeleteMapping("/me/avatar")
    public ApiResponse<UserVO> removeAvatar() {
        // 先完成变更再读取；变更失败不得继续生成成功响应。
        authService.removeAvatar();
        return ApiResponse.success("已恢复默认头像", authService.getCurrentUser());
    }

    private MultipartFile requireSingleAvatar(MultipartHttpServletRequest request) {
        List<MultipartFile> files = request.getFiles("file");
        if (request.getMultiFileMap().size() != 1 || files.size() != 1
                || !request.getParameterMap().isEmpty()) {
            // 拒绝重名文件、额外文件字段和文本字段（包括 URL 查询参数）。
            throw new BusinessException(422, "只允许上传一个名为 file 的头像文件，不接受额外字段");
        }
        if (files.get(0).isEmpty()) throw new BusinessException(422, "请选择非空头像文件");
        return files.get(0);
    }
~~~

### 5.2 扩展 GlobalExceptionHandler

**修改：** backend/src/main/java/com/fly/pocket_ledger_java/exception/GlobalExceptionHandler.java。保留所有现有处理器。

import 区新增：

~~~java
// 大小超限必须比普通 multipart 解析失败更具体，Spring 会选择最具体的处理器。
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
~~~

把 handleUnsupportedMediaType 中唯一的旧提示字符串替换为：

~~~java
// 修改此 return，其他注解及方法签名保留。
return ApiResponse.failure(ResultCode.PARAM_INVALID.getCode(),
        "参数错误：Content-Type 不受支持，请按接口使用 JSON 或 multipart/form-data");
~~~

在 handleUnexpectedException 之前新增：

~~~java
    /** 上传超过容器配置或文件大小限制时返回 413。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiResponse<Void> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        return ApiResponse.failure(ResultCode.AVATAR_TOO_LARGE);
    }

    /** multipart 缺失部分、边界错误等格式问题统一返回 422。 */
    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Void> handleInvalidMultipart(Exception exception) {
        return ApiResponse.failure(422, "参数错误：请使用合法的 multipart/form-data 上传头像");
    }
~~~

### 5.3 单元验收 Web 测试代码

**修改：** 已有 AuthControllerWebTest.java。新增 import：

~~~java
// 继续 mock AuthService，无需增加其他 Service mock。
import org.springframework.mock.web.MockMultipartFile;
import org.mockito.InOrder;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
~~~

类内新增：

~~~java
    @Test
    void avatarWritesBeforeReadingResponse() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        when(authService.getCurrentUser()).thenReturn(
                new UserVO(1L, "reed", "小飞", "https://avatar.example.com/a.png", null));
        mockMvc.perform(multipart("/auth/me/avatar").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatar_url").value("https://avatar.example.com/a.png"))
                .andExpect(jsonPath("$.data.avatarObjectKey").doesNotExist());
        InOrder order = inOrder(authService);
        order.verify(authService).replaceAvatar(any());
        order.verify(authService).getCurrentUser();
    }

    @Test
    void multipartRejectsMissingRepeatedAndExtraFields() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        mockMvc.perform(multipart("/auth/me/avatar")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(multipart("/auth/me/avatar").file(file).file(file))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(multipart("/auth/me/avatar").file(file)
                        .file(new MockMultipartFile("other", new byte[]{1})))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(multipart("/auth/me/avatar").file(file).param("user_id", "2"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/auth/me/avatar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        verify(authService, never()).replaceAvatar(any());
    }

    @Test
    void uploadFailureDoesNotReadSuccessResponse() throws Exception {
        doThrow(new BusinessException(ResultCode.AVATAR_STORAGE_UNAVAILABLE))
                .when(authService).replaceAvatar(any());
        mockMvc.perform(multipart("/auth/me/avatar")
                        .file(new MockMultipartFile("file", new byte[]{1})))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503));
        verify(authService, never()).getCurrentUser();
    }

    @Test
    void removalWritesBeforeReadingResponse() throws Exception {
        when(authService.getCurrentUser()).thenReturn(new UserVO(1L, "reed", null, null, null));
        mockMvc.perform(delete("/auth/me/avatar")).andExpect(status().isOk());
        InOrder order = inOrder(authService);
        order.verify(authService).removeAvatar();
        order.verify(authService).getCurrentUser();
    }
~~~

~~~powershell
# MVC 单测里的认证拦截器是 mock；真实认证和容器大小限制在单元 10 验收。
.\mvnw.cmd '-Dtest=AuthControllerWebTest,AuthAvatarServiceTest,AuthInterceptorTest' test
~~~

**通过标准：** 端点先写后读；重复文件/附加字段/JSON 请求返回 422；存储异常返回 503 且不查询成功响应。MockMvc multipart 不经过真实容器大小解析，所以不能据此声称 413 部署链路已验证。

## 单元 6：先改 API 层，让页面有稳定的调用入口

### 6.1 完整替换 request 函数

**修改：** fronted_static/js/api.js。定位 async function request（原 37 行），替换整个函数，保留后面的 buildQuery、账单、分类方法。

~~~javascript
  /** JSON 与文件共用认证、超时和统一响应处理。 */
  async function request(path, { method = "GET", body, timeout = 12000 } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    const token = getToken();
    const base = getBase();
    const headers = {};
    const isFormData = body instanceof FormData;
    if (body !== undefined && !isFormData) headers["Content-Type"] = "application/json";
    if (token) headers.Authorization = "Bearer " + token;
    let response;
    try {
      response = await fetch(base + path, {
        method,
        headers,
        // multipart 的 boundary 必须由浏览器产生，不能手动指定 Content-Type。
        body: body === undefined ? undefined : (isFormData ? body : JSON.stringify(body)),
        signal: controller.signal,
        cache: "no-store",
      });
      let payload = null;
      try { payload = await response.json(); } catch { /* 错误页可能不是 JSON。 */ }
      if (!response.ok || !payload || payload.code !== 200) {
        const err = new Error((payload && payload.message) || ("请求失败（HTTP " + response.status + "）"));
        err.httpStatus = response.status;
        // 5xx 或成功状态却无法解包时，写请求是否完成可能无法确认。
        err.uncertain = response.status >= 500 || (response.ok && !payload);
        if (response.status === 401 && !path.startsWith("/auth/login")) {
          if (getToken() === token && getBase() === base) {
            clearToken();
            err.auth = true;
          } else {
            err.stale = true; // 旧会话的 401 不能清掉新账号的令牌。
          }
        }
        throw err;
      }
      return payload.data;
    } catch (err) {
      if (err.httpStatus) throw err;
      const wrapped = new Error(err.name === "AbortError"
        ? "请求超时，操作结果暂无法确认"
        : "网络中断，操作结果暂无法确认，请检查后端连接");
      wrapped.uncertain = true;
      // 无 HTTP 响应不伪造状态码，更不自动重发注册或上传。
      throw wrapped;
    } finally {
      // 计时范围覆盖响应体读取，不能收到响应头就提前取消超时。
      clearTimeout(timer);
    }
  }
~~~

### 6.2 替换 register/login，新增三个方法

将原 register、login 两个函数完整替换为以下前两个函数，再在 me 后加入后面三个函数。logout、me 保留。

~~~javascript
  /** 第三个参数可省略，继续兼容旧的两参数注册调用。 */
  function register(username, password, profile = {}) {
    return request("/auth/register", {
      method: "POST",
      body: { username, password, nickname: profile.nickname ?? null, email: profile.email ?? null },
    });
  }

  async function login(username, password, isCurrent = () => true) {
    const data = await request("/auth/login", { method: "POST", body: { username, password } });
    // 页面提供会话代次检查，防止旧登录响应覆盖后来切换的账号。
    if (!isCurrent()) {
      const err = new Error("登录会话已变化，请重新操作");
      err.stale = true;
      throw err;
    }
    if (!data || !data.access_token) throw new Error("登录响应缺少访问令牌");
    setToken(data.access_token);
    return data;
  }

  /** 资料 PUT 始终只发两个字段，不能把完整 state.user 原样提交。 */
  function updateMe(profile) {
    return request("/auth/me", { method: "PUT", body: {
      nickname: profile.nickname, email: profile.email,
    } });
  }

  function uploadAvatar(file) {
    const form = new FormData();
    form.append("file", file);
    return request("/auth/me/avatar", { method: "POST", body: form, timeout: 30000 });
  }

  function removeAvatar() {
    return request("/auth/me/avatar", { method: "DELETE" });
  }
~~~

定位底部 return 对象中的 register, login, logout, me 一行，替换为：

~~~javascript
    // 新方法必须导出，app.js 才能调用。
    register, login, logout, me, updateMe, uploadAvatar, removeAvatar,
~~~

### 6.3 单元验收代码：浏览器控制台，无真实写请求

打开静态页面，控制台粘贴以下完整脚本。它暂时 mock fetch，并在 finally 恢复；测试期间不要点击页面按钮。控制台没有红色断言且最后显示“API 层验收通过”才算通过。

~~~javascript
(async () => {
  // 只验证请求编码，不向后端注册账号、写资料或上传对象。
  const originalFetch = window.fetch;
  const requests = [];
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  try {
    window.fetch = async (url, options) => {
      requests.push({ url, options });
      return new Response(JSON.stringify({ code: 200, message: "成功", data: { id: 1 } }), {
        status: 200, headers: { "Content-Type": "application/json" },
      });
    };
    await API.register("test-user", "12345678");
    await API.updateMe({ nickname: null, email: null, avatar_url: "must-not-send" });
    const file = new File([new Uint8Array([137, 80, 78, 71])], "test.png", { type: "image/png" });
    await API.uploadAvatar(file);
    assert(requests[0].options.headers["Content-Type"] === "application/json", "注册仍应发送 JSON");
    const profile = JSON.parse(requests[1].options.body);
    assert(Object.keys(profile).sort().join(",") === "email,nickname", "资料请求混入额外字段");
    assert(requests[2].options.body instanceof FormData, "头像没有使用 FormData");
    assert(!requests[2].options.headers["Content-Type"], "不能手动设置 multipart Content-Type");
    assert(requests[2].options.body.getAll("file").length === 1, "必须恰好一个 file");
    window.fetch = async () => new Response(JSON.stringify({ code: 422, message: "测试拒绝", data: null }), { status: 422 });
    let rejected = false;
    try { await API.updateMe({ nickname: null, email: null }); }
    catch (err) { rejected = err.httpStatus === 422 && !err.uncertain; }
    assert(rejected, "HTTP 错误必须保留实际状态码");
    window.fetch = async () => { throw new TypeError("模拟断网"); };
    let uncertain = false;
    try { await API.register("test-user", "12345678"); }
    catch (err) { uncertain = err.uncertain && !err.httpStatus; }
    assert(uncertain, "断网必须抛出结果不确定错误，不能伪造 HTTP 状态");
    console.log("API 层验收通过");
  } finally {
    window.fetch = originalFetch;
  }
})();
~~~

## 单元 7：先摆好页面控件，再写事件

### 7.1 导航新增身份区控件

**修改：** fronted_static/index.html。定位原来的 span#userName（原 39 行），在其前面添加 navAvatar，在其后面、logoutBtn 前添加两个按钮；保留原来的 userName 和退出图标。

~~~html
<!-- 插在原 userName 前；背景由 CSS 提供固定默认头像。 -->
<span id="navAvatar" class="avatar avatar-small" hidden>
  <img alt="当前头像" hidden>
</span>
~~~

~~~html
<!-- 插在原 userName 后、logoutBtn 前。 -->
<button type="button" class="btn btn-ghost btn-sm" id="profileBtn" hidden>个人资料</button>
<button type="button" class="btn btn-ghost btn-sm" id="appLoadRetry" hidden>重新加载</button>
~~~

### 7.2 注册表单新增可选资料

定位 input#authPassword 之后、p#authError 之前，新增以下整段。文件输入没有 name，避免传统表单意外提交；实际上传由 API.uploadAvatar 负责。

~~~html
<!-- 只在注册模式显示；已有 authForm 的 novalidate 由 JS 主动校验。 -->
<div id="registerOptionalFields" class="profile-fields" hidden>
  <label class="field-label" for="registerNickname">昵称（可选）</label>
  <input class="input" id="registerNickname" type="text" maxlength="50">
  <p class="popover-hint">未填写时显示用户名。</p>
  <label class="field-label" for="registerEmail">联系邮箱（可选）</label>
  <input class="input" id="registerEmail" type="email" maxlength="254">
  <p class="popover-hint">仅作联系资料，不用于登录或密码找回。</p>
  <input id="registerAvatarFile" type="file" accept="image/jpeg,image/png" hidden>
  <div class="profile-actions">
    <button class="btn btn-ghost btn-sm" type="button" id="registerAvatarChoose">选择头像（可选）</button>
    <button class="btn btn-ghost btn-sm" type="button" id="registerAvatarCancel" hidden>取消选择</button>
  </div>
  <img class="avatar-preview" id="registerAvatarPreview" alt="待提交的注册头像，居中裁切" hidden>
  <p class="popover-hint">JPG、PNG，最大 2 MB；原图上传，预览按圆形居中裁切。</p>
  <p class="field-error" id="registerAvatarError" hidden></p>
</div>
<!-- 账号已经创建后，后续操作只继续登录或上传，绝不重复注册。 -->
<div id="registerRecovery" class="profile-fields" hidden>
  <p id="registerStatus" role="status"></p>
  <span id="registerCurrentAvatar" class="avatar" hidden><img alt="服务器当前头像" hidden></span>
  <div class="profile-actions">
    <button class="btn btn-primary btn-sm" type="button" id="registerAvatarRetry">确认上传所选头像</button>
    <button class="btn btn-ghost btn-sm" type="button" id="registerAvatarSkip">暂时跳过</button>
    <button class="btn btn-ghost btn-sm" type="button" id="registerAbandon">放弃续传并返回登录</button>
  </div>
</div>
~~~

### 7.3 新增完整个人资料弹窗

定位“确认删除弹窗”注释，在该注释前面插入；不要放进 billForm 或 authForm 内。

~~~html
<!-- 独立资料弹窗：头像即时保存，文本资料按“保存资料”提交。 -->
<div class="modal-overlay" id="profileModal" hidden>
  <div class="modal glass" role="dialog" aria-modal="true" aria-labelledby="profileTitle">
    <div class="modal-head">
      <h3 id="profileTitle">个人资料</h3>
      <button type="button" class="btn btn-ghost btn-sm" id="profileClose">关闭</button>
    </div>
    <div class="profile-fields">
      <p>用户名：<strong id="profileUsername"></strong></p>
      <p>当前头像</p>
      <span class="avatar" id="profileAvatar"><img alt="当前已保存头像" hidden></span>
      <input id="avatarFile" type="file" accept="image/jpeg,image/png" hidden>
      <div class="profile-actions">
        <button type="button" class="btn btn-ghost btn-sm" id="avatarChoose">选择图片</button>
        <button type="button" class="btn btn-ghost btn-sm" id="avatarRemove">恢复默认</button>
      </div>
      <div id="avatarPending" hidden>
        <p>待上传预览（尚未保存）</p>
        <img class="avatar-preview" id="avatarPreview" alt="待上传头像，居中裁切" hidden>
        <div class="profile-actions">
          <button type="button" class="btn btn-primary btn-sm" id="avatarUpload">确认上传</button>
          <button type="button" class="btn btn-ghost btn-sm" id="avatarCancel">取消选择</button>
        </div>
      </div>
      <p class="popover-hint">支持 JPG、PNG，最大 2 MB。原图上传；确认上传后立即生效，关闭弹窗不会撤销。</p>
      <p class="field-error" id="avatarError" hidden></p>
      <form id="profileForm" novalidate>
        <label class="field-label" for="profileNickname">昵称（可选）</label>
        <input class="input" type="text" id="profileNickname" maxlength="50">
        <p class="popover-hint">未填写时显示用户名。</p>
        <label class="field-label" for="profileEmail">联系邮箱（可选）</label>
        <input class="input" type="email" id="profileEmail" maxlength="254">
        <p class="popover-hint">仅作联系资料，不用于登录或密码找回。</p>
        <p class="field-error" id="profileError" hidden></p>
        <div class="modal-foot">
          <button type="button" class="btn btn-ghost" id="profileCancel">关闭</button>
          <button type="submit" class="btn btn-primary" id="profileSubmit">
            <span class="btn-spinner" hidden></span><span class="btn-text">保存资料</span>
          </button>
        </div>
      </form>
    </div>
  </div>
</div>
~~~

### 7.4 样式追加到文件末尾

**修改：** fronted_static/css/styles.css。只追加以下内容，不改账单样式和主题变量。

~~~css
/* 默认头像为固定 SVG，不包含用户输入；真实图片加载成功后才覆盖背景。 */
.avatar {
  display: inline-flex;
  width: 88px;
  height: 88px;
  flex-shrink: 0;
  overflow: hidden;
  vertical-align: middle;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
  background: var(--input-bg) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 80 80'%3E%3Ccircle cx='40' cy='28' r='14' fill='%2398a1b8'/%3E%3Cpath d='M12 74a28 28 0 0 1 56 0' fill='%2398a1b8'/%3E%3C/svg%3E") center/cover no-repeat;
}
.avatar-small { width: 32px; height: 32px; }
.avatar img, .avatar-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  border-radius: 50%;
}
.avatar-preview { width: 96px; height: 96px; margin-top: 10px; }
.avatar[hidden], .avatar img[hidden], .avatar-preview[hidden] { display: none; }
.profile-fields { margin-top: 12px; }
.profile-fields .field-label { display: block; margin-top: 14px; }
.profile-fields .input { width: 100%; }
.profile-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0; }
#profileModal .modal, #authOverlay .modal { max-height: 90vh; overflow-y: auto; }
#userName { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
#profileModal[aria-busy="true"] { cursor: progress; }
/* 窄屏允许换行，长昵称不能挤走退出按钮。 */
@media (max-width: 640px) {
  .nav-actions { flex-wrap: wrap; justify-content: flex-end; }
  #userName { max-width: 76px; }
  .profile-actions .btn { flex: 1 1 auto; }
}
~~~

### 7.5 单元验收：DOM 完整性代码

保存后刷新页面，在控制台执行。此时新按钮还没有行为，点击无反应属正常，下一单元再接线。

~~~javascript
(() => {
  // 检查重复 ID 和关键控件，防止后面事件绑定到错误节点。
  const ids = [...document.querySelectorAll("[id]")].map(el => el.id);
  if (new Set(ids).size !== ids.length) throw new Error("页面存在重复 ID");
  const required = ["navAvatar", "profileBtn", "profileModal", "profileForm", "profileNickname",
    "profileEmail", "avatarFile", "avatarUpload", "avatarPending", "registerOptionalFields",
    "registerAvatarFile", "registerRecovery", "registerAvatarRetry", "registerAvatarSkip", "appLoadRetry"];
  for (const id of required) if (!document.getElementById(id)) throw new Error("缺少控件：" + id);
  if (document.querySelector("form form")) throw new Error("不能嵌套 form");
  console.log("页面结构验收通过");
})();
~~~

**通过标准：** 无重复 ID、无嵌套 form；桌面与 375px 宽度查看，弹窗可滚动、按钮可换行、隐藏默认状态正常。

## 单元 8：个人资料编辑与两处头像共用能力

本单元所有 JavaScript 都修改 fronted_static/js/app.js，且必须放在最外层 IIFE 内。不要另外新建前端模块或重复创建 API。

### 8.1 新增状态及 DOM 引用

定位 state 对象结尾（原 64 行），在它后面插入：

~~~javascript
  // 两处图片各自保存 File 和预览地址，不能相互覆盖。
  const profileState = {
    pendingFile: null, previewUrl: null, selecting: false, selectionId: 0,
    saving: false, uploading: false, removing: false, sessionGeneration: 0,
  };
  const registrationState = {
    phase: "idle", pendingFile: null, previewUrl: null, selecting: false, selectionId: 0,
    registeredUserId: null, registeredUsername: null, generation: 0,
  };
~~~

定位 DOM 引用区末尾 authApiBase/authApiSaveBtn 后面，插入：

~~~javascript
  // 沿用现有 $，所有 ID 与单元 7 的 HTML 对应。
  const navAvatar = $("navAvatar"), profileBtn = $("profileBtn"), profileModal = $("profileModal");
  const profileForm = $("profileForm"), profileNickname = $("profileNickname"), profileEmail = $("profileEmail");
  const profileSubmit = $("profileSubmit"), profileError = $("profileError"), avatarError = $("avatarError");
  const avatarFile = $("avatarFile"), avatarPreview = $("avatarPreview");
  const registerNickname = $("registerNickname"), registerEmail = $("registerEmail");
  const registerAvatarFile = $("registerAvatarFile"), registerAvatarPreview = $("registerAvatarPreview");
  const registerAvatarError = $("registerAvatarError");
~~~

### 8.2 新增共用校验、预览与资料逻辑

定位“登录 / 注册”大段注释（原 209 行），在它之前插入下列完整代码。它会调用文件中已有的 showError、hideError、toast；函数声明提升使其可以放在这些函数之前。

~~~javascript
  /** 与 Java String.trim 对齐，避免前后端对首尾空白的解释不同。 */
  function normalizeOptional(value) {
    const normalized = String(value ?? "").replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, "");
    return normalized === "" ? null : normalized;
  }

  function readProfile(nicknameInput, emailInput) {
    const nickname = normalizeOptional(nicknameInput.value);
    const email = normalizeOptional(emailInput.value);
    // novalidate 下必须主动 checkValidity，后端仍负责最终校验。
    emailInput.value = email || "";
    if (nickname && nickname.length > 50) throw new Error("昵称长度不能超过 50");
    if (email && (email.length > 254 || !emailInput.checkValidity())) throw new Error("邮箱格式或长度不正确");
    return { nickname, email };
  }

  function paintAvatar(container, url) {
    const img = container.querySelector("img");
    img.onload = null;
    img.onerror = null;
    img.hidden = true;
    img.removeAttribute("src");
    // 加载中和失败都展示固定背景，不反复查询或刷新永久 URL。
    if (!url) return;
    img.onload = () => { img.hidden = false; };
    img.onerror = () => { img.hidden = true; };
    img.src = url;
  }

  function renderIdentity() {
    const user = state.user;
    userName.hidden = !user;
    logoutBtn.hidden = !user;
    profileBtn.hidden = !user;
    navAvatar.hidden = !user;
    userName.textContent = user ? (normalizeOptional(user.nickname) || user.username) : "";
    paintAvatar(navAvatar, user && user.avatar_url);
    paintAvatar($("profileAvatar"), user && user.avatar_url);
    $("profileUsername").textContent = user ? user.username : "";
    // 这里绝不回填昵称/邮箱输入框，头像响应不能覆盖未保存草稿。
  }

  function clearSelectionImage(target, preview, input) {
    target.selectionId += 1;
    target.selecting = false;
    if (target.previewUrl) URL.revokeObjectURL(target.previewUrl);
    target.previewUrl = null;
    target.pendingFile = null;
    preview.removeAttribute("src");
    preview.hidden = true;
    input.value = "";
  }

  async function selectImage(file, target, preview, input, errorEl) {
    hideError(errorEl);
    clearSelectionImage(target, preview, input);
    if (!file) return;
    const selectionId = target.selectionId;
    target.selecting = true;
    let url = null;
    try {
      if (!file.size || file.size > 2 * 1024 * 1024) throw new Error("请选择 1 字节至 2 MB 的图片");
      // 部分设备不提供 MIME，此时允许常见后缀进入预览；服务端仍以魔数为准。
      const mimeOk = ["image/jpeg", "image/png"].includes(file.type);
      const nameOk = !file.type && /\.(jpe?g|png)$/i.test(file.name);
      if (!mimeOk && !nameOk) throw new Error("头像仅支持 JPG、PNG 格式");
      url = URL.createObjectURL(file);
      await new Promise((resolve, reject) => {
        const image = new Image();
        image.onload = resolve;
        image.onerror = () => reject(new Error("图片无法预览，请重新选择"));
        image.src = url;
      });
      if (selectionId !== target.selectionId) return;
      target.pendingFile = file;
      target.previewUrl = url;
      preview.src = url;
      preview.hidden = false;
      url = null; // 所有权交给 target，取消、关闭、成功时再释放。
    } catch (err) {
      if (selectionId === target.selectionId) showError(errorEl, err.message);
    } finally {
      if (url) URL.revokeObjectURL(url);
      if (selectionId === target.selectionId) target.selecting = false;
      input.value = ""; // 允许重新选择同一文件。
    }
  }

  function profileBusy() {
    return profileState.saving || profileState.uploading || profileState.removing || profileState.selecting;
  }

  function syncProfileControls() {
    const busy = profileBusy();
    profileModal.setAttribute("aria-busy", String(busy));
    profileModal.querySelectorAll("button, input").forEach(el => { el.disabled = busy; });
    $("avatarUpload").disabled = busy || !profileState.pendingFile;
    $("avatarPending").hidden = !profileState.pendingFile;
    profileSubmit.querySelector(".btn-spinner").hidden = !profileState.saving;
    logoutBtn.disabled = busy;
    apiSaveBtn.disabled = busy;
  }

  function syncScrollLock() {
    document.body.style.overflow = (!authOverlay.hidden || !billModal.hidden
      || !confirmModal.hidden || !profileModal.hidden) ? "hidden" : "";
  }

  function openProfile() {
    if (!state.user || !billModal.hidden || !confirmModal.hidden) return;
    profileNickname.value = state.user.nickname || "";
    profileEmail.value = state.user.email || "";
    hideError(profileError);
    hideError(avatarError);
    profileModal.hidden = false;
    renderIdentity();
    syncProfileControls();
    syncScrollLock();
    profileNickname.focus();
  }

  function closeProfile() {
    if (profileBusy()) return;
    clearSelectionImage(profileState, avatarPreview, avatarFile);
    profileModal.hidden = true;
    syncScrollLock();
    if (state.user) profileBtn.focus();
  }

  function resetRegistration() {
    registrationState.generation += 1;
    clearSelectionImage(registrationState, registerAvatarPreview, registerAvatarFile);
    registrationState.phase = "idle";
    registrationState.registeredUserId = null;
    registrationState.registeredUsername = null;
    $("registerRecovery").hidden = true;
    $("registerAvatarCancel").hidden = true;
    $("registerCurrentAvatar").hidden = true;
    registerNickname.value = "";
    registerEmail.value = "";
    hideError(registerAvatarError);
  }

  function resetFeatureSession() {
    // 使旧异步响应失效，同时释放浏览器持有的 File/blob URL。
    profileState.sessionGeneration += 1;
    clearSelectionImage(profileState, avatarPreview, avatarFile);
    profileState.saving = profileState.uploading = profileState.removing = false;
    profileModal.hidden = true;
    resetRegistration();
    state.user = null;
    renderIdentity();
    syncProfileControls();
  }

  async function writeProfile(kind) {
    if (profileBusy() || !state.user) return;
    const errorEl = kind === "saving" ? profileError : avatarError;
    hideError(errorEl);
    let profile;
    try {
      if (kind === "saving") profile = readProfile(profileNickname, profileEmail);
      if (kind === "uploading" && !profileState.pendingFile) return;
    } catch (err) { showError(errorEl, err.message); return; }
    const generation = profileState.sessionGeneration;
    const token = API.getToken();
    const base = API.getBase();
    const userId = state.user.id;
    const isCurrent = () => generation === profileState.sessionGeneration
      && token === API.getToken() && base === API.getBase();
    profileState[kind] = true;
    syncProfileControls();
    try {
      const result = kind === "saving" ? await API.updateMe(profile)
        : kind === "uploading" ? await API.uploadAvatar(profileState.pendingFile) : await API.removeAvatar();
      if (!isCurrent()) return;
      if (result.id !== userId) throw new Error("当前用户不一致，请重新登录");
      state.user = result;
      renderIdentity();
      if (kind !== "saving") clearSelectionImage(profileState, avatarPreview, avatarFile);
      toast(kind === "saving" ? "资料已保存" : kind === "uploading" ? "头像已更新" : "已恢复默认头像");
    } catch (err) {
      // request 在同会话 401 时已清令牌，故先检查代次再处理 auth，不能先用 token 拦掉它。
      if (generation !== profileState.sessionGeneration || err.stale) return;
      if (handleAuthError(err)) return;
      if (!isCurrent()) return;
      let message = err.message;
      if (err.uncertain) {
        try {
          const actual = await API.me();
          if (!isCurrent()) return;
          if (actual.id !== userId) throw new Error("当前用户不一致");
          state.user = actual;
          renderIdentity();
          message = "结果曾无法确认，已刷新当前已保存资料；请核对后决定是否再次提交。";
        } catch (checkError) {
          if (generation !== profileState.sessionGeneration || checkError.stale) return;
          if (handleAuthError(checkError)) return;
          message = "结果暂无法确认，查询当前资料也失败；请恢复连接后刷新页面，再打开资料核对。";
        }
      }
      if (isCurrent()) showError(errorEl, message);
      // 失败时保留图片和文字草稿；不自动重发写请求。
    } finally {
      if (generation === profileState.sessionGeneration) {
        profileState[kind] = false;
        syncProfileControls();
      }
    }
  }

  function bindProfileEvents() {
    profileBtn.addEventListener("click", openProfile);
    $("profileClose").addEventListener("click", closeProfile);
    $("profileCancel").addEventListener("click", closeProfile);
    profileModal.addEventListener("mousedown", e => { if (e.target === profileModal) closeProfile(); });
    $("avatarChoose").addEventListener("click", () => { if (!profileBusy()) avatarFile.click(); });
    avatarFile.addEventListener("change", async () => {
      if (profileBusy()) return;
      const selecting = selectImage(avatarFile.files[0], profileState, avatarPreview, avatarFile, avatarError);
      syncProfileControls();
      await selecting;
      syncProfileControls();
    });
    $("avatarCancel").addEventListener("click", () => {
      if (profileBusy()) return;
      clearSelectionImage(profileState, avatarPreview, avatarFile);
      syncProfileControls();
    });
    $("avatarUpload").addEventListener("click", () => writeProfile("uploading"));
    $("avatarRemove").addEventListener("click", () => writeProfile("removing"));
    profileForm.addEventListener("submit", e => { e.preventDefault(); writeProfile("saving"); });
    $("appLoadRetry").addEventListener("click", () => {
      enterApp().catch(err => { if (!handleAuthError(err)) toast("加载失败，可重试：" + err.message, "error"); });
    });
    // 另一个标签页切换账号或 API 地址时，旧上传不能回写本页面。
    window.addEventListener("storage", e => {
      if (["pl_token", "pl_api_base"].includes(e.key) || e.key === null) showLogin();
    });
    window.addEventListener("pagehide", () => {
      clearSelectionImage(profileState, avatarPreview, avatarFile);
      clearSelectionImage(registrationState, registerAvatarPreview, registerAvatarFile);
    });
  }
~~~

### 8.3 替换 showLogin、hideLogin、enterApp

删除原三个完整函数，逐一替换如下。setAuthMode 和 setAuthSubmitting 暂时保留，下一单元替换。

~~~javascript
  function showLogin() {
    resetFeatureSession();
    authPassword.value = "";
    setAuthMode("login");
    authOverlay.hidden = false;
    authApiBase.value = API.getBase();
    syncScrollLock();
    setTimeout(() => authUsername.focus(), 90);
  }

  function hideLogin() {
    authOverlay.hidden = true;
    syncScrollLock();
  }

  /** 入场失败只重试加载，不回到注册、登录或头像上传步骤。 */
  async function enterApp(knownUser = null) {
    const generation = profileState.sessionGeneration;
    const token = API.getToken();
    const user = knownUser || await API.me();
    if (generation !== profileState.sessionGeneration || token !== API.getToken()) return;
    state.user = user;
    renderIdentity();
    hideLogin();
    try {
      await loadCategories();
      if (generation !== profileState.sessionGeneration || token !== API.getToken()) return;
      populateCategoryFilter();
      setDateMode("single", { reload: false });
      $("appLoadRetry").hidden = true;
      refresh();
    } catch (err) {
      if (generation !== profileState.sessionGeneration) return;
      if (handleAuthError(err)) return;
      $("appLoadRetry").hidden = false;
      toast("账号已登录，页面数据加载失败，可点击重新加载", "error");
    }
  }
~~~

### 8.4 接入事件、关闭和快捷键

1. 在 bindEvents 的第一行加入以下调用，只调用一次。

~~~javascript
    // 所有资料事件在页面启动时集中绑定，打开弹窗不重复绑定。
    bindProfileEvents();
~~~

2. 原 closeModal 和 closeConfirm 内，各将 document.body.style.overflow = "" 替换为 syncScrollLock()。保留各自其他逻辑。

~~~javascript
    // 关闭一层弹窗时仍需照顾其他可见弹窗。
    syncScrollLock();
~~~

3. 定位原快捷键监听（原 1233 行）。在 if (!authOverlay.hidden) return 后、原 Escape 分支前加入以下分支；资料弹窗开启时阻止 N 键打开账单，并约束 Tab 焦点。

~~~javascript
      // 资料弹窗的键盘操作在其内部处理，忙碌时 Escape 也不会撤销已发请求。
      if (!profileModal.hidden) {
        if (e.key === "Escape") { e.preventDefault(); closeProfile(); }
        if (e.key === "Tab") {
          const nodes = [...profileModal.querySelectorAll("button:not(:disabled), input:not(:disabled):not([type=file])")]
            .filter(el => el.getClientRects().length);
          const first = nodes[0], last = nodes[nodes.length - 1];
          if (!first) { e.preventDefault(); return; }
          if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
          if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        }
        return;
      }
~~~

4. 原 logoutBtn click 处理内，在 API.logout() 前加入 resetFeatureSession()。原 location.reload() 保留。

~~~javascript
      // 先使旧响应失效，再清令牌、刷新页面。
      resetFeatureSession();
~~~

### 8.5 单元验收代码：只用于专用测试账号

正常登录测试账号后执行以下控制台脚本；它会真实保存并清空测试账号昵称邮箱，最后恢复原文本资料。不要拿常用账号做验收。

~~~javascript
(async () => {
  const assert = (ok, message) => { if (!ok) throw new Error(message); };
  const old = await API.me();
  try {
    const updated = await API.updateMe({ nickname: " 手敲验收 ", email: " Test@example.com " });
    assert(updated.nickname === "手敲验收" && updated.email === "Test@example.com", "文本规范化错误");
    const cleared = await API.updateMe({ nickname: "   ", email: "" });
    assert(cleared.nickname === null && cleared.email === null, "空白没有转成 null");
    assert(cleared.avatar_url === old.avatar_url, "文本保存误改了头像");
    console.log("资料 API 验收通过，继续执行下面的页面交互验收");
  } finally {
    // 只恢复文本，绝不把整个用户响应发回 PUT。
    await API.updateMe({ nickname: old.nickname, email: old.email });
  }
})();
~~~

再按以下顺序操作页面并记录结果：

1. 打开资料，输入未保存昵称；选图后 Network 不得出现 POST；点击上传，导航与“当前头像”更新，昵称草稿不变。
2. 取消选择后重复选择同一文件，预览应出现；成功、取消、关闭后没有持续增长的 blob URL 引用。
3. 保存文本不隐式上传待选图片；关闭后再次打开按已保存字段回填，空昵称输入框保持空。
4. 恢复默认只清头像；连续点击只发一个写请求；忙碌时关闭、Esc、遮罩不能关闭。
5. 在 Network 离线模式下上传，恢复网络后核对已保存头像，失败不自动重发；输入草稿和待选文件保留。
6. 开启资料弹窗按 Tab/Shift+Tab、Esc，焦点和滚动正常；375px 宽度控件可用。

**通过标准：** 上述行为全部符合预期，控制台无未捕获异常。此单元结束时旧注册提交事件尚在，下一单元立即替换它，最终交付不能保留提前提示“已自动登录”的行为。

## 单元 9：替换注册流程，确保失败后不重复建号

仍只修改 fronted_static/js/app.js。按本单元顺序录入，完成后再刷新页面；不能新旧 submit 监听同时存在。

### 9.1 替换两个认证界面函数

定位 let authMode = "login"，保留它，并在紧接下一行新增 let authSubmitting = false。删除原 setAuthMode 和 setAuthSubmitting，替换为以下完整函数，同时加入 syncRegistrationControls。

~~~javascript
  // 与 phase 分开：一个控制网络/提交互斥，一个记录业务完成阶段。
  let authSubmitting = false;

  function setAuthMode(mode, { preserve = false } = {}) {
    if (!preserve) resetRegistration();
    authMode = mode;
    const isLogin = mode === "login";
    authTitle.textContent = isLogin ? "登录口袋账本" : "注册新账号";
    authToggleBtn.textContent = isLogin ? "没有账号？注册一个" : "已有账号？去登录";
    authPassword.autocomplete = isLogin ? "current-password" : "new-password";
    hideError(authError);
    syncRegistrationControls();
  }

  function setAuthSubmitting(on) {
    authSubmitting = on;
    syncRegistrationControls();
  }

  function syncRegistrationControls() {
    const phase = registrationState.phase;
    const busy = authSubmitting || registrationState.selecting;
    const avatarStage = ["avatar_failed", "uploading_avatar"].includes(phase);
    const recovering = ["login_required", "registration_unknown"].includes(phase) || avatarStage;
    const lockedAccount = registrationState.registeredUserId !== null;
    $("registerOptionalFields").hidden = authMode !== "register" && !avatarStage;
    $("registerRecovery").hidden = !recovering;
    authUsername.disabled = busy || avatarStage;
    authUsername.readOnly = lockedAccount || phase === "registration_unknown";
    authPassword.disabled = busy || avatarStage;
    registerNickname.disabled = busy || lockedAccount;
    registerEmail.disabled = busy || lockedAccount;
    $("registerAvatarChoose").disabled = busy;
    $("registerAvatarCancel").disabled = busy;
    $("registerAvatarCancel").hidden = !registrationState.pendingFile && registerAvatarError.hidden;
    registerAvatarFile.disabled = busy;
    authSubmit.hidden = avatarStage;
    authSubmit.disabled = busy;
    authSubmit.querySelector(".btn-spinner").hidden = !authSubmitting;
    authSubmit.querySelector(".btn-text").textContent = busy ? "请稍候…"
      : authMode === "register" ? "注册并登录" : "登录";
    authToggleBtn.disabled = busy || phase !== "idle";
    // 在已创建账号的恢复过程中固定后端，换后端需要明确放弃本次续传。
    authApiBase.disabled = authApiSaveBtn.disabled = busy || phase !== "idle";
    $("registerAvatarRetry").hidden = !avatarStage;
    $("registerAvatarSkip").hidden = !avatarStage;
    $("registerAvatarRetry").disabled = busy || !registrationState.pendingFile;
    $("registerAvatarSkip").disabled = busy;
    $("registerAbandon").disabled = busy;
    logoutBtn.disabled = busy || profileBusy();
    apiSaveBtn.disabled = busy || profileBusy();
  }
~~~

注意 authSubmitting 只声明一次：上面的代码块已经包含声明，不要把说明中的声明再重复录入。

### 9.2 新增阶段恢复与头像续传函数

放在 syncRegistrationControls 后、enterApp 前。

~~~javascript
  /** 显示服务器当前头像，仅更新已保存展示，不覆盖待上传预览。 */
  function showRegisteredAvatar(user) {
    state.user = user;
    $("registerCurrentAvatar").hidden = false;
    paintAvatar($("registerCurrentAvatar"), user.avatar_url);
  }

  async function finishRegistration(user, message) {
    registrationState.phase = "complete";
    clearSelectionImage(registrationState, registerAvatarPreview, registerAvatarFile);
    authPassword.value = "";
    await enterApp(user);
    // enterApp 的数据加载失败已经单独提示，不再退回注册。
    if (state.user && state.user.id === user.id && authOverlay.hidden) toast(message);
  }

  async function uploadRegisteredAvatar() {
    if (!registrationState.pendingFile || !registrationState.registeredUserId) return;
    const file = registrationState.pendingFile;
    const userId = registrationState.registeredUserId;
    const generation = registrationState.generation;
    const session = profileState.sessionGeneration;
    const token = API.getToken(), base = API.getBase();
    const alive = () => generation === registrationState.generation && session === profileState.sessionGeneration;
    const current = () => alive() && token === API.getToken() && base === API.getBase();
    setAuthSubmitting(true);
    registrationState.phase = "uploading_avatar";
    syncRegistrationControls();
    try {
      // 每次重试之前确认登录者仍然是刚注册的用户，不能只比对用户名。
      const actual = await API.me();
      if (!current()) return;
      if (actual.id !== userId) {
        showLogin();
        showError(authError, "当前账号与刚注册的账号不同，已取消头像续传");
        return;
      }
      showRegisteredAvatar(actual);
      const uploaded = await API.uploadAvatar(file);
      if (!current()) return;
      if (uploaded.id !== userId) throw new Error("头像响应用户不一致，请重新登录");
      await finishRegistration(uploaded, "注册成功，头像已设置");
    } catch (err) {
      if (!alive() || err.stale) return;
      if (handleAuthError(err)) return;
      if (!current()) return;
      registrationState.phase = "avatar_failed";
      let message = "账号已注册并登录，头像上传未完成：" + err.message;
      // 超时可能已经提交，因此先展示服务器当前状态，再允许用户决定是否替换。
      if (err.uncertain) {
        try {
          const actual = await API.me();
          if (!current()) return;
          if (actual.id !== userId) { showLogin(); return; }
          showRegisteredAvatar(actual);
          message = "账号已注册并登录。已查询服务器当前头像，请核对；可以确认再次上传，也可以跳过。";
        } catch (checkError) {
          if (!alive() || checkError.stale) return;
          if (handleAuthError(checkError)) return;
          message = "账号已创建，头像结果与当前登录状态暂无法确认；恢复连接后点击重试，将先核对账号。";
        }
      }
      if (current()) $("registerStatus").textContent = message;
    } finally {
      if (alive()) setAuthSubmitting(false);
    }
  }

  async function skipRegisteredAvatar() {
    if (authSubmitting || registrationState.selecting) return;
    const generation = registrationState.generation;
    const session = profileState.sessionGeneration;
    const token = API.getToken(), base = API.getBase();
    setAuthSubmitting(true);
    try {
      const user = await API.me();
      if (generation !== registrationState.generation || session !== profileState.sessionGeneration
          || token !== API.getToken() || base !== API.getBase()) return;
      if (user.id !== registrationState.registeredUserId) {
        showLogin();
        showError(authError, "当前账号已变化，已取消头像续传");
        return;
      }
      // 跳过是用户明确选择，此时释放待上传图片；以后可以重新选择。
      await finishRegistration(user, "注册成功，可稍后设置头像");
    } catch (err) {
      if (generation !== registrationState.generation || err.stale) return;
      if (!handleAuthError(err)) $("registerStatus").textContent = "暂时无法确认当前账号：" + err.message;
    } finally {
      if (generation === registrationState.generation) setAuthSubmitting(false);
    }
  }
~~~

### 9.3 新增唯一的认证提交函数

紧接上一组函数之后新增 submitAuth。局部密码只用于本次注册及自动登录；不存入 registrationState、localStorage 或 sessionStorage。

~~~javascript
  async function submitAuth(event) {
    event.preventDefault();
    if (authSubmitting || registrationState.selecting
        || ["avatar_failed", "uploading_avatar", "complete"].includes(registrationState.phase)) return;
    const username = authUsername.value.trim();
    // 保持后端既有密码首尾空白处理，并用 UTF-8 字节数检查 BCrypt 上限。
    let password = authPassword.value.trim();
    const creating = authMode === "register";
    let profile;
    try {
      if (!username || !password) throw new Error("请输入用户名和密码");
      if (creating) {
        if (registrationState.phase !== "idle") return;
        // 图片校验失败后必须重新选图或明确取消，不能悄悄当成“未选头像”建号。
        if (!registerAvatarError.hidden) throw new Error("请重新选择合法头像，或点击取消选择后再注册");
        if (username.length < 2 || username.length > 50) throw new Error("用户名长度需在 2~50 个字符之间");
        if (password.length < 8 || password.length > 64 || new TextEncoder().encode(password).length > 72) {
          throw new Error("密码需 8~64 个字符，且 UTF-8 编码不超过 72 字节");
        }
        profile = readProfile(registerNickname, registerEmail);
      }
    } catch (err) { showError(authError, err.message); password = ""; return; }

    const generation = registrationState.generation;
    const session = profileState.sessionGeneration;
    const base = API.getBase();
    let expectedToken = API.getToken();
    const alive = () => generation === registrationState.generation && session === profileState.sessionGeneration
      && base === API.getBase();
    const current = () => alive() && expectedToken === API.getToken();
    setAuthSubmitting(true);
    hideError(authError);
    try {
      if (creating) {
        registrationState.phase = "registering";
        syncRegistrationControls();
        try {
          const created = await API.register(username, password, profile);
          if (!current()) return;
          if (!created || !created.id) throw new Error("注册响应不完整，结果暂无法确认");
          registrationState.registeredUserId = created.id;
          registrationState.registeredUsername = created.username;
          registrationState.phase = "registered";
        } catch (err) {
          if (!alive() || err.stale) return;
          if (err.auth) { handleAuthError(err); return; }
          if (!current()) return;
          if ([400, 409, 422].includes(err.httpStatus)) {
            // 明确拒绝才允许修改资料后重新提交注册。
            registrationState.phase = "idle";
            showError(authError, err.message);
          } else {
            // 没有可靠用户 ID 时绝不自动续传到随后登录的账号。
            clearSelectionImage(registrationState, registerAvatarPreview, registerAvatarFile);
            registrationState.phase = "registration_unknown";
            registrationState.registeredUsername = username;
            setAuthMode("login", { preserve: true });
            $("registerStatus").textContent = "注册结果暂无法确认，请重新输入密码登录确认；头像需登录后重新选择。";
          }
          return;
        }
      }

      if (registrationState.registeredUserId) registrationState.phase = "logging_in";
      await API.login(username, password, current);
      if (!alive()) return;
      expectedToken = API.getToken();
      password = "";
      authPassword.value = "";
      const actual = await API.me();
      if (!current()) return;
      if (registrationState.registeredUserId && actual.id !== registrationState.registeredUserId) {
        showLogin();
        showError(authError, "登录账号与刚注册的账号不同，已取消头像续传");
        return;
      }
      if (registrationState.pendingFile && registrationState.registeredUserId) {
        if (creating) {
          // 首次注册交互自动上传，失败后的手动登录必须由用户确认续传。
          await uploadRegisteredAvatar();
        } else {
          registrationState.phase = "avatar_failed";
          showRegisteredAvatar(actual);
          $("registerStatus").textContent = "账号已登录，请确认上传之前选择的头像，或暂时跳过。";
        }
      } else {
        await finishRegistration(actual, creating ? "注册成功，已登录" : "登录成功");
      }
    } catch (err) {
      if (!alive() || err.stale) return;
      if (handleAuthError(err)) return;
      if (!current()) return;
      if (registrationState.registeredUserId) {
        registrationState.phase = "login_required";
        setAuthMode("login", { preserve: true });
        $("registerStatus").textContent = "账号已注册成功，请重新输入密码登录后继续设置头像。";
      }
      showError(authError, err.message);
    } finally {
      password = "";
      if (alive()) {
        authPassword.value = "";
        setAuthSubmitting(false);
      }
    }
  }
~~~

### 9.4 替换旧事件，补齐图片选择和恢复按钮

在 bindEvents 中执行这些精确修改：

1. **删除**原 authToggleBtn.addEventListener("click", ...) 整段（原 868～870 行）。
2. **删除**原 authForm.addEventListener("submit", async ...) 整段（原 882～902 行），包括过早的“注册成功，已自动登录”toast。
3. 在原“登录 / 注册”注释后加入以下代码。其他账单监听不动。

~~~javascript
    // 认证提交只绑定一次，旧 async submit 监听必须删除。
    authForm.addEventListener("submit", submitAuth);
    authToggleBtn.addEventListener("click", () => {
      if (authSubmitting || registrationState.selecting || registrationState.phase !== "idle") return;
      setAuthMode(authMode === "login" ? "register" : "login");
    });
    $("registerAvatarChoose").addEventListener("click", () => {
      if (!authSubmitting && !registrationState.selecting) registerAvatarFile.click();
    });
    registerAvatarFile.addEventListener("change", async () => {
      if (authSubmitting || registrationState.selecting) return;
      const selecting = selectImage(registerAvatarFile.files[0], registrationState,
        registerAvatarPreview, registerAvatarFile, registerAvatarError);
      syncRegistrationControls();
      await selecting;
      syncRegistrationControls();
    });
    $("registerAvatarCancel").addEventListener("click", () => {
      if (authSubmitting || registrationState.selecting) return;
      clearSelectionImage(registrationState, registerAvatarPreview, registerAvatarFile);
      hideError(registerAvatarError);
      syncRegistrationControls();
    });
    $("registerAvatarRetry").addEventListener("click", () => {
      if (!authSubmitting && !registrationState.selecting) uploadRegisteredAvatar();
    });
    $("registerAvatarSkip").addEventListener("click", skipRegisteredAvatar);
    $("registerAbandon").addEventListener("click", () => {
      if (authSubmitting || registrationState.selecting) return;
      API.logout();
      showLogin();
    });
~~~

4. 原 authApiSaveBtn click 函数体最前面新增以下 guard，再保留其余保存地址和 checkHealth 逻辑。

~~~javascript
      // 恢复流程不能悄悄切换到另一个后端继续上传。
      if (authSubmitting || registrationState.selecting || registrationState.phase !== "idle") return;
~~~

5. 在 showLogin 中 resetFeatureSession() 之后新增以下一行，确保会话失效时退出忙碌状态。

~~~javascript
    // 会话代次已更新，旧请求的 finally 不应重新控制新登录表单。
    authSubmitting = false;
~~~

6. 修改原 handleAuthError 函数第一行：在 if (!e || !e.auth) return false 之前加入以下代码，旧会话错误静默丢弃。

~~~javascript
    // API 层已标记为过时的响应不应污染当前账号的界面。
    if (e && e.stale) return true;
~~~

7. 检查文件末尾启动逻辑：保留有令牌时 enterApp、无令牌时 showLogin。完成所有代码后再刷新页面。

### 9.5 单元验收代码：模拟注册成功、登录失败

先在一个未登录的独立测试页面打开控制台，执行下列脚本。它会 mock 注册和登录，不访问真实后端；执行后立刻恢复 API 方法。脚本利用真实 DOM 事件走页面代码，因此能发现旧 submit 监听没删除、按钮没绑定等问题。

~~~javascript
(async () => {
  // 在全新、未登录页面执行；不要在普通用户的正在编辑页面执行。
  const original = { register: API.register, login: API.login };
  const assert = (ok, message) => { if (!ok) throw new Error(message); };
  const waitFor = async predicate => {
    for (let i = 0; i < 100; i++) {
      if (predicate()) return;
      await new Promise(resolve => setTimeout(resolve, 20));
    }
    throw new Error("等待页面状态超时");
  };
  let registrations = 0, logins = 0;
  try {
    API.register = async () => { registrations++; return { id: 98765, username: "manual_test" }; };
    API.login = async () => { logins++; throw Object.assign(new Error("模拟自动登录失败"), { httpStatus: 401 }); };
    document.getElementById("authToggleBtn").click();
    document.getElementById("authUsername").value = "manual_test";
    document.getElementById("authPassword").value = "12345678";
    document.getElementById("authForm").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await waitFor(() => logins === 1 && !document.getElementById("authSubmit").disabled);
    assert(document.getElementById("registerStatus").textContent.includes("账号已注册成功"), "未提示部分完成");
    assert(document.getElementById("authPassword").value === "", "密码输入未清空");
    document.getElementById("authPassword").value = "12345678";
    document.getElementById("authForm").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await waitFor(() => logins === 2 && !document.getElementById("authSubmit").disabled);
    assert(registrations === 1, "恢复登录时错误地重复注册");
    assert(![...document.querySelectorAll(".toast")].some(el => el.textContent.includes("已登录")), "登录失败却提示成功");
    console.log("注册阶段恢复验收通过");
  } finally {
    API.register = original.register;
    API.login = original.login;
    document.getElementById("registerAbandon").click();
  }
})();
~~~

### 9.6 单元验收代码：注册请求结果不确定

重新刷新未登录页面后执行：

~~~javascript
(async () => {
  // 无 HTTP 响应时，只能引导登录确认，不能自动第二次注册。
  const original = API.register;
  let calls = 0;
  try {
    API.register = async () => {
      calls++;
      throw Object.assign(new Error("模拟连接中断"), { uncertain: true });
    };
    document.getElementById("authToggleBtn").click();
    document.getElementById("authUsername").value = "unknown_test";
    document.getElementById("authPassword").value = "12345678";
    document.getElementById("authForm").dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    for (let i = 0; i < 100 && document.getElementById("authSubmit").disabled; i++) {
      await new Promise(resolve => setTimeout(resolve, 20));
    }
    if (calls !== 1 || !document.getElementById("registerStatus").textContent.includes("注册结果暂无法确认")) {
      throw new Error("结果不确定分支不正确");
    }
    if (document.getElementById("authTitle").textContent !== "登录口袋账本") throw new Error("没有转到登录确认");
    console.log("注册结果不确定验收通过");
  } finally {
    API.register = original;
    document.getElementById("registerAbandon").click();
  }
})();
~~~

**通过标准：** 两段脚本通过；真实浏览器补测“只填用户名密码”“仅昵称”“仅邮箱”“仅头像”“三项全部填写”“注册前取消图片”。其中头像上传失败后重试的 Network 顺序只能是 GET /auth/me → POST /auth/me/avatar，不得再次出现 POST /auth/register 或重复自动登录。手动登录恢复必须 ID 匹配并确认续传；切换账号、退出或页面关闭后旧图片不再上传。

## 单元 10：真实数据库、认证链路和云端联调

### 10.1 新增数据库集成验收，证明 NULL 真的落库

**新增完整测试文件：** backend/src/test/java/com/fly/pocket_ledger_java/UserProfileIntegrationTest.java。

使用已执行单元 1 迁移的专用测试库，DB_NAME 必须以 _test 结尾。本测试类默认跳过，显式启用后使用事务回滚账号与资料，不访问 OSS；所有生成的账号名使用随机前缀。不要把事务回滚误当成允许在个人实际账单库运行的理由。

~~~java
package com.fly.pocket_ledger_java;

import com.aliyun.oss.OSS;
import com.fly.pocket_ledger_java.dto.RegisterDTO;
import com.fly.pocket_ledger_java.dto.UserProfileUpdateDTO;
import com.fly.pocket_ledger_java.entity.User;
import com.fly.pocket_ledger_java.mapper.UserMapper;
import com.fly.pocket_ledger_java.service.AuthService;
import com.fly.pocket_ledger_java.util.LoginUser;
import com.fly.pocket_ledger_java.util.UserContext;
import com.fly.pocket_ledger_java.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL 验证，不用 mock 的响应推断数据已经入库。 */
@SpringBootTest(properties = {
        "aliyun.oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
        "aliyun.oss.region=cn-hangzhou",
        "aliyun.oss.bucket=test-bucket",
        "aliyun.oss.public-base-url=https://avatar.example.com"
})
@EnabledIfEnvironmentVariable(named = "RUN_DB_INTEGRATION_TESTS", matches = "true")
@Transactional
class UserProfileIntegrationTest {
    @Autowired private AuthService service;
    @Autowired private UserMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private OSS ossClient;

    @BeforeEach
    void requireTestDatabase() {
        // 连接后只读验证实际数据库，防止环境变量指向真实业务库。
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_test");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private UserVO registerUser() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("profile_" + UUID.randomUUID().toString().replace("-", ""));
        dto.setPassword("testPassword123");
        dto.setNickname("重复昵称");
        dto.setEmail("same@example.com");
        return service.register(dto);
    }

    @Test
    void profilePersistsClearsAndDoesNotTouchAvatarOrAnotherUser() {
        UserVO first = registerUser();
        UserVO second = registerUser();
        // 两个用户昵称邮箱完全相同仍可注册，证明没有错误唯一性约束。
        User stored = mapper.selectById(first.getId());
        assertThat(stored.getNickname()).isEqualTo("重复昵称");
        assertThat(stored.getEmail()).isEqualTo("same@example.com");
        assertThat(stored.getAvatarObjectKey()).isNull();
        String key = "avatars/" + first.getId() + "/00000000000000000000000000000000.png";
        jdbc.update("UPDATE fly_user SET avatar_object_key = ? WHERE id = ?", key, first.getId());
        UserContext.set(new LoginUser(first.getId(), first.getUsername()));
        UserProfileUpdateDTO dto = new UserProfileUpdateDTO();
        dto.setNickname("  ");
        dto.setEmail("");
        service.updateCurrentUser(dto);
        service.updateCurrentUser(dto); // 原样保存必须成功。
        User after = mapper.selectById(first.getId());
        assertThat(after.getNickname()).isNull();
        assertThat(after.getEmail()).isNull();
        assertThat(after.getAvatarObjectKey()).isEqualTo(key);
        assertThat(mapper.selectById(second.getId()).getEmail()).isEqualTo("same@example.com");
    }
}
~~~

~~~powershell
# 先通过本地运行环境配置专用测试库的 DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD。
# DB_NAME 必须以 _test 结尾，且已建立原表并执行增量迁移。
$env:RUN_DB_INTEGRATION_TESTS = 'true'
.\mvnw.cmd '-Dtest=UserProfileIntegrationTest' test
Remove-Item Env:RUN_DB_INTEGRATION_TESTS
~~~

**通过标准：** 测试通过，测试生成用户随事务回滚，原有账单不变。Bean Validation 在 Controller 边界执行，直接调用 Service 的测试不能拿非法 DTO 推断 MVC 会创建账号；非法资料“不创建用户”的真实 HTTP 验收见下节。

### 10.2 真实 HTTP 验收脚本（JavaScript 控制台）

启动后端并使用专用测试库与测试 Bucket。以下脚本需要在静态页面控制台运行；它用局部令牌，不修改页面 API 对象或本地登录令牌。会创建两个验收账号，结束后保留以供人工核查；用户名及密码仅存在当前脚本内，控制台只输出用户名。

~~~javascript
(async () => {
  const base = API.getBase();
  const assert = (ok, message) => { if (!ok) throw new Error(message); };
  const suffix = Date.now().toString(36);
  const usernameA = "profile_a_" + suffix, usernameB = "profile_b_" + suffix;
  const password = "Acceptance123!";
  async function call(path, method = "GET", body, token = "") {
    const headers = {};
    if (token) headers.Authorization = "Bearer " + token;
    const multipart = body instanceof FormData;
    if (body !== undefined && !multipart) headers["Content-Type"] = "application/json";
    const res = await fetch(base + path, { method, headers,
      body: body === undefined ? undefined : multipart ? body : JSON.stringify(body) });
    const envelope = await res.json();
    assert(envelope.code === res.status, "HTTP 状态与 code 不一致");
    return { status: res.status, data: envelope.data };
  }
  // 真实拦截器链路：匿名资料、上传和删除都必须拒绝。
  for (const [method, path] of [["GET", "/auth/me"], ["PUT", "/auth/me"], ["DELETE", "/auth/me/avatar"]]) {
    const res = await call(path, method, method === "PUT" ? {} : undefined);
    assert(res.status === 401, "匿名访问未被拒绝：" + method);
  }
  const anonymousFile = new FormData();
  anonymousFile.append("file", new Blob([new Uint8Array([137, 80, 78, 71])]), "a.png");
  assert((await call("/auth/me/avatar", "POST", anonymousFile)).status === 401, "匿名上传未被拒绝");
  // 同名合法注册能紧接非法注册成功，证明非法资料没有留下半成品账号。
  assert((await call("/auth/register", "POST", { username: usernameA, password, email: "bad" })).status === 422,
    "非法邮箱没有拒绝");
  const a = await call("/auth/register", "POST", { username: usernameA, password, nickname: "相同昵称", email: "same@example.com" });
  const b = await call("/auth/register", "POST", { username: usernameB, password, nickname: "相同昵称", email: "same@example.com" });
  assert(a.status === 200 && b.status === 200, "注册或重复资料验收失败");
  assert(a.data.avatar_url === null && !a.data.access_token, "注册不应设置头像或发 token");
  const loginA = await call("/auth/login", "POST", { username: usernameA, password });
  const loginB = await call("/auth/login", "POST", { username: usernameB, password });
  const tokenA = loginA.data.access_token, tokenB = loginB.data.access_token;
  await call("/auth/me", "PUT", {}, tokenA);
  assert((await call("/auth/me", "GET", undefined, tokenA)).data.email === null, "A 的资料未清空");
  assert((await call("/auth/me", "GET", undefined, tokenB)).data.email === "same@example.com", "A 修改影响了 B");
  assert((await call("/auth/me", "PUT", { user_id: b.data.id }, tokenA)).status === 422, "允许指定其他用户");
  // 真正的 1×1 PNG，不用魔数样本冒充可显示图片。
  const pngBytes = Uint8Array.from(atob("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQaPgPAAIzAZDtHsO2AAAAAElFTkSuQmCC"), c => c.charCodeAt(0));
  const form = new FormData();
  form.append("file", new Blob([pngBytes], { type: "image/png" }), "acceptance.png");
  const uploaded = await call("/auth/me/avatar", "POST", form, tokenA);
  assert(uploaded.status === 200 && uploaded.data.avatar_url.startsWith("https://"), "头像上传失败");
  const avatarUrl = new URL(uploaded.data.avatar_url);
  assert(!avatarUrl.search && !avatarUrl.hash, "头像 URL 不应有签名参数");
  assert((await call("/auth/me", "GET", undefined, tokenB)).data.avatar_url === null, "A 上传影响了 B");
  const removed = await call("/auth/me/avatar", "DELETE", undefined, tokenA);
  assert(removed.status === 200 && removed.data.avatar_url === null, "恢复默认失败");
  assert((await call("/auth/me/avatar", "DELETE", undefined, tokenA)).status === 200, "重复恢复默认失败");
  const tooLarge = new FormData();
  tooLarge.append("file", new Blob([new Uint8Array(2 * 1024 * 1024 + 1)]), "oversize.png");
  assert((await call("/auth/me/avatar", "POST", tooLarge, tokenA)).status === 413, "真实容器没有返回 413");
  console.log("真实 HTTP 验收通过；已创建测试账号：", usernameA, usernameB);
})();
~~~

此脚本在删除对象之前只核对 URL 结构，不证明匿名 GET 和原始字节；下一节用新上传的受控测试图片完成真实云验证。若 413 变成连接断开或 HTML 错误页，含义是容器/代理在 MVC 统一处理前拦截了请求；先检查 Tomcat、代理上传大小及错误转发配置，不修改脚本去接受任意失败状态。

### 10.3 OSS 公共读、匿名写拒绝与原始字节验收

使用页面上传一张专用、无个人资料的测试 PNG，记录返回 avatar_url；保持该头像暂时有效。不要为浏览器 fetch 下载图片而额外开放 OSS CORS，普通 img 展示与后端上传不需要此配置。

在 PowerShell 执行以下脚本。输入 URL 应来自刚才真实上传响应；使用 curl.exe 避免 PowerShell 的 curl 别名。下载是验收原始字节所需，输出文件放系统临时目录。此处命令由实施者执行，不是编写文档时已执行。

~~~powershell
# 使用本次受控图片和服务返回的公共地址，不填写签名或密钥。
$avatarSource = Read-Host '输入本次测试 PNG 的本机绝对路径'
$avatarUrl = Read-Host '输入接口返回的 avatar_url'
$avatarUri = [Uri]$avatarUrl
if ($avatarUri.Scheme -ne 'https' -or $avatarUri.Query) { throw '必须使用无签名的 HTTPS 公共地址' }
$downloadPath = Join-Path $env:TEMP ('avatar-check-' + [guid]::NewGuid().ToString('N') + '.png')
curl.exe --fail --silent --show-error --dump-header - --output $downloadPath $avatarUrl
if ($LASTEXITCODE -ne 0) { throw '匿名 GET 失败，请核对公共读权限与浏览器域名' }
if ((Get-FileHash -LiteralPath $avatarSource -Algorithm SHA256).Hash -ne
    (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash) {
    throw '上传前后字节不一致'
}
# 匿名 PUT 测试使用独立随机 Key，绝不覆盖当前头像；预期严格为 403。
$probeUrl = $avatarUrl.Substring(0, $avatarUrl.LastIndexOf('/') + 1) + 'anonymous-write-probe-' + [guid]::NewGuid().ToString('N') + '.png'
$probeStatus = curl.exe --silent --show-error --output NUL --write-out '%{http_code}' --request PUT --upload-file $avatarSource $probeUrl
if ($probeStatus -ne '403') { throw "匿名写权限验收失败：HTTP $probeStatus；停止联调并核查该测试 Key" }
Write-Host '匿名读取、原始字节、匿名写拒绝验收通过'
# 仅删除脚本刚生成的这个临时下载文件，不进行目录清理。
Remove-Item -LiteralPath $downloadPath
~~~

核对 GET 响应 Content-Type 为 image/png，Cache-Control 与代码一致；随后在页面恢复默认，使用维护身份检查刚才测试对象已删除。若匿名写意外成功，用维护身份核对并删除随机 probe 对象，然后修复权限；不得继续把 Bucket 当成“写私有”。由于长缓存，浏览器还能看到旧头像并不能证明云端对象未删除，维护身份应以 OSS 控制台对象状态为准。

### 10.4 更新接口契约文档

**修改：** backend/docs/pocket-ledger-api.html。实施时按下面锚点修改，不另外新增一份相互矛盾的接口契约。

1. 在 article#register 的请求参数表 password 行后新增 nickname/email 行；删除该 article 中“只返回 id、username”含义的旧说明，注册响应示例 data 加入 nickname、email、avatar_url:null。保留注册不发 token。
2. 在 article#register 和 article#me 的返回字段表 username 行后均新增三个字段；两个 username 行中的树形字符从“└─”改成“├─”。更新两个 article 的响应示例。
3. 在 article#me 的结束标签之后、认证 section 结束之前新增三个 article；在左侧 href="#me" 所在 li 后新增三项导航。

可直接录入的表格行：

~~~html
<!-- 注册 Body 表原有六列：名称、类型、是否必须、默认值、备注、其他信息。 -->
<tr><td>nickname</td><td>string / null</td><td>否</td><td>null</td><td>昵称</td><td>去首尾空白，最多 50；空白转 null，允许重复。</td></tr>
<tr><td>email</td><td>string / null</td><td>否</td><td>null</td><td>联系邮箱</td><td>去首尾空白，最多 254，只验证格式；不用于登录。</td></tr>
~~~

~~~html
<!-- 注册与当前用户返回表均为四列；字段必须存在，值允许为 null。 -->
<tr><td class="nested-1"><span class="tree">├─</span> nickname</td><td>string / null</td><td>必须</td><td>昵称，空值时页面显示 username。</td></tr>
<tr><td class="nested-1"><span class="tree">├─</span> email</td><td>string / null</td><td>必须</td><td>联系邮箱。</td></tr>
<tr><td class="nested-1"><span class="tree">└─</span> avatar_url</td><td>string / null</td><td>必须</td><td>公共读永久头像地址，无签名；空值使用默认头像。</td></tr>
~~~

导航片段：

~~~html
<!-- 放在“获取当前用户”导航项后。 -->
<li><a href="#profile-update">修改个人资料</a></li>
<li><a href="#avatar-upload">上传头像</a></li>
<li><a href="#avatar-remove">恢复默认头像</a></li>
~~~

三个 article 完整内容：

~~~html
<!-- 资料和头像共享 UserVO，但写入边界彼此独立。 -->
<article id="profile-update" class="endpoint">
  <h2><span class="method put">PUT</span> 修改个人资料 <span class="auth-badge">需要认证</span></h2>
  <p><strong>Path：</strong><code>/auth/me</code>；Content-Type：application/json。</p>
  <pre><code>{"nickname":"小飞","email":"reed@example.com"}</code></pre>
  <p>整体替换昵称、邮箱。缺失、null 或去空白后的空字符串均清空；{} 清空两项。拒绝任何其他字段，不修改头像。</p>
  <p>成功 HTTP 200，message 为“资料更新成功”，data 为完整公开资料。未登录 401，校验或未知字段 422。</p>
</article>
<article id="avatar-upload" class="endpoint">
  <h2><span class="method post">POST</span> 上传头像 <span class="auth-badge">需要认证</span></h2>
  <p><strong>Path：</strong><code>/auth/me/avatar</code>；Content-Type：multipart/form-data。</p>
  <p>只允许一个名为 file 的文件，不接受额外文件、文本字段或目标用户。原始文件 1 字节至 2 MiB，仅检查 JPEG/PNG 文件头，不解码处理。</p>
  <p>成功 HTTP 200，message 为“头像更新成功”，data 为完整公开资料。文件超限 413，参数错误 422，OSS 上传不可用 503；数据库异常 500。</p>
  <p>每次上传生成全新对象标识；数据库更新成功后尽力删除旧图。删除失败记录日志，不撤销新头像。</p>
</article>
<article id="avatar-remove" class="endpoint">
  <h2><span class="method delete">DELETE</span> 恢复默认头像 <span class="auth-badge">需要认证</span></h2>
  <p><strong>Path：</strong><code>/auth/me/avatar</code>；无需请求体。</p>
  <p>成功 HTTP 200，message 为“已恢复默认头像”，完整公开资料中的 avatar_url 为 null。重复调用成功，昵称邮箱不变。</p>
</article>
~~~

更新注册和 GET /auth/me 的两个响应示例时，data 部分使用以下内容（放进 HTML 的 pre/code 内；JSON 本身不支持注释，中文解释使用外层 HTML 注释）：

~~~html
<!-- 用于替换两个响应示例中的 data 对象；注册阶段 avatar_url 固定为 null。 -->
<pre><code>{
  "code": 200,
  "message": "注册成功",
  "data": {
    "id": 42,
    "username": "reed",
    "nickname": "小飞",
    "email": "reed@example.com",
    "avatar_url": null
  }
}</code></pre>
~~~

GET /auth/me 的示例 message 使用现有默认“操作成功”；不要把“注册成功”复制成 GET 的提示。邮箱和昵称省略时响应保留相应字段且值为 null。

### 10.5 最终执行与记录

~~~powershell
# backend 中执行。普通全套测试不启用真实数据库/云端集成开关。
.\mvnw.cmd test
~~~

~~~powershell
# 项目根目录核对最终修改，不能包含 frontend_vue 或真实凭证。
git status --short
git diff --check
git diff --stat
git diff -- backend fronted_static
~~~

按单元记录下表，只有实际执行并留有输出才勾选通过：

| 项目 | 执行环境/日期 | 实际结果 | 证据 |
| --- | --- | --- | --- |
| 原有认证测试回归 | 实施时填写 | 未执行 | Maven 报告 |
| 迁移前后行数与列结构 | 实施时填写 | 未执行 | SQL 输出与备份记录 |
| DTO、Service、MVC 普通测试 | 实施时填写 | 未执行 | Surefire 报告 |
| MySQL 空值、重复值与隔离 | 实施时填写 | 未执行 | 集成测试报告 |
| 真实 HTTP 401/413/422/503 | 实施时填写 | 未执行 | Network 或脚本输出 |
| OSS 原始字节与权限 | 实施时填写 | 未执行 | 哈希、HTTP 状态与对象清理记录 |
| 注册部分失败恢复 | 实施时填写 | 未执行 | 控制台验收及 Network 记录 |
| 资料草稿、预览、默认头像、窄屏 | 实施时填写 | 未执行 | 浏览器操作记录 |
| 原有分类、账单增删改查、筛选和图表 | 实施时填写 | 未执行 | 浏览器回归记录 |

前端额外核对：只填写可选项的每一种组合；错误邮箱和超长昵称；GIF/SVG/WebP/HEIC；图片损坏；令牌失效；两个标签页切换账号；上传/保存忙碌时重复点击；服务器已提交但响应被丢弃；恢复默认后刷新与重新登录。503 用隔离环境临时配置无写权限的测试身份触发，完成后恢复配置；不要破坏正式身份权限。

## 收尾：提交、发布、回退与人工清理

每个单元测试通过可以做一次小提交；先用 git diff 核对，再用 git add 加入本单元明确路径，不用 git add .。提交由实施者按项目流程决定，本文不执行提交或推送。

发布顺序为：备份与迁移 → OSS 环境配置 → 后端 → API/云端冒烟 → 静态前端 → 浏览器回归。数据库三列可空，应用回退时优先保留列；不要删除列、恢复旧库或重跑初始化脚本来回退已有账单。

对 AVATAR_LINK_FAILED、AVATAR_UPLOAD_FAILED、AVATAR_DELETE_FAILED 中的候选 Key，使用独立维护身份人工确认：等待相关请求结束；优先核查 24 小时以前对象；删除前查询所有用户引用；不确定则保留。查询模板如下，参数值只使用已核对日志中的 Key，不拼接可执行 SQL：

~~~sql
-- 在数据库工具中为 @candidate_key 赋值为本次已核对的日志 Key，再执行只读查询。
-- 以下说明值不会代表真实待删除对象；禁止把“查不到”直接变成批量目录删除。
SET @candidate_key = 'avatars/42/00000000000000000000000000000000.png';
SELECT id, avatar_object_key FROM fly_user WHERE avatar_object_key = @candidate_key;
~~~

实施后自查：只新增两个生产 Java 文件；没有复制公共返回/异常逻辑；没有第二套 DTO 转换器、头像 Service 或 Utils；没有改变密码登录、JWT、账单金额日期规则及 localStorage 键；没有加入签名 URL、图片处理、清理表或自动重试；没有查阅或修改 frontend_vue。所有测试状态按实际结果填写，不能把文档写完等同于功能完成。

## 常见错误：先理解含义，再定位修正

| 现象 | 含义 | 本指导书中的修正位置 |
| --- | --- | --- |
| UserVO 或 AuthServiceImpl 构造器参数数量不符 | 实体/依赖扩展后，测试仍调用旧签名 | 2.4 的两处 UserVO；4.3 的 AuthProfileServiceTest |
| Unknown column nickname | Java 已新增字段，但当前连接的库尚未迁移 | 先 SELECT DATABASE() 核对目标，再按单元 1 检查迁移记录 |
| Could not resolve placeholder OSS_* | 后端进程没有收到环境配置 | 3.2 注入运行环境；普通测试按 3.4 使用假配置与 mock Bean |
| Lambda column cache 相关错误 | 纯 Mockito 测试未初始化 MyBatis 列名元数据 | 4.6 setUp 的 TableInfoHelper.initTableInfo |
| 空字符串清空失败 | 使用了会跳过 null 的实体更新方式 | 2.5 用 LambdaUpdateWrapper 显式 set，而非 updateById(旧实体) |
| multipart boundary 缺失 | 前端手动写了 Content-Type，却没提供浏览器生成的边界 | 6.1 的 FormData 分支不设置 Content-Type |
| 上传成功后资料草稿消失 | 头像响应处理重新回填了表单 | 8.2 renderIdentity 只刷新展示，打开弹窗时才回填输入框 |
| 注册成功后提示用户名占用 | 恢复流程重复提交了注册请求 | 9.4 删除旧 submit；恢复只走登录或头像端点 |
| PUT Object 返回 503 | 业务已将 SDK 上传失败转换为统一错误 | 按操作 ID 查 AVATAR_UPLOAD_FAILED，核对 Region、Endpoint、写权限与网络 |
| 已换头像但日志出现删除失败 | 新关联已提交，仅旧图清理失败 | 不回滚新头像；按收尾部分核对引用后人工清理 |
| 图片 URL 可下载但页面显示默认图 | 图片加载失败、域名策略或原始图片内容不可显示 | 先单独打开地址核对 HTTP 与 Content-Type，再核对图片；不添加签名刷新循环 |

本文中的 mock/DOM 模拟通过只证明相应代码与状态逻辑可运行，不能替代真实浏览器布局、移动端照片方向、MySQL 数据与云端权限验收。

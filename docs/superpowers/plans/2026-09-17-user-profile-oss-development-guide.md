# 用户资料与阿里云 OSS 头像上传开发指导书

**版本：** 2.0  
**日期：** 2026-09-19  
**项目：** Pocket Ledger Java  
**文档性质：** 待实施的设计与开发指导。本文中的 SQL、代码片段和命令未因编写本文而执行，不代表功能已经实现。

> 实施者须知：按本文逐项开发和验证，执行工作可参考本地 executing-plans 技能。遵守项目 AGENTS.md：子代理仅用于探索、检索和核验，代码修改、方案取舍及最终验证由主代理负责。不得查阅或修改 frontend_vue。

**目标：** 注册时支持可选填写昵称、邮箱和选择本地图片作为头像；登录后支持查看、修改、清空资料，以及替换头像、恢复默认头像。

**架构：** 静态前端调用 Java 后端。后端负责认证、参数与文件校验，将头像原始文件存入公共读阿里云 OSS，并将稳定的对象标识写入 MySQL。查询用户资料时用公共访问域名拼接永久地址，以 avatar_url 返回前端。

**后端拆分：** 本版新增 2 个生产 Java 文件（UserProfileUpdateDTO、OssConfig），资料与头像操作全部并入现有 AuthService/AuthServiceImpl，复用现有响应转换和业务异常机制。不新增存储服务、头像服务、图片处理器、配置绑定类或事务模板。需求、接口与页面交互保持不变。

**技术栈：** 原生 HTML/CSS/JavaScript、Java 8、Spring Boot 2.3.0.RELEASE、MyBatis-Plus 3.4.3.4、MySQL 8、阿里云 OSS Java SDK。

**需求依据：** 用户已明确注册时也可选填写昵称、邮箱和上传头像；昵称可重复、可修改且为空时显示用户名；头像通过选择本地图片上传，云端存储使用阿里云；邮箱仅校验格式，不用于登录、验证或密码找回。经用户确认：头像采用公共读 OSS 与永久地址，服务端仅校验大小与文件头类型，不做缩放、重编码和元数据处理，原图元数据（含拍摄位置）公开可见等代价已被接受。第一版不建立文件清理任务表。本文件合并设计说明、接口契约、实施计划和验收清单，作为本次实施的完整依据。

> 版本记录：v1.3 采用私有读 Bucket 与临时签名 URL，规划 AvatarService、OssAvatarStorageService、AvatarImageProcessor、OssProperties 等类与行锁短事务；v2.0 按用户决策整体简化为公共读永久地址方案，新增文件收敛为 2 个，事务设计改为单条 UPDATE。

---

## 1. 范围与约束

### 1.1 本次交付

1. 注册页增加可选昵称、联系邮箱和本地头像选择，支持一次点击完成注册、自动登录及可选头像上传。
2. 查看、修改和清空昵称、邮箱。
3. 从本地选择 JPG/JPEG 或 PNG 图片作为头像。
4. 上传前预览，确认后上传。
5. 将头像上传至阿里云 OSS。
6. 替换已有头像、恢复默认头像。
7. 完成数据迁移、错误处理、旧头像清理和前后端验证。

### 1.2 范围限制

- 只查看和修改 backend、fronted_static 以及本次文档。
- 不查阅、不修改 frontend_vue。
- 保持 Java 8、Spring Boot 2.3 和现有项目结构。
- 继续使用 javax.validation，不改为 jakarta.validation。
- 不升级框架，不引入 Vue 或新的前端构建体系。
- 注册必填用户名、密码，可选昵称、邮箱、头像；三个可选项可以任意组合，也可以全部不填。
- 注册页一次提交的用户操作在内部依次执行 JSON 注册、自动登录、可选头像上传，不要求用户进入个人资料页重新选择头像。
- 登录仍使用用户名、密码，JWT 流程不因资料扩展而改变。
- 不加入邮箱唯一性、邮箱验证、邮件发送或邮箱找回密码。
- 不加入手机号、账号状态、角色权限等额外用户字段。
- 不提供用户填写头像 URL 的输入框。
- 不向浏览器下发永久 OSS AccessKey。
- 本次采用 Java 后端中转上传，不采用浏览器直传 OSS。
- 头像 Bucket 为公共读，头像地址永久有效；写操作仍仅由后端持有密钥执行。
- 服务端仅校验文件大小与文件头类型，不做缩放、重编码、EXIF 方向或元数据处理；接受原图元数据（含拍摄位置）公开可见、原图流量与不可随时吊销访问的代价。
- 不为已有数据重跑包含 DROP TABLE 的初始化脚本。
- 第一版不建立文件清理任务表，不实现清理调度或后台自动重试；删除失败记录日志，后续按需人工清理。

### 1.3 第一版产品决策

- 注册和登录后的资料编辑都支持昵称、邮箱，均可选、允许重复。
- 注册页的头像在账号创建并登录成功后自动上传；账号已创建但头像上传失败时保留账号，明确提示部分完成，并提供仅重试头像或暂时跳过的操作。
- 登录后的头像独立上传并立即生效；登录后的昵称、邮箱通过“保存资料”生效。
- 头像使用公共读 OSS 存储，接口返回由公共访问域名拼接的永久地址，无签名、无有效期。
- 服务端仅校验大小与文件头类型：JPG/JPEG、PNG 最大 2 MiB；不支持 GIF、SVG、WebP、HEIC。不校验像素尺寸，不解码图片内容。
- 上传保存原始字节，不做缩放、重编码和元数据处理；原图携带的 EXIF 等元数据（含拍摄位置）随公共地址公开可见，该代价已被明确接受。
- 默认头像使用静态页面中的固定 SVG 图标。
- 图片不提供手动裁剪器；前端使用居中裁切预览，最终圆形头像由 CSS 展示。
- 对象 Key 不可变（每次上传生成新 Key），公共地址可配置长缓存。

---

## 2. 已核对的项目现状

### 2.1 后端

当前用户表为 fly_user，字段为：

| 字段 | 用途 |
| --- | --- |
| id | 用户主键 |
| username | 唯一登录用户名 |
| password_hash | BCrypt 密码哈希 |
| created_at | 注册时间 |
| updated_at | 更新时间 |

已有接口：

- POST /auth/register
- POST /auth/login
- GET /auth/me

现有实现特征：

- User 实体通过 MyBatis-Plus 映射 fly_user。
- UserMapper 继承 BaseMapper<User>。
- AuthServiceImpl.getCurrentUser() 从 UserContext 获取用户 ID，再查询数据库。
- UserVO 当前只包含 id、username。
- AuthService 接口现有 register、login、getCurrentUser 三个方法；AuthServiceImpl 约 124 行，构造注入 UserMapper、PasswordEncoder、JwtUtils，含私有 toVO(User)。
- AuthController 仅依赖 AuthService 一个服务，现有 AuthControllerWebTest 基于 mock AuthService 做切片测试。
- config/ 目录已有 AuthConfig、JwtProperties、MybatisPlusConfig、WebMvcConfig 等配置类，新增 OssConfig 与该目录惯例一致。
- 统一响应为 code、message、data。
- 参数校验失败采用 HTTP 422。
- 数据库字段名已支持下划线与 Java 驼峰映射。
- Jackson 没有全局 snake_case 策略，JSON 特殊字段使用 @JsonProperty。
- CORS 已允许 PUT 等方法，OPTIONS 已放行。
- 初始化 SQL 含删除用户表、账单表等语句，不能作为已有数据库升级脚本。

### 2.2 静态前端

- API 是 api.js 中的 IIFE 对象。
- request() 目前使用 JSON.stringify(body) 发送请求。
- 登录令牌存储在 localStorage，请求带 Authorization: Bearer。
- request() 解包统一响应，成功返回 data。
- 非登录请求出现 401 时清令牌，并通过 err.auth 标记认证错误。
- enterApp() 调用 API.me()，将用户数据放到 state.user。
- 注册表单当前调用 API.register() 后再调用 API.login()，已有自动登录顺序；需要扩展该顺序以处理可选头像及各阶段失败。
- 当前注册成功提示早于实际登录完成，实施时应调整提示时机，不能在登录接口成功前显示“已自动登录”。
- 当前导航只显示 username。
- 已有 modal、toast、字段错误和按钮加载样式，可以复用。
- 现有资料状态没有昵称、邮箱或头像编辑功能。
- 没有专门的静态前端测试框架。

以上是编写指导书时的基线；实施前仅对相关文件确认是否已有新改动，不要覆盖用户新增代码。

---

## 3. 最终字段设计

### 3.1 昵称、邮箱

| 属性 | nickname | email |
| --- | --- | --- |
| 是否必填 | 否 | 否 |
| 是否唯一 | 否 | 否 |
| 能否修改 | 是 | 是 |
| 最大长度 | 50 | 254 |
| 数据库空值 | NULL | NULL |
| 处理首尾空白 | trim | trim |
| 空字符串 | 转 NULL | 转 NULL |
| 展示兜底 | username | 未填写 |
| 业务用途 | 展示名称 | 联系资料 |

邮箱只校验格式，不校验邮箱是否真实存在或归用户所有，不自动转换整段邮箱大小写，不创建唯一索引。

昵称长度在 Java @Size 和前端 maxlength 中按其字符串长度规则执行。包含 emoji 的长度可能按多个 UTF-16 单元计数；前后端提示保持一致，不按数据库 VARCHAR 可容纳上限放宽后端限制。

### 3.2 头像存储与地址拼接

| 层次 | 字段 | 用途 |
| --- | --- | --- |
| MySQL | avatar_object_key | 保存稳定 OSS 对象标识 |
| Java User | avatarObjectKey | 实体映射 |
| Java UserVO | avatarUrl | 公共访问域名拼接出的永久地址 |
| JSON | avatar_url | 前端图片展示 |
| 静态前端 | state.user.avatar_url | 当前头像地址 |

对象标识示例：

~~~text
avatars/42/4e94ae5336584d22af22cdac197a4280.png
~~~

拼接规则：

~~~text
avatar_url = {aliyun.oss.public-base-url} + "/" + avatar_object_key
~~~

规则：

- 数据库不保存完整 URL，只保存对象标识；更换访问域名时只改配置，不改任何用户数据。
- 数据库不保存图片二进制、Base64 或本机文件路径。
- avatar_object_key 为 NULL 时，avatar_url 返回 null。
- 拼接是纯字符串操作，不发起网络请求，不依赖 OSS SDK。
- avatar_url 是后端生成的只读响应字段，地址永久有效。
- 普通资料更新请求不接受 avatar_url、avatar_object_key。
- 不把内部 avatar_object_key 暴露为用户可任意提交的字段。
- 不将用户名或默认头像地址写入数据库作为“默认资料”。

这是对 v1.3 私有读签名地址方案的正式修订：Bucket 改为公共读，avatar_url 由临时签名地址改为永久拼接地址；数据库仍使用 avatar_object_key，接口字段名不变。

---

## 4. 用户交互与页面行为

### 4.1 导航身份区

登录后显示：

- 当前头像或默认头像。
- nickname 非空时显示 nickname，否则显示 username。
- “个人资料”按钮。
- 现有退出按钮。

所有用户输入文字使用 textContent 渲染。

### 4.2 个人资料弹窗

| 区域 | 控件与行为 |
| --- | --- |
| 用户名 | 只读展示，不提交 |
| 头像 | 当前头像、选择图片、待上传预览、确认上传、取消选择、恢复默认 |
| 昵称 | 可选文本框，最多 50 |
| 邮箱 | 可选 email 输入框，最多 254 |
| 资料操作 | 保存资料、关闭 |

提示文案：

- 昵称：“未填写时显示用户名。”
- 邮箱：“仅作联系资料，不用于登录或密码找回。”
- 头像：“支持 JPG、PNG，最大 2 MB。头像上传成功后立即生效。”
- 待上传：“已选择图片，确认上传后生效。”

### 4.3 登录后的保存边界

- 选择图片仅创建本地预览，不上传。
- 确认上传成功后头像立即生效。
- 关闭弹窗不撤销已经成功的头像上传。
- “保存资料”只保存昵称、邮箱，不隐式上传待选图片。
- 头像上传响应到达后，不覆盖用户尚未保存的昵称、邮箱输入草稿。
- 恢复默认头像只清空头像关联，不清空昵称、邮箱。
- 正在提交期间禁用重复提交和会造成误解的关闭操作。
- 上传失败保留选中图片供重试，界面区分“当前头像”与“待上传预览”。

### 4.4 图片展示

- 默认头像为固定 SVG，不需要存入 OSS。
- 当前头像加载中显示默认头像。
- 图片加载成功后显示真实头像。
- 头像地址永久有效，加载失败直接回退默认头像，无地址刷新逻辑。
- 设置固定尺寸、圆形边框、object-fit: cover 和 object-position: center。
- 大图预览与导航头像都保持可见的裁切提示。
- 预览使用本地 blob URL，使用完毕后释放。

### 4.5 注册页面

注册模式显示以下控件；登录模式仍只显示用户名、密码：

| 控件 | 要求 |
| --- | --- |
| 用户名 | 必填，沿用现有 2～50 长度规则 |
| 密码 | 必填，沿用现有 8～64 长度及 BCrypt 72 字节上限 |
| 昵称 | 可选，最多 50，未填时显示用户名 |
| 邮箱 | 可选，仅做格式与长度校验 |
| 头像 | 可选，本地选择、预览、取消选择；沿用图片限制 |
| 注册并登录 | 一次触发注册、登录及可选头像上传 |

操作顺序：

1. 用户可在注册前选择头像并预览，同时填写可选昵称、邮箱。
2. 提交前检查文本规则和图片大小、基础类型，明显不合法时停留原表单。
3. 注册请求创建账号，并在同一数据库事务中保存昵称、邮箱；此时头像关联为空。
4. 前端使用刚输入的用户名、密码调用现有登录接口。
5. 有待上传头像时，用新令牌调用 POST /auth/me/avatar；没有头像则跳过。
6. 成功后进入主界面并展示最终资料；成功上传前不显示“头像已设置”。

注册页无需单独的“确认上传”按钮，也不允许在未登录时调用头像接口。三个网络请求属于同一次注册交互，并不要求用户注册后到资料页面补填。

账号成功创建后，后续失败必须按阶段处理：

- 自动登录失败：显示“账号已注册成功，请登录后继续”，不再次提交注册。
- 头像上传失败：显示“账号已注册并登录，头像上传失败”，提供“重试头像”和“暂时跳过”；昵称、邮箱仍已保存。
- 用户跳过：进入系统；页面内可将待上传图片交给个人资料区域继续处理，也可以主动取消选择，不能悄悄重新注册。
- 请求超时不能直接推断注册或上传未发生；确认状态后再继续，详见第 12.9 节。

---

## 5. API 契约

所有 /auth/me 相关接口要求有效登录。用户身份从 JWT 验证后的 UserContext 获取，不从请求体或路径接收 userId。

所有成功响应使用现有 ApiResponse；所有失败响应 data 为 null。

### 5.1 GET /auth/me

成功：HTTP 200。

~~~json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": 42,
    "username": "reed",
    "nickname": "小飞",
    "avatar_url": "https://avatar.example.com/avatars/42/example.png",
    "email": "reed@example.com"
  }
}
~~~

示例域名是说明值。运行时使用实际 Bucket 的公共读访问域名拼接，地址中不含签名参数。

未填写资料时，三个可选响应字段均保留，值为 null：

~~~json
{
  "id": 42,
  "username": "reed",
  "nickname": null,
  "avatar_url": null,
  "email": null
}
~~~

不返回 passwordHash、password_hash、avatar_object_key、OSS 密钥。

### 5.2 PUT /auth/me

请求 Content-Type 为 application/json。

~~~json
{
  "nickname": "小飞",
  "email": "reed@example.com"
}
~~~

请求语义：

- 整体替换昵称、邮箱这两个字段。
- 前端每次提交两个字段。
- null、去空白后的空字符串或缺失字段都按清空处理。
- 因此 {} 会清空昵称、邮箱。
- 不更新头像。
- 拒绝 id、user_id、username、password、password_hash、avatar_url、avatar_object_key 及其他未知字段。

成功：HTTP 200，message 为“资料更新成功”，data 为完整 UserVO。

### 5.3 POST /auth/me/avatar

请求采用 multipart/form-data，唯一文件字段为 file。

~~~http
POST /auth/me/avatar
Authorization: Bearer <access_token>
Content-Type: multipart/form-data; boundary=<浏览器自动生成>
~~~

- 一次只接受一个 file。
- 缺失 file、空文件、多文件或额外文件字段应明确拒绝。
- 服务端不接受前端提供的目标用户、Bucket、Key、URL 或删除路径。
- 成功表示 OSS 上传完成且数据库头像关联已提交。
- 成功：HTTP 200，message 为“头像更新成功”，data 为完整 UserVO。
- 不使用用户原始文件名生成目标 Key。

### 5.4 DELETE /auth/me/avatar

不需要请求体。

- 清空当前用户头像关联。
- 成功后头像相关响应字段为 null。
- 原本没有头像时重复调用，也返回成功。
- 成功：HTTP 200，message 为“已恢复默认头像”，data 为完整 UserVO。
- 旧 OSS 对象删除失败不推翻已成功的恢复默认操作，记录日志供后续人工清理。

### 5.5 注册与登录兼容

- POST /auth/register 继续接收 application/json，必填 username、password，新增可选 nickname、email。
- 仅发送原来的 username、password 仍能注册；旧调用保持兼容。
- 文件不通过 JSON 或 Base64 传入 RegisterDTO，注册页选中的 File 暂存浏览器内存，登录成功后通过 POST /auth/me/avatar 的 multipart 请求上传。
- 注册接口不接收 avatar_url、avatar_object_key、目标用户 ID 或云端凭证，也不开放匿名头像上传。
- 注册响应使用扩展后的 UserVO，nickname、email 返回实际保存值，未填写则为 null；avatar_url 在此阶段为 null。
- POST /auth/login 请求、令牌响应不变。
- 不向 JWT 增加昵称、邮箱和头像地址。
- 修改资料后不要求重新登录，不重新签发令牌。
- 更新原有 UserVO 构造器调用及相应测试。

注册请求示例：

~~~json
{
  "username": "reed",
  "password": "examplePassword123",
  "nickname": "小飞",
  "email": "reed@example.com"
}
~~~

注册成功响应仍不发 token：

~~~json
{
  "code": 200,
  "message": "注册成功",
  "data": {
    "id": 42,
    "username": "reed",
    "nickname": "小飞",
    "avatar_url": null,
    "email": "reed@example.com"
  }
}
~~~

随后前端登录并上传已选择的图片，上传接口返回带实际 avatar_url 的完整资料。这套分阶段协议不承诺“建号与上传头像全部成功或全部回滚”；头像失败不删除已创建账号，也不撤销昵称、邮箱。

非法昵称或邮箱返回 422，且不创建账号；用户名重复沿用 409。注册成功后仅重试失败的登录或头像阶段，不能把同一份表单再次当作新注册请求发送。

### 5.6 状态码

| 状态码 | 场景 |
| --- | --- |
| 200 | 查询、资料保存、头像替换或移除成功 |
| 401 | 未登录、令牌失效、用户已不存在 |
| 413 | multipart 或文件大小超限 |
| 422 | 邮箱格式、长度、文件缺失、非 JPG/PNG 文件头等 |
| 503 | OSS 上传等必要外部操作暂时不可用 |
| 500 | 未预期的数据库或内部异常 |

错误示例：

~~~json
{
  "code": 422,
  "message": "头像仅支持 JPG、PNG 格式",
  "data": null
}
~~~

服务端日志保留排障信息，但响应不直接透出 SDK 异常、内部路径或凭证。

---

## 6. 数据库迁移

### 6.1 执行前检查

- [ ] 确认目标数据库。
- [ ] 备份数据，并验证备份可读取。
- [ ] 查询 fly_user 的实际结构和索引。
- [ ] 确认本次新增列尚不存在。
- [ ] 在测试库试执行迁移。
- [ ] 记录执行版本、时间和结果。

只读检查：

~~~sql
SHOW CREATE TABLE fly_user;
SHOW COLUMNS FROM fly_user;
SHOW INDEX FROM fly_user;
~~~

### 6.2 用户表迁移

若三列都尚未创建，执行以下一次性迁移：

~~~sql
ALTER TABLE fly_user
    ADD COLUMN nickname VARCHAR(50) NULL DEFAULT NULL
        COMMENT '展示昵称，允许重复',
    ADD COLUMN avatar_object_key VARCHAR(255) NULL DEFAULT NULL
        COMMENT 'OSS头像对象标识',
    ADD COLUMN email VARCHAR(254) NULL DEFAULT NULL
        COMMENT '联系邮箱，仅校验格式';
~~~

注意：

- 不给昵称、邮箱增加唯一索引。
- 不需要给头像对象标识增加业务查询索引。
- 老用户三列自动为 NULL，不需要伪造默认值。
- 保持用户主键和账单关联不变。
- 保留现有 updated_at 自动更新机制。
- 初始化建表脚本同步增加三列，但不在已有数据库运行整个初始化脚本。
- 如果早期 avatar_url 列已被别人创建，应另写结构修订迁移，检查其数据后再处理；不能把完整 URL 直接当 Object Key 使用。
- 本脚本不是可反复执行的幂等脚本，使用迁移记录防止重复执行。

### 6.3 本次数据库变更边界

本次只给 fly_user 增加上述三列，不新增文件清理表或其他业务表。旧头像在数据库更新成功后尝试直接删除，失败仅记录日志，处理方式见第 10、11 节。

原有头像 Key 永不重新绑定给用户；每次上传生成全新 Key。这是避免误删当前头像的重要前提。

---

## 7. 阿里云 OSS 配置

### 7.1 资源准备

实施前记录以下配置，不能用示例值代替真实环境：

| 项目 | 要求 |
| --- | --- |
| Bucket | 专门用于本项目，或具有明确隔离前缀 |
| Region | 与 Bucket 实际地域一致 |
| Endpoint | 后端可访问的 HTTPS SDK Endpoint |
| 浏览器访问域名 | 公共读访问域名，浏览器可直接 GET，必要时绑定自定义域名 |
| 对象前缀 | avatars/ |
| Bucket 读权限 | 公共读（public-read）；写权限保持私有，仅后端密钥可写 |
| 身份 | 专用 RAM 身份，仅授予写权限，部署时优先角色凭证 |

头像对象通过公共读地址直接访问，地址永久有效、无签名。持有地址者可长期读取该图片；原样上传的图片若含 EXIF 拍摄位置等元数据，将随地址一并公开。这是第 1.3 节已接受的取舍。

参考：

- [OSS 权限与访问控制](https://www.alibabacloud.com/help/en/oss/user-guide/permissions-and-access-control-overview)
- [固定 URL 与签名 URL](https://www.alibabacloud.com/help/en/oss/use-a-fixed-file-url-to-access-a-file)

### 7.2 权限边界

业务服务仅对目标 Bucket 的 avatars/* 具有必要写权限：

- PutObject：上传。
- DeleteObject：清理。

读取走公共读，不需要为业务身份申请 GetObject 权限。人工核查遗留文件使用独立维护身份。不需要业务服务创建 Bucket、管理账号或列举所有 Bucket。

### 7.3 Spring 配置

合并到已有 spring 节点，不创建重复的 spring 根节点：

~~~yaml
spring:
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 3MB

aliyun:
  oss:
    endpoint: ${OSS_ENDPOINT}
    region: ${OSS_REGION}
    bucket: ${OSS_BUCKET}
    public-base-url: ${OSS_PUBLIC_BASE_URL}
    avatar-prefix: avatars/
~~~

aliyun.oss 是本项目新增自定义配置，由 OssConfig 以 @Value 读取，不单独建立配置绑定类。public-base-url 是浏览器可访问的公共读域名，用于拼接 avatar_url。

凭证从环境或凭证提供器加载。开发环境可使用 OSS_ACCESS_KEY_ID、OSS_ACCESS_KEY_SECRET；不得填写真实密钥到代码、静态 JS 或提交到 Git 的配置。

### 7.4 SDK 与连接

官方 OSS Java SDK V1 支持 Java 7 及以上。官方文档当前 Maven 示例为：

~~~xml
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.18.4</version>
</dependency>
~~~

该版本作为实施候选固定版本；实施时在当前 JDK 8、Spring Boot 依赖树下编译并执行上传、删除测试，不能只凭本文认定已兼容。本方案不引入 EXIF 或图片处理第三方库。

配置要求：

- 显式使用 HTTPS 与 V4 签名，设置正确 Region。
- SDK 客户端由 OssConfig 创建为 Spring 单例 Bean，关闭应用时 shutdown。
- 配置有限的连接、读取、连接池等待超时和重试次数。
- 前端上传超时可设 30 秒；后端 OSS 操作预算应短于该值。
- 本地电脑访问公网 Endpoint；不能返回阿里云内网域名给浏览器。
- 如默认域名受账号、地域或预览规则限制，绑定自定义 HTTPS 域名并实际验证。
- 上传时可设置 Cache-Control（如 public, max-age=31536000）：对象 Key 不可变，长缓存安全且省流量。
- 普通 img 展示不要求为了上传开放 OSS 全域 CORS；本方案上传发生在 Java 后端。

参考：

- [Java SDK 安装与配置](https://www.alibabacloud.com/help/en/oss/developer-reference/oss-java-sdk/)
- [Java 简单上传](https://www.alibabacloud.com/help/en/oss/developer-reference/simple-upload-11)
- [OSS 访问域名与网络](https://www.alibabacloud.com/help/en/oss/user-guide/access-and-network-overview)

---

## 8. 后端文件与职责

项目根目录为 C:/fly_develop/java_ai_project/pocket_ledger_java_fly。

Java 主包目录为 backend/src/main/java/com/fly/pocket_ledger_java，测试包位于 backend/src/test/java/com/fly/pocket_ledger_java。

### 8.1 修改已有文件

| 相对主包路径或项目路径 | 改动 |
| --- | --- |
| entity/User.java | nickname、avatarObjectKey、email |
| dto/RegisterDTO.java | 保留用户名、密码校验，增加可选 nickname、email、空值规范化和未知字段拒绝 |
| vo/UserVO.java | nickname、avatarUrl、email |
| service/AuthService.java | 新增 updateCurrentUser、replaceAvatar、removeAvatar 三个方法 |
| service/impl/AuthServiceImpl.java | 注册时保存 nickname、email；实现资料更新与头像编排；文件校验、OSS 上传/删除为私有方法；扩展已有 toVO()，用公共域名拼接永久地址 |
| controller/AuthController.java | PUT /me、POST /me/avatar、DELETE /me/avatar，继续只依赖 AuthService |
| common/ResultCode.java | 增加文件超限、文件类型及 OSS 暂时不可用等必要错误码，复用 BusinessException |
| exception/GlobalExceptionHandler.java | 补充 multipart 异常映射，复用现有 BusinessException 处理；修正只提示 application/json 的媒体类型文案 |
| backend/pom.xml | 仅新增 OSS SDK 依赖，不引入图片处理库 |
| backend/src/main/resources/application.yml | multipart 与 OSS 配置 |
| sql/pocket_ledger_init.sql | 新环境结构 |
| fronted_static/index.html | 注册可选资料区、导航与资料弹窗 |
| fronted_static/js/api.js | 扩展 register 参数，支持 FormData 与新接口 |
| fronted_static/js/app.js | 注册分阶段提交、两处头像选择预览、渲染和状态处理 |
| fronted_static/css/styles.css | 头像、表单与适配 |

UserMapper 无需改动：头像关联更新使用现有 MyBatis-Plus 能力，不增加锁行查询方法。

### 8.2 新增 Java 文件：共 2 个

| 文件/类 | 职责 |
| --- | --- |
| dto/UserProfileUpdateDTO.java | 仅接收昵称、邮箱 |
| config/OssConfig.java | 以 @Value 读取 aliyun.oss 配置，创建并管理 OSS SDK 单例客户端 Bean，应用关闭时释放 |

另在实施时创建 backend/docs/migrations/2026-09-17-user-profile-oss.sql 增量迁移文件；SQL 与必要测试文件不计入上述生产 Java 文件数量。

不新增以下类及其理由：OssProperties（配置项仅 5 个，@Value 足够）；OssAvatarStorageService（调用方仅头像一处，SDK 调用是 AuthServiceImpl 的私有方法）；AvatarService（编排并入 AuthServiceImpl）；AvatarImageProcessor（本方案不做图片处理）。

避免在 Controller 中写 SDK 调用代码，也避免业务 Service 依赖前端上传文件名生成存储路径。

### 8.3 复用方式与依赖方向

- AuthService 接口扩展 updateCurrentUser(UserProfileUpdateDTO)、replaceAvatar(MultipartFile)、removeAvatar()；AuthServiceImpl 继续承担注册、登录、资料读写与头像编排，是用户相关操作的唯一 Service。
- AuthServiceImpl 新增注入 OssConfig 提供的 OSS 客户端，另以 @Value 读取公共访问域名与头像前缀；已有私有 toVO(User) 仍是用户响应的统一转换入口，Key 非空时拼接永久地址，纯字符串操作。
- 文件校验（大小、文件头魔数）与 OSS putObject/deleteObject 是 AuthServiceImpl 的私有方法，不单独成类。
- AuthController 的新端点调用 authService 完成变更后，再调用 authService.getCurrentUser() 组装响应；继续维持单一 Service 依赖，AuthControllerWebTest 维持现有 mock 结构。
- 头像写方法 replaceAvatar/removeAvatar 因包含 OSS 网络调用，不加 @Transactional；数据库关联更新是单条 UPDATE，自带原子性（见第 10.5 节）。
- 文件与 OSS 操作失败复用 BusinessException、ResultCode 及全局异常处理；原始 SDK 异常在捕获处记录，清理失败单独捕获。
- 不为上述改动再配套建立接口、工厂或通用框架。将来确有第二种存储实现或图片处理需求时，再评估抽象。

这样头像与资料能力全部落在现有认证链路上，不为单一调用方的逻辑增加转发层。

---

## 9. 注册与资料编辑后端实现

### 9.1 User 实体新增属性

~~~java
private String nickname;
private String avatarObjectKey;
private String email;
~~~

由已有 MyBatis-Plus 下划线映射对应数据库列。

### 9.2 登录后资料更新 DTO

以下为可按当前包结构实现的 DTO 示例：

~~~java
package com.fly.pocket_ledger_java.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import javax.validation.constraints.Email;
import javax.validation.constraints.Size;

@Data
public class UserProfileUpdateDTO {

    @Size(max = 50, message = "长度不能超过 50")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "长度不能超过 254")
    private String email;

    public void setNickname(String nickname) {
        this.nickname = normalize(nickname);
    }

    public void setEmail(String email) {
        this.email = normalize(email);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("不支持的字段：" + name);
    }
}
~~~

不添加 @NotBlank，因为字段可选。邮箱的最终格式判断由后端执行，前端只做及时提示。

### 9.3 局部字段更新

AuthService 增加：

~~~java
UserVO updateCurrentUser(UserProfileUpdateDTO dto);
~~~

Service 从 UserContext 取得用户 ID 并检查用户存在。明确只更新两列：

~~~java
LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
update.eq(User::getId, userId)
      .set(User::getNickname, dto.getNickname())
      .set(User::getEmail, dto.getEmail());

userMapper.update(null, update);
~~~

- 使用显式 set(..., null) 实现清空。
- 不用完整旧实体覆盖数据库整行。
- 不修改 avatarObjectKey。
- 不仅凭更新行数为 0 判断失败；原样保存也要成功。
- 写操作使用非只读事务。
- 完成后查询最新用户，使用现有 AuthServiceImpl.toVO(User) 转为 UserVO。

MyBatis-Plus 文档说明显式 set 的值为 null 时可以写入数据库 NULL：
[条件构造器](https://baomidou.com/guides/wrapper/)

### 9.4 VO 与映射

~~~java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {
    private Long id;
    private String username;
    private String nickname;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    private String email;
}
~~~

扩展现有 AuthServiceImpl.toVO(User)，不新建响应组装类。转换行为：

1. id、username、nickname、email 从实体直接映射。
2. avatarObjectKey 为 null，则 avatarUrl 为 null。
3. 否则用 @Value 注入的公共访问域名拼接永久地址：publicBaseUrl + "/" + objectKey；纯字符串操作，无网络调用、无 SDK 依赖。
4. 不在 nickname 为空时写入 username；回退由页面负责。

UserVO 原有两参数构造器调用需调整。建议生产映射使用无参构造与 setter，减少增加字段时参数错位。

注册、查询和资料更新均复用该私有转换方法。头像端点完成写操作后调用 AuthService.getCurrentUser()，因此也使用相同转换逻辑，无需把 toVO 暴露为公共业务接口。

### 9.5 Controller

~~~java
@PutMapping("/me")
public ApiResponse<UserVO> updateMe(
        @Valid @RequestBody UserProfileUpdateDTO dto) {
    return ApiResponse.success(
            "资料更新成功",
            authService.updateCurrentUser(dto));
}
~~~

继续复用现有参数异常转换，未知字段为 422。保持 /auth/me 不在匿名白名单内。

### 9.6 注册 DTO 的完整设计

RegisterDTO 与 UserProfileUpdateDTO 分别负责注册和已登录资料修改，不能只扩展后者而遗漏前者。

以下示例保留当前项目用户名、密码的 trim、长度及 BCrypt 字节数规则，并新增可选字段：

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

@Data
public class RegisterDTO {

    @NotBlank(message = "不能为空")
    @Size(min = 2, max = 50, message = "长度需在 2~50 个字符之间")
    private String username;

    @NotBlank(message = "不能为空")
    @Size(min = 8, max = 64, message = "长度需在 8~64 个字符之间")
    private String password;

    @Size(max = 50, message = "长度不能超过 50")
    private String nickname;

    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "长度不能超过 254")
    private String email;

    @JsonIgnore
    @AssertTrue(message = "密码 UTF-8 编码后不能超过 72 字节")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null
                || password.getBytes(StandardCharsets.UTF_8).length <= 72;
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

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("不支持的字段：" + name);
    }
}
~~~

明确 DTO 的分工：

- RegisterDTO：username、password、nickname、email。
- UserProfileUpdateDTO：nickname、email。
- 头像上传接口：MultipartFile file，用户身份来自当前 JWT。

注册页支持上传头像，不意味着 JSON DTO 必须包含 MultipartFile。图片走现有受认证上传接口，保留文件格式和业务资料各自的校验边界。

### 9.7 注册 Service 与 Controller

保留 AuthController.register(@Valid @RequestBody RegisterDTO dto) 和现有注册事务，在创建 User 时补充字段：

~~~java
User user = new User();
user.setUsername(dto.getUsername());
user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
user.setNickname(dto.getNickname());
user.setEmail(dto.getEmail());
user.setAvatarObjectKey(null);
~~~

后续沿用用户名查重、唯一索引兜底、userMapper.insert(user) 和响应转换逻辑。昵称、邮箱不查询是否重复，不创建唯一索引。

实现要求：

1. 校验失败时，在创建用户之前返回 422。
2. 用户名、密码哈希、昵称、邮箱通过同一次插入保存，不先建号再额外调用资料更新接口补写。
3. 注册 Service 不上传 OSS 文件，避免未认证请求直接写头像。
4. 保留注册不发 token、登录接口负责发 token 的职责。
5. 注册成功响应包含实际 nickname、email，avatar_url 为 null；头像成功后由上传响应反映结果。
6. 更新现有“注册只返回 id、username”的代码注释和响应断言。
7. 注册与登录后的资料更新采用相同昵称、邮箱约束，测试两种入口的空值行为一致。

---

## 10. 头像文件校验与存储

### 10.1 输入校验

按以下顺序处理。本方案不解码图片内容，因此不设像素尺寸限制，也不存在解码放大攻击面：

1. 确认是当前登录用户。
2. 确认 multipart 中恰好一个名为 file 的文件，没有额外业务字段。
3. 确认 MultipartFile 不为空。
4. 检查大小：1 至 2,097,152 字节（2 MiB）。
5. 读取文件头魔数：JPEG 以 FF D8 FF 开头，PNG 以 89 50 4E 47 开头；其余一律拒绝。
6. 按魔数决定对象扩展名（jpg/png）与上传 Content-Type。

不因原文件叫 image.jpg、请求头声称 image/jpeg 或浏览器可以预览就放行；判定依据只有文件内容本身。

### 10.2 存储规范

- 上传原始字节，不做缩放、重编码、EXIF 方向或元数据处理；原图携带的 EXIF 等元数据（含拍摄位置）随公共地址公开可见，此代价已在第 1.3 节明确接受。
- Content-Type 按魔数设置为 image/jpeg 或 image/png。
- 上传时可设置 Cache-Control（如 public, max-age=31536000）：对象 Key 不可变，长缓存安全。
- 前端不显示原始图片文件名作为头像名称。

### 10.3 对象 Key

~~~text
avatars/{userId}/{去掉连字符的随机UUID}.{jpg或png}
~~~

- userId 必须来自后端认证上下文。
- UUID 必须由后端产生。
- 不包含原始文件名、邮箱或昵称。
- 每次上传均为新 Key。
- 不接受已有 Key 的重新绑定。

上传与删除由 AuthServiceImpl 的私有方法直接调用 OSS SDK 完成，无独立存储服务类：

~~~java
// AuthServiceImpl 内私有方法示意
private String putAvatarObject(Long userId, byte[] content, String contentType) { ... }
private void deleteAvatarObject(String objectKey) { ... }
~~~

putAvatarObject 返回新生成的 Object Key，扩展名由已验证的魔数结果决定。

### 10.4 头像端点

~~~java
@PostMapping(
        value = "/me/avatar",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ApiResponse<UserVO> uploadAvatar(
        @RequestParam("file") MultipartFile file) {
    authService.replaceAvatar(file);
    return ApiResponse.success(
            "头像更新成功",
            authService.getCurrentUser());
}
~~~

此示例展示路由与参数形式；实际端点还需读取 MultipartHttpServletRequest，在现有 Controller 的私有辅助方法中验证文件数量和额外字段，无需再建统一校验器类。不能只靠单个 MultipartFile 参数断言请求只有一个文件；校验应发生在调用 Service 之前。

删除端点：

~~~java
@DeleteMapping("/me/avatar")
public ApiResponse<UserVO> removeAvatar() {
    authService.removeAvatar();
    return ApiResponse.success(
            "已恢复默认头像",
            authService.getCurrentUser());
}
~~~

### 10.5 实现顺序与事务策略

replaceAvatar 与 removeAvatar 不加 @Transactional，也不使用 TransactionTemplate：两者都包含 OSS 网络调用，不应裹在数据库事务内占用连接等待网络。头像关联更新是单条 UPDATE 语句，数据库对单条语句自带原子性，无需显式事务，也不需要行锁。

替换流程：

1. 校验文件（第 10.1 节）。
2. 读取当前用户的旧 Key（普通 SELECT）。
3. 上传新对象，取得 newKey。
4. 单条 UPDATE 将 avatar_object_key 置为 newKey。
5. UPDATE 成功后，oldKey 非空且不同于 newKey 时尝试删除旧对象；删除异常单独捕获并记录日志，不将已成功的替换改判为失败。

恢复默认流程：

1. 读取当前用户的旧 Key。
2. 单条 UPDATE 将 avatar_object_key 置为 NULL。
3. UPDATE 成功后尽力删除旧对象，失败记日志，操作仍成功。

updateCurrentUser 与 register 保持现有 @Transactional 风格（纯数据库操作，无 OSS 调用）。

并发说明：个人应用并发替换同一用户头像的概率可忽略；即便发生，两次单条 UPDATE 各自原子，最终结果必然指向其中一次上传，最坏情况是 OSS 残留一个无人引用的孤儿对象，不影响数据一致性，因此不引入行锁。

### 10.6 失败处理

| 失败点 | 处理 |
| --- | --- |
| 文件校验失败 | 不上传、不改数据库，返回 422/413 |
| OSS 上传失败 | 保留当前关联，返回 503 |
| 上传成功、UPDATE 失败 | 数据库仍指向旧头像；记录已上传的 newKey 供人工删除，不自动重试 |
| 删除旧对象失败 | 新头像照常生效，记录日志，后续按需人工清理 |
| 删除时对象已不存在 | 视为成功 |

本版没有持久化清理队列或自动恢复机制。服务异常退出时，OSS 可能残留无人引用的图片，按第 11 节人工核查处理；单条 UPDATE 不承诺与 OSS 的原子一致。

---

## 11. 直接删除、人工清理与地址特性

### 11.1 直接删除与失败日志

1. 使用后端从数据库取得的旧 Key 或本次上传生成的新 Key，不接受前端指定待删除路径。
2. 确认 Key 属于配置的头像前缀，并且不是当前有效头像。
3. 在数据库关联更新成功后调用 OSS 删除；对象已不存在同样视为成功。
4. 删除失败记录操作 ID、用户 ID、对象 Key、处理阶段、OSS Request ID 和异常原因。
5. 日志使用统一事件标识 AVATAR_DELETE_FAILED，便于后续人工查找。
6. 不创建删除任务，不启动定时扫描或应用级后台重试。

SDK 自身的有限网络重试可以保留，但不等同于持久化清理机制。旧文件删除失败不能把新头像更新变成失败。

### 11.2 按需人工清理遗留图片

出现删除失败日志或需要核查存储时，由维护人员按以下步骤操作；这不是本版需要开发的自动化功能：

1. 从失败日志取得候选对象 Key；若怀疑服务中断导致日志缺失，可在 OSS 控制台核查 avatars/ 下较早的对象。
2. 优先核查创建超过 24 小时的对象，并确认没有相关上传请求仍在处理中。
3. 查询 fly_user，确认当前没有用户引用候选 Key。
4. 删除前再次核对对象 Key、前缀和数据库引用，不批量删除整个目录。
5. 删除确认无人引用的图片，记录处理结果；不能确认的对象暂时保留。

不对整个 avatars/ 配置按年龄无条件删除的生命周期规则，避免删掉仍在使用的头像。本版允许少量遗留图片等待人工处理，不承诺服务重启后自动清理。

### 11.3 地址特性

- avatar_url 由公共域名拼接而成，永久有效，无签名、无过期时间；地址失效只可能是对象被删除或网络故障。
- 对象不可变：每次上传生成新 Key，旧地址与旧对象一一对应，可放心配置长缓存。
- 前端图片加载失败直接回退默认头像，不需要刷新地址或重试机制。
- 更换绑定域名只需修改 aliyun.oss.public-base-url 配置并重启，不改数据库、不改对象。

---

## 12. 静态前端实施

### 12.1 HTML 控件

建议采用独立 ID：

| ID | 用途 |
| --- | --- |
| profileBtn | 打开资料 |
| profileModal | 资料弹窗 |
| profileForm | 昵称、邮箱表单 |
| profileNickname | 昵称 |
| profileEmail | 邮箱 |
| profileSubmit | 保存资料 |
| profileError | 资料错误 |
| avatarFile | 隐藏的文件输入 |
| avatarChoose | 选择图片 |
| avatarPreview | 待上传预览 |
| avatarUpload | 确认上传 |
| avatarCancel | 取消选择 |
| avatarRemove | 恢复默认 |
| avatarError | 头像错误 |
| registerOptionalFields | 仅注册模式显示的可选资料区域 |
| registerNickname | 注册昵称 |
| registerEmail | 注册邮箱 |
| registerAvatarFile | 注册头像文件输入 |
| registerAvatarChoose | 注册时选择本地图片 |
| registerAvatarPreview | 注册头像预览 |
| registerAvatarCancel | 取消注册头像选择 |
| registerAvatarError | 注册头像选择错误 |
| registerAvatarRetry | 账号已创建后仅重试头像 |
| registerAvatarSkip | 账号已创建后跳过头像 |

文件输入：

~~~html
<input
  id="avatarFile"
  type="file"
  accept="image/jpeg,image/png"
  hidden
>
~~~

accept 仅改善选择器体验，不替代后端校验。

资料表单输入：

~~~html
<input id="profileNickname" type="text" maxlength="50">
<input id="profileEmail" type="email" maxlength="254">
~~~

两者均不加 required。默认头像 SVG 使用固定静态内容，不拼接用户输入。

注册模式追加独立的可选字段区，不能与个人资料弹窗复用相同 ID：

~~~html
<div id="registerOptionalFields" hidden>
  <label for="registerNickname">昵称（可选）</label>
  <input id="registerNickname" type="text" maxlength="50">

  <label for="registerEmail">邮箱（可选）</label>
  <input id="registerEmail" type="email" maxlength="254">

  <input id="registerAvatarFile" type="file"
         accept="image/jpeg,image/png" hidden>
  <button id="registerAvatarChoose" type="button">选择头像（可选）</button>
  <button id="registerAvatarCancel" type="button" hidden>取消选择</button>
  <img id="registerAvatarPreview" alt="注册头像预览" hidden>
  <p id="registerAvatarError" class="field-error" hidden></p>
</div>
~~~

该区域只在注册模式显示。现有 authForm 带 novalidate，需要在提交逻辑主动调用邮箱输入框的 checkValidity() 并显示错误，不能误以为 type="email" 会自动阻止提交；后端仍进行最终校验。

### 12.2 request() 支持 FormData

保留原有认证头、AbortController、响应解包、错误处理，仅改变请求体编码分支：

~~~javascript
const isFormData = body instanceof FormData;

if (body !== undefined && !isFormData) {
  headers["Content-Type"] = "application/json";
}

const requestBody =
  body === undefined
    ? undefined
    : isFormData
      ? body
      : JSON.stringify(body);
~~~

fetch 使用 requestBody。

FormData 分支不要手动设置 Content-Type，浏览器会生成 multipart boundary。已有 JSON 接口必须继续按原方式工作。

为区分注册被明确拒绝与网络结果不确定，HTTP 错误对象增加 httpStatus 属性，值取实际响应状态；保留原来的 message 和 err.auth。网络中断、超时等无 HTTP 响应的错误不能伪造 409 或当作“未创建账号”。注册请求不配置自动重发。

### 12.3 API 方法

在 API IIFE 内增加：

~~~javascript
// 替换原 register 定义，保留两参数旧调用的兼容性。
function register(username, password, profile = {}) {
  return request("/auth/register", {
    method: "POST",
    body: {
      username,
      password,
      nickname: profile.nickname == null ? null : profile.nickname,
      email: profile.email == null ? null : profile.email
    }
  });
}

function updateMe(profile) {
  return request("/auth/me", {
    method: "PUT",
    body: {
      nickname: profile.nickname,
      email: profile.email
    }
  });
}

function uploadAvatar(file) {
  const form = new FormData();
  form.append("file", file);

  return request("/auth/me/avatar", {
    method: "POST",
    body: form,
    timeout: 30000
  });
}

function removeAvatar() {
  return request("/auth/me/avatar", {
    method: "DELETE"
  });
}
~~~

register 替换现有定义，updateMe、uploadAvatar、removeAvatar 加入 API 最后的返回对象，否则 app.js 无法调用。register 的 profile 参数没有头像字段，File 只传给 uploadAvatar。

### 12.4 展示和回填

~~~javascript
function getDisplayName(user) {
  const nickname =
    typeof user.nickname === "string" ? user.nickname.trim() : "";
  return nickname || user.username;
}

function normalizeOptional(value) {
  const normalized = value.trim();
  return normalized === "" ? null : normalized;
}
~~~

展示：

~~~javascript
userName.textContent = getDisplayName(state.user);
~~~

打开弹窗时回填真实字段：

~~~javascript
profileNickname.value = state.user.nickname || "";
profileEmail.value = state.user.email || "";
~~~

不要把昵称展示回退值填回输入框。

### 12.5 前端状态

建议局部维护：

~~~javascript
const profileState = {
  pendingFile: null,
  previewUrl: null,
  saving: false,
  uploading: false,
  removing: false,
  sessionGeneration: 0
};
~~~

- 三种写操作在当前页面串行进行，避免乱序响应覆盖。
- 登录身份变化或失效时增加 sessionGeneration。
- 写请求开始时记录会话代次，返回后不匹配则丢弃界面回写。
- 头像地址永久有效，无地址刷新代次；图片加载失败直接回退默认头像。
- 关闭弹窗前清理 pendingFile 和 previewUrl；已上传头像不撤销。

### 12.6 本地预览

1. 点击选择按钮，触发 avatarFile.click()。
2. change 时读取一个 File。
3. 检查大小和基础类型，显示明确错误。
4. 释放旧 previewUrl。
5. 使用 URL.createObjectURL(file) 创建预览。
6. 保存 pendingFile。
7. 用户取消、选新图片或上传成功时，释放已不使用的对象 URL。
8. 清空 file input 的 value，允许再次选择同一个文件。

~~~javascript
const previewUrl = URL.createObjectURL(file);
// 将地址用于预览。
URL.revokeObjectURL(previewUrl); // 仅在预览不再使用时调用。
~~~

不要刚赋值给 img 就立即 revoke，以免影响加载。

### 12.7 上传与保存行为

上传：

1. 确认有 pendingFile。
2. 检查没有其他写请求。
3. 设置 uploading 和加载状态。
4. 调用 API.uploadAvatar(pendingFile)。
5. 会话仍有效时更新 state.user，刷新导航及当前头像。
6. 保留昵称、邮箱输入框未保存草稿。
7. 释放预览和 pendingFile。
8. toast 提示“头像已更新”。
9. 普通失败保留待上传文件并提示。
10. 超时、断网等结果不确定时，重新查询 /auth/me 确认当前状态，不盲目自动重复上传。
11. finally 恢复按钮状态。

资料保存：

1. 读取并规范化昵称、邮箱。
2. 基础校验后调用 API.updateMe。
3. 成功更新 state.user 和导航。
4. 失败保留输入。
5. 不提交、不清空头像字段。

移除：

1. 调用 API.removeAvatar。
2. 成功更新 state.user 并显示默认头像。
3. 清理待上传预览，避免默认头像与待上传图片混淆。
4. 重复移除视为正常成功。

### 12.8 弹窗与样式

- 复用已有 modal-overlay、field-error、toast 风格。
- 不直接调用账单专用 clearErrors() 和 setSubmitting()。
- 资料表单、头像区域分别维护错误提示。
- 可使用现有 showError(el, msg)、hideError(el) 通用函数。
- 打开时聚焦合理控件，关闭后恢复到 profileBtn。
- 处理 Esc、遮罩点击和关闭按钮的一致性。
- 正在写请求时暂时阻止关闭，避免“取消已取消上传”的误解。
- 保持 body 滚动锁与现有其他弹窗一致。
- 移动端按钮可换行，长昵称不挤压退出按钮。
- 不把邮箱放到导航栏，资料弹窗中查看即可。

### 12.9 注册提交编排与恢复

现有 authForm 的提交处理必须按阶段改写，不能继续用一个 try/catch 把注册、登录、上传的所有失败都显示成“注册失败”。登录模式仍按原有 API.login → enterApp 执行，不读取注册专用字段。

注册流程独立保存以下临时状态，与 profileState 分开：

~~~javascript
const registrationState = {
  phase: "idle",
  pendingFile: null,
  previewUrl: null,
  registeredUserId: null,
  registeredUsername: null,
  generation: 0
};
~~~

不在该对象、localStorage 或 sessionStorage 保存密码。密码只用于本次提交及紧接着的自动登录，结束后清空输入框和可释放的引用；登录失败需要用户重新输入。

阶段和允许操作：

| phase | 含义 | 允许操作 |
| --- | --- | --- |
| idle | 尚未发送注册或上次被明确拒绝 | 编辑、选择图片、提交 |
| registering | 注册请求中 | 禁止重复提交和切换账号 |
| registration_unknown | 注册结果无法确认 | 转登录确认，不自动重发注册 |
| registered | 已收到注册成功响应 | 只继续登录，不重复注册 |
| logging_in | 自动登录中 | 等待结果 |
| login_required | 账号已创建但登录未完成 | 手动登录该账号或放弃续传 |
| uploading_avatar | 已登录，上传头像中 | 等待结果，不重复建号或登录 |
| avatar_failed | 账号已创建，头像未确认成功 | 确认当前状态后仅重试头像或跳过 |
| complete | 注册流程结束 | 进入主界面 |

提交算法：

1. 在 idle 状态从用户名、密码、昵称、邮箱和 pendingFile 捕获一份本次提交快照；请求期间不随表单继续变化。
2. 对昵称、邮箱执行与资料编辑一致的空值规范化和长度检查；前端检查邮箱格式。对图片检查大小、支持类型并尝试本地解码；后端仍是最终校验者。
3. 调用 API.register(username, password, {nickname, email})。发生明确的 409/422 等拒绝时回到 idle，保留可修改的输入和头像预览，不登录、不上传。
4. 收到成功响应立即记录 data.id、data.username，将 phase 改为 registered。此后不允许同一流程再调用注册接口。
5. 调用 API.login(username, password)，成功后确认令牌已保存。只有这个阶段成功，才可以显示“已登录”。
6. 调用 API.me() 并核对当前用户 ID 等于 registeredUserId。若不一致或会话已切换，停止续传，不把待上传头像应用到其他账号。
7. 有 pendingFile 时调用 API.uploadAvatar(pendingFile)；没有则直接进入 complete。
8. 上传成功后使用返回资料更新当前状态，释放 blob URL、File 引用和密码输入，进入主界面。
9. 进入主界面的数据加载失败应提示“加载失败，可重试”，不能回到注册步骤或撤销已成功的账号创建。

失败及恢复规则：

- **注册失败且明确未创建：** 409/422 等按业务错误处理，保留输入供修改；不得先尝试上传头像。
- **注册响应超时、连接中断或提交结果不确定：** 进入 registration_unknown，提示“注册结果暂无法确认，请尝试登录确认”。不得自动重发，也不假定账号不存在；未取得可靠用户 ID 的头像不自动续传到随后登录的账号，可在确认登录后由用户重新选择或明确确认上传。
- **注册成功、自动登录失败：** 提示“账号已注册成功，请登录后继续设置头像”。保留当前页面内的待上传 File 和已注册用户 ID，不保存密码；登录后只有 ID 匹配才允许用户确认续传。
- **注册成功、头像被后端拒绝：** 显示具体图片错误，账号、昵称和邮箱均保留；允许重新选图后仅调用上传接口，或直接跳过。
- **上传超时或响应丢失：** 先通过 /auth/me 查询当前头像，展示实际结果；不能直接断言上传未成功，更不能重新注册。若用户仍选择重试，则是已登录的头像替换操作。
- **头像失败后刷新或关闭页面：** File 和 blob URL 只在内存中，无法保证恢复；账号和已保存资料仍存在，用户登录后重新选择图片即可。
- **用户切换到其他账号或退出：** 增加 generation 并释放注册图片、用户 ID 等状态，任何旧异步结果不得继续上传或更新界面。

成功提示按实际阶段显示：无头像时为“注册成功，已登录”；头像上传完成时为“注册成功，头像已设置”；跳过上传时为“注册成功，可稍后设置头像”。移除现有在登录请求完成前就出现的“注册成功，已自动登录”提示。

### 12.10 两处头像控件共用逻辑

注册页与个人资料页复用文件大小、类型、预览释放和上传 API，但状态分开保存，避免相互覆盖。注册页在提交时自动上传，个人资料页仍使用“确认上传”按钮。共享校验规则不意味着提前开放匿名 OSS 上传。

---

## 13. 异常处理和信息边界

新增或完善以下处理：

| 异常 | 对外结果 |
| --- | --- |
| MaxUploadSizeExceededException | HTTP 413，文件超限 |
| 缺少 multipart file | HTTP 422 |
| 非 multipart 请求调用上传接口 | 按项目契约统一 HTTP 422 |
| 文件大小或类型校验失败，以 BusinessException 表达 | HTTP 422，明确规则原因 |
| 必要 OSS 上传失败，记录原始异常后转换为 BusinessException | HTTP 503，稍后重试 |
| 清理旧对象失败 | 记录日志供人工清理，不修改已成功响应 |
| 认证失效 | HTTP 401 |
| 其他未预期异常 | HTTP 500 |

复用现有 BusinessException，不新增头像专用异常类。固定错误码和提示集中放在 ResultCode，例如 AVATAR_INVALID（422）、AVATAR_TOO_LARGE（413）、AVATAR_STORAGE_UNAVAILABLE（503）；规则细节可以通过现有 code/message 构造器提供。GlobalExceptionHandler 继续统一处理 BusinessException，仅补充框架 multipart 异常和适用于 JSON、multipart 各端点的媒体类型提示。

AuthServiceImpl 的 OSS 私有方法只把预期的 SDK/网络失败转换为存储业务错误，捕获处记录原始异常及 Request ID，不把任意编程错误都包装成 503。头像编排按阶段处理异常：上传失败向外返回；旧图删除失败仅记录日志；UPDATE 失败时记录已上传的 newKey 供人工清理，不改写旧关联。复用异常类不等于用一个 catch 统一处理所有阶段。

在 Spring multipart 解析、Servlet 容器和未来反向代理三层都可能出现大小限制。实际部署时验证最终响应，不能只测试 Controller 内 file.getSize()。

日志建议记录：

- 操作 ID。
- 用户 ID。
- 对象 Key。
- 图片处理阶段。
- OSS Request ID。
- 异常类型、删除结果及 AVATAR_DELETE_FAILED 事件标识。

日志不记录：

- AccessKey 或 Secret。
- 用户上传的完整二进制。
- 邮箱等不必要的用户资料。

---

## 14. 测试计划

### 14.1 DTO 与 Controller

- [ ] 合法昵称、邮箱保存成功。
- [ ] 空字符串和空白转为 NULL。
- [ ] nickname 50 以内通过，超限拒绝。
- [ ] email 长度超限或格式非法拒绝。
- [ ] 两个用户使用相同昵称、邮箱允许保存。
- [ ] PUT 请求包含 avatar_url、user_id 等未知字段拒绝。
- [ ] GET/PUT/上传/删除响应均使用 avatar_url。
- [ ] 仅用户名、密码注册仍成功，三个可选响应字段均为 null。
- [ ] 注册填写昵称、邮箱时响应返回真实值，注册响应中的 avatar_url 此时为 null。
- [ ] 注册时可单独填写昵称、单独填写邮箱、同时填写或全部不填。
- [ ] 注册昵称、邮箱的空白、长度和格式规则与登录后资料修改一致。
- [ ] 注册用户名、密码原有约束及 BCrypt 72 字节限制继续有效。
- [ ] 注册提交 avatar_url、avatar_object_key、user_id 等未知字段返回 422。
- [ ] 注册响应不发 token，密码和密码哈希均不返回。
- [ ] 不返回密码哈希和 Object Key。
- [ ] multipart 缺失、空文件、多文件、超限状态正确。
- [ ] 头像端点先完成 authService.replaceAvatar/removeAvatar 变更，再调用 authService.getCurrentUser() 返回完整资料；变更失败时不继续查询成功响应。

现有 AuthControllerWebTest mock 了认证拦截器为直接放行。它不能单独证明认证有效，认证测试必须另行覆盖真实拦截器链路。

MockMvc 示例：

~~~java
@Test
void updateProfileRejectsInvalidEmail() throws Exception {
    mockMvc.perform(put("/auth/me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"小飞\",\"email\":\"invalid\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value(422));
}
~~~

测试需增加 put 的静态导入，并使用现有测试上下文。头像相关用例继续基于现有 mock AuthService 模拟 void 写操作和 getCurrentUser() 的结果；使用调用顺序断言（如 Mockito InOrder）验证先写后读，不新增其他 Service mock。

注册 DTO 校验示例，加入现有 AuthControllerWebTest：

~~~java
@Test
void registerRejectsInvalidOptionalEmail() throws Exception {
    mockMvc.perform(post("/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"reed\","
                            + "\"password\":\"examplePassword123\","
                            + "\"nickname\":\"小飞\","
                            + "\"email\":\"invalid\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value(422));
    verify(authService, never()).register(any());
}
~~~

该示例需要引入 Mockito 的 verify、never 及现有 any。成功请求还应通过 ArgumentCaptor<RegisterDTO> 检查 Controller 传给 Service 的 nickname、email，避免只断言 mock 返回值而漏掉请求绑定错误。

至少增加两个 Service 或测试库用例：注册带 nickname、email 的用户后查询数据库检查真实值；注册非法可选资料时确认没有新增用户。仅有 MockMvc 的成功响应断言不能证明资料已入库。

### 14.2 Service 与编排

- [ ] 用户 ID 来自 UserContext。
- [ ] 注册插入用户时一并保存 nickname、email，avatar_object_key 初始为 NULL。
- [ ] 注册失败不残留半成品用户，昵称、邮箱不做唯一性查询。
- [ ] 注册成功后的头像接口只使用新账号的有效令牌定位用户。
- [ ] 资料更新仅更新 nickname、email。
- [ ] 头像更新仅更新 avatar_object_key。
- [ ] 昵称、邮箱显式 NULL 确实落库。
- [ ] 原样重复保存成功。
- [ ] 数据库关联更新成功后才尝试删除旧对象。
- [ ] replaceAvatar、removeAvatar 方法上没有 @Transactional；OSS 调用不处于数据库事务中。
- [ ] 上传失败不修改关联。
- [ ] UPDATE 失败时记录已上传的新对象 Key 供人工清理，且不改写旧关联。
- [ ] 旧对象删除失败不影响新头像或恢复默认的成功结果。
- [ ] 恢复默认重复调用成功。
- [ ] toVO 拼接地址使用配置域名与数据库 Key，纯字符串、无网络调用。
- [ ] service 单测结束清理 UserContext，避免污染后续测试。

### 14.3 文件校验

使用受控测试样本，覆盖 AuthServiceImpl 的校验私有方法：

- [ ] 正常 JPEG、PNG 通过。
- [ ] 文件头与扩展名不一致（如 GIF 改名为 .jpg）拒绝。
- [ ] 非图片或任意二进制内容拒绝。
- [ ] SVG、GIF、WebP、HEIC 拒绝。
- [ ] 空文件、缺失文件拒绝。
- [ ] 2 MiB 边界值（恰好等于、超出 1 字节）判定正确。
- [ ] 魔数判断只读文件头字节，不依赖扩展名与 Content-Type。

### 14.4 存储与清理

AuthServiceImpl 的普通单元测试直接 mock OSS 客户端（Mockito 支持具体类），不依赖真实 OSS。响应转换测试覆盖空头像与非空 Key，断言拼接地址使用配置域名与数据库 Key。

真实 OSS 集成测试显式启用，使用专用测试 Bucket 或隔离测试前缀，并执行：

1. 上传原始文件字节。
2. 验证公共读地址（无签名）可被浏览器直接 GET 访问。
3. 验证返回 Content-Type 与文件内容。
4. 验证匿名写入（无凭证 PUT）被拒绝。
5. 删除测试对象。
6. 检查测试未遗留文件。
7. 模拟删除失败，验证日志包含人工处理所需信息，且不误报已提交操作失败。

不能把不稳定的外网请求放入每次运行的普通单元测试。

### 14.5 浏览器验收

| 场景 | 预期 |
| --- | --- |
| 老用户登录 | 用户名、默认头像 |
| 仅填写用户名、密码注册 | 注册并自动登录，默认昵称与头像 |
| 注册时填写昵称、邮箱 | 首次进入系统即展示昵称，资料中邮箱已保存 |
| 注册时仅选择头像 | 一次提交后自动上传，昵称回退用户名，邮箱为空 |
| 注册时三项全部填写 | 首次进入系统三项资料与输入一致 |
| 注册前取消已选头像 | 正常建号，不调用上传接口 |
| 注册资料格式不合法 | 不创建账号，不登录、不上传 |
| 注册图片前端校验不通过 | 保留表单并提示，不发送注册请求 |
| 注册用户名冲突 | 显示 409 对应提示，头像不上传 |
| 注册成功、自动登录失败 | 明确账号已创建，只继续登录 |
| 注册成功、头像上传失败 | 账号及文本资料保留，仅重试头像或跳过 |
| 注册结果不确定 | 提示登录确认，不自动重新注册 |
| 自动登录尚未完成 | 不显示“已登录”，不调用上传接口 |
| 注册过程中切换会话 | 待上传图片不应用到其他账号 |
| 注册模式切回登录模式 | 隐藏注册可选控件，不把其值混入登录请求 |
| 设置昵称 | 导航立即显示昵称 |
| 清空昵称 | 显示用户名 |
| 填写/清空邮箱 | 保存后刷新一致 |
| 选择图片 | 本地预览，不产生上传 |
| 取消选择 | 数据库与 OSS 不变 |
| 上传成功 | 导航与弹窗同步更新 |
| 关闭后重新打开 | 已上传头像保留 |
| 头像上传时有未保存昵称 | 草稿不被覆盖 |
| 恢复默认 | 当前关联为空，显示默认图标 |
| 上传失败 | 旧头像保持，待上传文件可重试 |
| 连续点击 | 单页面没有重复写请求 |
| 网络超时 | 查询状态确认，不误报必然失败 |
| 头像地址失效（对象被删） | 回退默认头像，无刷新重试循环 |
| 上传过程中登录失效 | 返回登录，不回写旧用户状态 |
| 手机照片 | 浏览器按 EXIF 自动摆正方向，预览裁切合理 |
| 刷新和重新登录 | 资料持久化正确 |
| 浏览器 Network | JWT 与 multipart 正确，无 OSS 密钥 |

静态前端目前没有测试框架，可用人工浏览器验收与 Network 面板完成第一版验证，无需引入新构建工具。

### 14.6 后端执行方式

在项目约定的 JDK 8 和 Maven 3.8.6 环境下，从 backend 目录执行：

~~~powershell
$projectJdk8 = "C:\fly_develop\Java\temurin-jdk8u504-b01"
$env:JAVA_HOME = $projectJdk8
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" -version
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" test
~~~

先确认上述本地路径仍有效。不修改全局 JAVA_HOME。数据库与 OSS 集成测试使用隔离资源并显式启用，不拿个人实际账单数据做破坏性测试。

---

## 15. 分阶段实施清单

### 阶段 A：契约和迁移

- [ ] 确认实际代码与本文基线差异。
- [ ] 固定字段、空值、PUT 整体替换两字段的语义。
- [ ] 固定注册 JSON 可选字段及“注册 → 登录 → 可选头像上传”的契约与部分失败语义。
- [ ] 编写版本化迁移 SQL。
- [ ] 在测试库仅给 fly_user 新增三列，不新增清理表。
- [ ] 同步初始化结构和接口文档。
- [ ] 验证老用户和账单数据完整。

交付：可重复说明、可追踪执行的数据库升级方案。

### 阶段 B：注册与资料读写

- [ ] 编写 DTO、Controller 校验测试。
- [ ] 扩展 RegisterDTO 的 nickname、email，保留现有用户名、密码校验。
- [ ] 注册 Service 在创建用户时一并保存可选昵称和邮箱。
- [ ] 新增实体属性、VO 字段与 UserProfileUpdateDTO，扩展现有 AuthServiceImpl.toVO()；公共域名拼接在本阶段一并实现，纯字符串操作，无 SDK 依赖。
- [ ] 实现 nickname、email 更新。
- [ ] 验证 NULL 清空、重复邮箱、原样保存。
- [ ] 更新旧 UserVO 构造器测试。
- [ ] 运行认证和资料相关测试。

交付：昵称、邮箱可在注册时保存，登录后读取、修改和清空；旧的两字段注册请求仍兼容。

### 阶段 C：文件校验、OSS 与头像接口

- [ ] 配置受限 OSS 写身份和实际 Bucket 参数，Bucket 设为公共读。
- [ ] 实现 OssConfig：@Value 读取配置，创建并管理 SDK 客户端生命周期。
- [ ] 固定 OSS SDK 依赖并编译，不引入图片处理库。
- [ ] 在 AuthServiceImpl 实现文件大小与文件头魔数校验私有方法。
- [ ] 实现上传、删除私有方法与 replaceAvatar、removeAvatar 编排：上传 → 单条 UPDATE 关联 → 尽力删旧对象；无显式事务、无行锁。
- [ ] 增加上传、删除接口及测试，校验 multipart 文件数量与额外字段。
- [ ] 复用 BusinessException、ResultCode，补充 multipart 异常映射和必要错误码。
- [ ] UPDATE 失败时记录已上传对象 Key；旧对象删除失败记录 AVATAR_DELETE_FAILED 日志。
- [ ] 完成文件校验单测和隔离 OSS 集成验证。

交付：后端可接收头像文件，存入公共读 OSS 并提供永久地址，头像替换与恢复默认可运行，遗留图片可按文档人工核查。

### 阶段 D：静态页面

- [ ] 注册表单增加可选昵称、邮箱及头像选择预览，仅注册模式显示。
- [ ] 扩展 API.register 的可选资料参数并保留两参数旧调用。
- [ ] 实现注册、自动登录、可选头像上传的阶段状态和独立错误处理。
- [ ] 实现账号已创建后的仅登录、仅上传重试与跳过，禁止重复建号。
- [ ] 修正过早显示“已自动登录”的成功提示。
- [ ] 增加导航头像与个人资料按钮。
- [ ] 增加资料表单和本地图片选择控件。
- [ ] 修改 request() 的 FormData 分支并回归 JSON 接口。
- [ ] 实现预览与对象 URL 释放。
- [ ] 实现独立上传、移除、保存状态。
- [ ] 实现默认头像回退和会话代次保护。
- [ ] 验证手机宽度与弹窗键盘操作。

交付：注册时可选填写三项资料，以及登录后完整可用的个人资料界面。

### 阶段 E：联调与交付

- [ ] 运行必要后端测试。
- [ ] 用两个用户验证资料隔离。
- [ ] 验证注册可选字段所有组合，以及登录、上传分别失败时的恢复行为。
- [ ] 验证旧两字段注册请求、登录、分类、账单功能。
- [ ] 逐项完成浏览器验收。
- [ ] 检查无 OSS 凭证被提交或下发。
- [ ] 记录数据库版本、OSS 配置项和清理操作。
- [ ] 明确尚未执行或未通过的测试，不把代码片段当作测试结果。

交付：具备测试证据、操作说明和回退方案的功能版本。

---

## 16. 发布、回退与运行维护

### 16.1 发布顺序

1. 备份并执行兼容性数据库迁移。
2. 准备 OSS 配置与凭证。
3. 发布 Java 后端。
4. 验证资料查询、上传、公共地址展示、删除。
5. 发布静态前端。
6. 执行核心验收。
7. 检查删除失败日志、其他异常和存储遗留。

### 16.2 回退

- 优先回退应用版本。
- 新增可空列一般可暂时保留，不立即 DROP。
- 回退静态页面不会回滚已上传头像。
- 回退时等待在途头像操作结束；人工清理前重新核对当前数据库引用。
- 不通过恢复旧数据库覆盖后续新产生的账单。
- 真正删除新列或 OSS 对象前，单独核对数据和影响范围。

### 16.3 运行维护

- 按需查看 AVATAR_DELETE_FAILED 日志，人工核对并清理遗留图片。
- 监控头像上传失败率、413/422 分布和 OSS 错误。
- 记录人工核查和删除结果。
- 实际部署有反向代理时同步核对上传大小与超时。
- 更换访问域名后只改 aliyun.oss.public-base-url 配置，不改用户表 Object Key。
- 轮换凭证时验证上传与清理权限。
- 公共读 Bucket 中所有对象均可被持有地址者读取；如需收紧为私有读，需重新引入签名地址方案（见文首 v1.3 版本记录）。

---

## 17. 最终验收定义

完成必须同时满足：

1. 昵称可重复、可修改、可清空；为空时显示用户名。
2. 邮箱仅做格式与长度校验，可重复、可清空。
3. 用户通过本地文件选择设置头像，不存在 URL 输入框。
4. 图片经过服务端大小与文件头类型校验后，原始字节存入公共读阿里云 OSS。
5. 数据库保存 avatar_object_key，接口返回 avatar_url。
6. 默认头像、上传预览、立即生效和恢复默认行为清楚一致。
7. 用户只能修改自己的资料和头像。
8. 资料保存与头像保存互不覆盖。
9. 关联更新失败时记录已上传对象 Key 供人工清理；旧图删除失败记录日志，具备人工核查步骤，不依赖清理表或后台自动重试。
10. 头像地址永久有效，无签名刷新逻辑；图片加载失败回退默认头像。
11. 老用户、注册登录和原有记账功能正常。
12. 完成必要测试，并如实记录验证结果。
13. 未查阅、未修改 Vue 前端。
14. 注册页支持可选昵称、邮箱和本地头像，一次点击完成注册、自动登录和可选上传，用户无需到资料页重新选择。
15. RegisterDTO、注册 Service、注册响应和前端请求覆盖昵称、邮箱；头像通过新账号的认证上传接口保存。
16. 账号创建后若登录或头像失败，准确提示完成阶段，只重试失败阶段，不重复注册或删除已创建账号。
17. 新增生产 Java 文件按第 8.2 节收敛为 2 个；资料与头像操作复用 AuthService/AuthServiceImpl、响应转换和业务异常机制，头像关联通过单条 UPDATE 更新，无 TransactionTemplate、行锁与独立存储服务。

---

## 18. 已核验的参考资料

本地代码是项目行为的首要依据；官方文档用于核验依赖 API 和云服务规则。

- [MyBatis-Plus 条件构造器与 NULL 更新](https://baomidou.com/guides/wrapper/)
- [Hibernate Validator 6.1 参考指南](https://docs.jboss.org/hibernate/validator/6.1/reference/en-US/html_single/)
- [阿里云 OSS Java SDK](https://www.alibabacloud.com/help/en/oss/developer-reference/oss-java-sdk/)
- [OSS Java 简单上传](https://www.alibabacloud.com/help/en/oss/developer-reference/simple-upload-11)
- [OSS 权限与访问控制](https://www.alibabacloud.com/help/en/oss/user-guide/permissions-and-access-control-overview)
- [固定地址与临时签名地址](https://www.alibabacloud.com/help/en/oss/use-a-fixed-file-url-to-access-a-file)
- [OSS 访问域名与网络](https://www.alibabacloud.com/help/en/oss/user-guide/access-and-network-overview)

SDK 版本、云端访问限制和控制台配置以实际实施时的官方文档与账号状态为准；本文没有创建 Bucket、配置 RAM、执行迁移或调用真实上传。

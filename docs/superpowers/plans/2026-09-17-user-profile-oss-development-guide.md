# 用户资料与阿里云 OSS 头像上传开发指导书

**版本：** 1.3  
**日期：** 2026-09-17  
**项目：** Pocket Ledger Java  
**文档性质：** 待实施的设计与开发指导。本文中的 SQL、代码片段和命令未因编写本文而执行，不代表功能已经实现。

> 实施者须知：按本文逐项开发和验证，执行工作可参考本地 executing-plans 技能。遵守项目 AGENTS.md：子代理仅用于探索、检索和核验，代码修改、方案取舍及最终验证由主代理负责。不得查阅或修改 frontend_vue。

**目标：** 注册时支持可选填写昵称、邮箱和选择本地图片作为头像；登录后支持查看、修改、清空资料，以及替换头像、恢复默认头像。

**架构：** 静态前端调用 Java 后端。后端负责认证、参数与图片校验，将头像存入私有阿里云 OSS，并将稳定的对象标识写入 MySQL。查询用户资料时生成临时签名地址，以 avatar_url 返回前端。

**后端拆分：** 本版新增 6 个生产 Java 文件，复用现有认证服务、响应转换和业务异常机制。头像关联在 AvatarService 内使用 TransactionTemplate 管理短事务；不增加存储接口、独立事务服务、响应组装器或头像专用异常类。需求、接口与页面交互保持不变。

**技术栈：** 原生 HTML/CSS/JavaScript、Java 8、Spring Boot 2.3.0.RELEASE、MyBatis-Plus 3.4.3.4、MySQL 8、阿里云 OSS Java SDK。

**需求依据：** 用户已明确注册时也可选填写昵称、邮箱和上传头像；昵称可重复、可修改且为空时显示用户名；头像通过选择本地图片上传，云端存储使用阿里云；邮箱仅校验格式，不用于登录、验证或密码找回。第一版不建立文件清理任务表。本文件合并设计说明、接口契约、实施计划和验收清单，作为本次实施的完整依据。

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
- 不为已有数据重跑包含 DROP TABLE 的初始化脚本。
- 第一版不建立文件清理任务表，不实现清理调度或后台自动重试；删除失败记录日志，后续按需人工清理。

### 1.3 第一版产品决策

- 注册和登录后的资料编辑都支持昵称、邮箱，均可选、允许重复。
- 注册页的头像在账号创建并登录成功后自动上传；账号已创建但头像上传失败时保留账号，明确提示部分完成，并提供仅重试头像或暂时跳过的操作。
- 登录后的头像独立上传并立即生效；登录后的昵称、邮箱通过“保存资料”生效。
- 头像使用私有 OSS 存储，接口返回临时签名访问地址。
- 默认头像使用静态页面中的固定 SVG 图标。
- 图片不提供手动裁剪器；前端使用居中裁切预览，服务端等比例缩小，最终圆形头像由 CSS 展示。
- 头像 JPG/JPEG、PNG 最大 2 MiB；不支持 GIF、SVG、WebP、HEIC。
- 单边不超过 4096 像素，总像素数不超过 16,000,000。
- 输出图片最大边为 512 像素，小图不放大。

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

### 3.2 头像存储与展示分离

| 层次 | 字段 | 用途 |
| --- | --- | --- |
| MySQL | avatar_object_key | 保存稳定 OSS 对象标识 |
| Java User | avatarObjectKey | 实体映射 |
| Java UserVO | avatarUrl | 本次可访问的签名地址 |
| JSON | avatar_url | 前端图片展示 |
| 静态前端 | state.user.avatar_url | 当前头像地址 |

对象标识示例：

~~~text
avatars/42/4e94ae5336584d22af22cdac197a4280.png
~~~

规则：

- 数据库不保存临时签名 URL。
- 数据库不保存图片二进制、Base64 或本机文件路径。
- avatar_object_key 为 NULL 时，avatar_url 返回 null。
- avatar_url 是后端生成的只读响应字段。
- 普通资料更新请求不接受 avatar_url、avatar_object_key。
- 不把内部 avatar_object_key 暴露为用户可任意提交的字段。
- 不将用户名或默认头像地址写入数据库作为“默认资料”。

这是对早期 avatar_url 数据库字段方案的正式修订。数据库使用 avatar_object_key，接口仍保留用户熟悉的 avatar_url。

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
- 加载失败先按第 11 节规则处理签名刷新，再回退默认头像。
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
    "avatar_url": "https://avatar.example.com/avatars/42/example.png?signature=example",
    "email": "reed@example.com"
  }
}
~~~

示例域名和签名是说明值，不可直接访问。运行时使用实际 Bucket、访问域名和 SDK 生成的完整签名。

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
- 不向 JWT 增加昵称、邮箱和签名 URL。
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
| 422 | 邮箱格式、长度、文件缺失、损坏图片、非法格式、尺寸超限等 |
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

服务端日志保留排障信息，但响应不直接透出 SDK 异常、内部路径、签名 URL 或凭证。

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
| 浏览器访问域名 | 浏览器可访问的 HTTPS 域名，必要时绑定自定义域名 |
| 对象前缀 | avatars/ |
| Bucket/Object 权限 | 私有，不开放匿名写入 |
| 访问签名有效期 | 3600 秒 |
| 身份 | 专用 RAM 身份，部署时优先角色凭证 |

私有对象采用签名 URL 访问。签名有效期内持有链接者可读取，签名 URL 不作为永久资料保存或记录到普通日志。

参考：

- [OSS 权限与访问控制](https://www.alibabacloud.com/help/en/oss/user-guide/permissions-and-access-control-overview)
- [固定 URL 与签名 URL](https://www.alibabacloud.com/help/en/oss/use-a-fixed-file-url-to-access-a-file)

### 7.2 权限边界

业务服务仅对目标 Bucket 的 avatars/* 具有必要权限：

- PutObject：上传。
- GetObject：授权读取及签名访问。
- DeleteObject：清理。
- 人工核查遗留文件使用独立维护身份；业务服务不需要为自动巡检申请对象列举权限。

不需要业务服务创建 Bucket、管理账号或列举所有 Bucket。

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
    avatar-prefix: avatars/
    signed-url-expire-seconds: 3600
~~~

aliyun.oss 是本项目新增自定义配置，通过 @ConfigurationProperties 绑定。

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

该版本作为实施候选固定版本；实施时在当前 JDK 8、Spring Boot 依赖树下编译并执行上传、签名、删除测试，不能只凭本文认定已兼容。

配置要求：

- 显式使用 HTTPS 与 V4 签名，设置正确 Region。
- SDK 客户端由 Spring 单例管理，关闭应用时 shutdown。
- 配置有限的连接、读取、连接池等待超时和重试次数。
- 前端上传超时可设 30 秒；后端 OSS 操作预算应短于该值。
- 本地电脑访问公网 Endpoint；不能返回阿里云内网域名给浏览器。
- 签名时使用与最终请求一致的域名，不能生成签名后随意替换 Host。
- 普通 img 展示不要求为了上传开放 OSS 全域 CORS；本方案上传发生在 Java 后端。
- 如默认域名受账号、地域或预览规则限制，绑定自定义 HTTPS 域名并实际验证。

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
| service/AuthService.java | 新增资料更新方法 |
| service/impl/AuthServiceImpl.java | 注册时保存 nickname、email，登录后更新两项资料；扩展已有 toVO()，注入具体 OSS 存储类生成签名地址 |
| controller/AuthController.java | PUT /me、POST /me/avatar、DELETE /me/avatar；头像操作完成后调用现有 AuthService 查询响应资料 |
| mapper/UserMapper.java | 增加按用户 ID 锁行读取方法 |
| common/ResultCode.java | 增加文件超限、图片规则及 OSS 暂时不可用等必要错误码，复用 BusinessException |
| exception/GlobalExceptionHandler.java | 补充 multipart 异常映射，复用现有 BusinessException 处理；修正只提示 application/json 的媒体类型文案 |
| backend/pom.xml | OSS SDK、所选 EXIF 读取库依赖 |
| backend/src/main/resources/application.yml | multipart 与 OSS 配置 |
| sql/pocket_ledger_init.sql | 新环境结构 |
| fronted_static/index.html | 注册可选资料区、导航与资料弹窗 |
| fronted_static/js/api.js | 扩展 register 参数，支持 FormData 与新接口 |
| fronted_static/js/app.js | 注册分阶段提交、两处头像选择预览、渲染和状态处理 |
| fronted_static/css/styles.css | 头像、表单与适配 |

### 8.2 新增 Java 文件：共 6 个

| 文件/类 | 职责 |
| --- | --- |
| dto/UserProfileUpdateDTO.java | 仅接收昵称、邮箱 |
| config/OssProperties.java | 绑定 OSS 配置 |
| config/OssConfig.java | 创建并管理 OSS SDK 单例 Bean，关闭应用时释放客户端 |
| service/OssAvatarStorageService.java | 具体 Spring Service，封装 OSS 上传、签名、删除；直接注入使用，无配套接口或 Impl 类 |
| service/AvatarImageProcessor.java | 内容校验、方向处理、缩放与重编码 |
| service/AvatarService.java | 具体 Spring Service，编排替换、移除、失败补偿；内部用 TransactionTemplate 修改数据库关联 |

另在实施时创建 backend/docs/migrations/2026-09-17-user-profile-oss.sql 增量迁移文件；SQL 与必要测试文件不计入上述生产 Java 文件数量。

EXIF 库应选择支持 Java 8 的固定版本并核验其 API、许可证与依赖。本文不将 JDK ImageIO 描述为能够自动处理全部 EXIF 方向。

避免在 Controller 中写 SDK 和图片处理代码，也避免业务 Service 依赖前端上传文件名生成存储路径。

### 8.3 复用方式与依赖方向

- AuthServiceImpl 保留现有接口与实现结构，继续处理注册、登录和资料读写；已有私有 toVO(User) 是用户响应的统一转换入口。
- AuthServiceImpl 直接注入 OssAvatarStorageService，仅在头像 Key 非空时调用 createReadUrl；不依赖 AvatarService。
- AvatarService 直接注入 UserMapper、AvatarImageProcessor、OssAvatarStorageService 和 PlatformTransactionManager；在构造时创建专用 TransactionTemplate，无需新增事务配置类或辅助 Service。
- AvatarService 的 replaceCurrentAvatar(file)、removeCurrentAvatar() 返回 void，负责完成头像变更和必要清理；不组装 UserVO，也不依赖 AuthService。
- AuthController 先调用 AvatarService 完成变更，再调用 authService.getCurrentUser() 得到完整响应。该查询发生在头像关联事务提交之后。
- 图片校验和必要 OSS 操作失败复用 BusinessException、ResultCode 及全局异常处理；原始 SDK 异常在捕获处记录，清理失败由头像编排单独捕获。
- 不为上述新增具体类再配套建立接口、Impl、工厂或通用文件框架。将来确有第二种存储实现或多个响应模型需要复用时，再评估抽象。

这样保留了配置、图片处理、存储、头像业务各自的职责，同时避免仅为转发调用或少量字段映射增加一层类。

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
3. 否则调用注入的 OssAvatarStorageService.createReadUrl(key)，生成 HTTPS 临时签名地址。
4. 不在 nickname 为空时写入 username；回退由页面负责。
5. 签名生成不需要每次向 OSS 发起图片下载或存在性查询。

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

## 10. 头像图片处理与存储

### 10.1 输入校验

按以下顺序处理，避免在确认尺寸前完整解码任意大图片：

1. 确认是当前登录用户。
2. 确认 multipart 中恰好一个名为 file 的文件，没有额外业务字段。
3. 确认 MultipartFile 不为空。
4. 检查大小：1 至 2,097,152 字节。
5. 从内容识别读取器，仅允许 JPEG、PNG。
6. 先读宽高，要求均大于 0，单边不超过 4096。
7. 用 long 计算 width * height，不超过 16,000,000，避免整数溢出。
8. 解码内容，失败或读取器返回 null 时拒绝。
9. 按 EXIF 方向处理 JPEG，包括镜像方向。
10. 等比例缩小至最大边 512，小图不放大。
11. 重新编码，去除原始附带元数据。
12. 按输出格式设置 MIME 类型和扩展名，再上传。

不因原文件叫 image.jpg 或客户端声称 image/jpeg 就直接放行。不支持的格式即便浏览器可以预览，也由服务端拒绝。

### 10.2 输出规范

- JPEG 输入输出为 JPEG。
- PNG 输入输出为 PNG，可保留透明背景。
- 重新编码使用新像素缓冲和标准元数据，不把原始 EXIF 复制回去。
- JPEG 输出明确转换为可编码的 RGB 色彩类型。
- 设置 image/jpeg 或 image/png。
- 对象保持私有。
- 图片缓存策略采用短于签名有效期的 private 缓存，不将响应配置为公开长期缓存。
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

具体类 OssAvatarStorageService 对外提供以下方法。下列仅列出方法签名；实现时补充 public 方法体，不另建 Java interface：

~~~java
String uploadAvatar(Long userId, byte[] content, String contentType);
String createReadUrl(String objectKey);
void deleteAvatar(String objectKey);
~~~

uploadAvatar 返回 Object Key，不返回并持久化签名 URL。实现负责根据已验证的 contentType 选择后缀。服务端处理后的输出也应有明确大小上限，避免不受限的内存或网络占用。

### 10.4 上传端点

~~~java
@PostMapping(
        value = "/me/avatar",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ApiResponse<UserVO> uploadAvatar(
        @RequestParam("file") MultipartFile file) {
    avatarService.replaceCurrentAvatar(file);
    return ApiResponse.success(
            "头像更新成功",
            authService.getCurrentUser());
}
~~~

此示例展示路由与参数形式；实际端点还需读取 MultipartHttpServletRequest，在现有 Controller 的私有辅助方法中验证文件数量和额外字段，无需再建统一校验器类。不能只靠单个 MultipartFile 参数断言请求只有一个文件；校验应发生在调用 AvatarService 之前。

删除端点：

~~~java
@DeleteMapping("/me/avatar")
public ApiResponse<UserVO> removeAvatar() {
    avatarService.removeCurrentAvatar();
    return ApiResponse.success(
            "已恢复默认头像",
            authService.getCurrentUser());
}
~~~

### 10.5 事务边界

AvatarService 使用 TransactionTemplate，只把锁行和关联更新放入数据库事务。Controller、AvatarService 类及其上传/移除入口不添加覆盖整个流程的 @Transactional，也不从已有事务方法调用这些入口，保证 OSS 上传、删除发生在事务外。

在 AvatarService 构造时用注入的 PlatformTransactionManager 创建专用 TransactionTemplate，保持可写事务。当前调用链由 Controller 直接进入，模板采用默认 REQUIRED 传播即可；无需引入额外事务 Bean、独立事务 Service 或 REQUIRES_NEW 嵌套事务。

上传流程：

1. 校验并处理图片。
2. 上传新对象，取得 newKey。
3. 在 AvatarService 内调用 TransactionTemplate.execute(...)，锁行并更新数据库关联。
4. execute 正常返回后，事务已经提交，取得回调返回的 oldKey。不能在回调内部认为事务已提交。
5. oldKey 非空且不同于 newKey 时，在事务外尝试删除旧对象；删除异常单独捕获并记录日志，不将已成功的头像替换改判为失败。
6. AvatarService 返回后，由 Controller 调用 authService.getCurrentUser() 查询最新资料并返回。删除操作使用有限超时，避免长时间拖延响应。

关联事务：

1. SELECT ... FOR UPDATE 锁定当前用户。
2. 用户不存在则拒绝并回滚。
3. 读取锁定状态下的 oldKey。
4. 仅更新 avatar_object_key = newKey。
5. 回调返回 oldKey，由 TransactionTemplate 完成提交，再将该 Key 返回给外层编排方法；不持久化清理任务。

锁行查询的 SQL 形式：

~~~sql
SELECT id, username, nickname, avatar_object_key, email
FROM fly_user
WHERE id = #{userId}
FOR UPDATE;
~~~

该查询必须位于 TransactionTemplate.execute 的回调中。AvatarService 可以用私有方法复用替换与移除的数据库操作，方法内部显式调用模板，因此不依赖同类自调用的事务代理。

核心结构示例（放在 AvatarService 内；不是完整上传实现）：

~~~java
private String changeAvatarKey(Long userId, String newKey) {
    return transactionTemplate.execute(status -> {
        // selectByIdForUpdate 是本次在现有 UserMapper 增加的锁行查询。
        User user = userMapper.selectByIdForUpdate(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String oldKey = user.getAvatarObjectKey();
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getAvatarObjectKey, newKey));
        return oldKey;
    });
}
~~~

示例中的 userId 由外层从 UserContext 取得并验证，newKey 只来自本次后端上传结果或恢复默认时的 null。返回 null 表示原先没有头像，不代表事务失败。数据库异常须传播给外层补偿逻辑，不能捕获后伪装成成功。

恢复默认头像的关联事务同样锁行，只将 avatar_object_key 设为 NULL，并返回 oldKey。事务提交后在外层尝试删除旧对象，失败只记日志，恢复默认仍然成功。

AvatarService 应将“上传”“数据库关联”“删除旧对象”分段处理；“资料查询与响应组装”由 Controller 随后调用现有 AuthService 完成。不能用一个覆盖整段流程的 catch 统一删除 newKey：数据库已提交后，即使旧对象删除或随后资料查询、响应组装失败，也不能删除已经生效的新头像。

### 10.6 失败补偿

| 失败点 | 处理 |
| --- | --- |
| 图片校验 | 不上传、不改数据库 |
| OSS 上传 | 保留当前关联，返回 503 |
| 新对象上传成功，数据库确定回滚 | 在事务外尝试删除 newKey；失败记录对象 Key、操作 ID 和原因，供人工清理 |
| 数据库提交结果不确定 | 先查询最终关联；无法确认时保留对象并记录日志，不能盲目删除 newKey |
| 数据库成功，响应发送失败 | 视为可能已成功，前端重新获取资料确认 |
| 清理旧对象失败 | 保留新头像成功状态，记录日志，后续按需人工清理 |

本版没有持久化清理队列或自动恢复机制。服务突然退出、网络异常或删除失败时，OSS 可能残留无人引用的图片，后续人工核查处理；不要声称本地事务能保证 OSS 与 MySQL 原子一致。

---

## 11. 直接删除、人工清理与签名地址维护

### 11.1 直接删除与失败日志

1. 使用后端从数据库取得的旧 Key 或本次上传生成的新 Key，不接受前端指定待删除路径。
2. 确认 Key 属于配置的头像前缀，并且不是当前有效头像。
3. 在数据库事务结束后调用 OSS 删除；对象已不存在同样视为成功。
4. 删除失败记录操作 ID、用户 ID、对象 Key、处理阶段、OSS Request ID 和异常原因。
5. 日志使用统一事件标识 AVATAR_DELETE_FAILED，便于后续人工查找。
6. 不创建删除任务，不启动定时扫描或应用级后台重试。

SDK 自身的有限网络重试可以保留，但不等同于持久化清理机制。不在持有数据库锁时等待 OSS 网络调用，旧文件删除失败不能把新头像更新变成失败。

### 11.2 按需人工清理遗留图片

出现删除失败日志或需要核查存储时，由维护人员按以下步骤操作；这不是本版需要开发的自动化功能：

1. 从失败日志取得候选对象 Key；若怀疑服务中断导致日志缺失，可在 OSS 控制台核查 avatars/ 下较早的对象。
2. 优先核查创建超过 24 小时的对象，并确认没有相关上传请求仍在处理中。
3. 查询 fly_user，确认当前没有用户引用候选 Key。
4. 删除前再次核对对象 Key、前缀和数据库引用，不批量删除整个目录。
5. 删除确认无人引用的图片，记录处理结果；不能确认的对象暂时保留。

不对整个 avatars/ 配置按年龄无条件删除的生命周期规则，避免删掉仍在使用的头像。本版允许少量遗留图片等待人工处理，不承诺服务重启后自动清理。

### 11.3 签名过期

- 默认签名有效期 3600 秒。
- /auth/me 响应不应被共享缓存保存，建议 Cache-Control: no-store。
- 前端图片加载失败，可对当前这次头像加载重新调用一次 /auth/me。
- 得到新地址后重试一次。
- 仍然失败则展示默认头像，不无限递归。
- 刷新请求出现 401，按现有认证流程处理。
- 不因为签名过期而清空数据库中的 avatar_object_key。
- 使用加载代次标识，忽略旧图片或旧会话的异步结果。

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
  avatarLoadGeneration: 0,
  sessionGeneration: 0
};
~~~

- 三种写操作在当前页面串行进行，避免乱序响应覆盖。
- 登录身份变化或失效时增加 sessionGeneration。
- 写请求开始时记录会话代次，返回后不匹配则丢弃界面回写。
- 头像地址变更时增加 avatarLoadGeneration。
- 图片 load/error 回调只处理当前代次。
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
| 图片规则校验失败，以 BusinessException 表达 | HTTP 422，明确规则原因 |
| 必要 OSS 上传失败，记录原始异常后转换为 BusinessException | HTTP 503，稍后重试 |
| 清理旧对象失败 | 记录日志供人工清理，不修改已成功响应 |
| 认证失效 | HTTP 401 |
| 其他未预期异常 | HTTP 500 |

复用现有 BusinessException，不新增头像专用异常类。固定错误码和提示集中放在 ResultCode，例如 AVATAR_INVALID（422）、AVATAR_TOO_LARGE（413）、AVATAR_STORAGE_UNAVAILABLE（503）；规则细节可以通过现有 code/message 构造器提供。GlobalExceptionHandler 继续统一处理 BusinessException，仅补充框架 multipart 异常和适用于 JSON、multipart 各端点的媒体类型提示。

OssAvatarStorageService 只把预期的 SDK/网络失败转换为存储业务错误，捕获处记录原始异常及 Request ID，不把任意编程错误都包装成 503。AvatarService 根据发生阶段处理异常：上传失败向外返回；旧图删除失败仅记录日志；确定回滚后的新图删除失败不能掩盖最初的数据库异常。复用异常类不等于用一个 catch 统一处理所有阶段。

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
- 完整签名 URL。
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
- [ ] 头像端点先完成 AvatarService 变更，再调用 AuthService.getCurrentUser() 返回完整资料；变更失败时不继续查询成功响应。

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

测试需增加 put 的静态导入，并使用现有测试上下文。AuthController 新增 AvatarService 依赖后，现有 AuthControllerWebTest 同步增加 @MockBean AvatarService。头像成功用例分别模拟 void 写操作和 authService.getCurrentUser() 的结果；使用调用顺序断言验证先写后读，不能继续 mock AvatarService 返回 UserVO。

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

### 14.2 Service 与事务

- [ ] 用户 ID 来自 UserContext。
- [ ] 注册插入用户时一并保存 nickname、email，avatar_object_key 初始为 NULL。
- [ ] 注册失败不残留半成品用户，昵称、邮箱不做唯一性查询。
- [ ] 注册成功后的头像接口只使用新账号的有效令牌定位用户。
- [ ] 资料更新仅更新 nickname、email。
- [ ] 头像更新仅更新 avatar_object_key。
- [ ] 昵称、邮箱显式 NULL 确实落库。
- [ ] 原样重复保存成功。
- [ ] 头像关联提交成功后才尝试删除旧对象。
- [ ] 使用真实事务管理器和隔离数据库验证 TransactionTemplate 回滚、行锁及提交边界，不能仅靠 mock 回调宣称事务有效。
- [ ] 上传和删除 OSS 的调用点不处于数据库事务中；提交失败时不能进入删除旧头像的成功分支。
- [ ] 上传失败不修改关联。
- [ ] 数据库确定回滚后尝试删除新对象，删除失败记录日志。
- [ ] 提交结果不确定不误删新对象。
- [ ] 两次并发头像替换关联正确。
- [ ] 直接删除不误删仍在使用的对象。
- [ ] 旧对象删除失败不影响新头像或恢复默认的成功结果。
- [ ] 恢复默认重复调用成功。
- [ ] service 单测结束清理 UserContext，避免污染后续测试。

### 14.3 图片处理

使用受控测试样本，避免运行单测时下载外部图片：

- [ ] 正常 JPEG、透明 PNG。
- [ ] 小图不放大。
- [ ] 长宽不同图片等比例缩小。
- [ ] EXIF 旋转与镜像方向处理正确。
- [ ] 文件内容与扩展名不一致。
- [ ] 非图片伪装成 JPG。
- [ ] 损坏、截断图片。
- [ ] SVG、GIF、WebP、HEIC 拒绝。
- [ ] 文件大小临界值。
- [ ] 单边及总像素数临界值。
- [ ] 图片输出可重新解码。
- [ ] 输出不保留原始 EXIF 等元数据。

### 14.4 存储与清理

AvatarService 与 AuthServiceImpl 的普通单元测试直接 mock 具体类 OssAvatarStorageService，不依赖真实 OSS，也不为 mock 新增接口。需要单测存储封装时 mock SDK 客户端。AuthServiceImpl 的响应转换测试覆盖空头像与非空 Key，并断言调用签名方法使用数据库 Key。

真实 OSS 集成测试显式启用，使用专用测试 Bucket 或隔离测试前缀，并执行：

1. 上传处理后图片。
2. 生成浏览器可用的签名 URL。
3. 验证返回 Content-Type 与图片内容。
4. 验证未签名访问被拒绝。
5. 验证过期签名失效。
6. 删除测试对象。
7. 检查测试未遗留文件。
8. 模拟旧对象和回滚后新对象的删除失败，验证日志包含人工处理所需信息，且不误报已提交操作失败。

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
| 签名过期 | 刷新一次后重试，无无限循环 |
| 上传过程中登录失效 | 返回登录，不回写旧用户状态 |
| 手机照片 | 方向正确，预览裁切合理 |
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
- [ ] 新增实体属性、VO 字段与 UserProfileUpdateDTO，扩展现有 AuthServiceImpl.toVO()；签名依赖在阶段 C 实现，本阶段测试可 mock 具体存储类。
- [ ] 实现 nickname、email 更新。
- [ ] 验证 NULL 清空、重复邮箱、原样保存。
- [ ] 更新旧 UserVO 构造器测试。
- [ ] 运行认证和资料相关测试。

交付：昵称、邮箱可在注册时保存，登录后读取、修改和清空；旧的两字段注册请求仍兼容。

### 阶段 C：图片处理与 OSS

- [ ] 配置受限 OSS 身份和实际 Bucket 参数。
- [ ] 实现 OssProperties、OssConfig，集中绑定配置并管理 SDK 客户端生命周期。
- [ ] 固定 SDK、图片元数据读取依赖并编译。
- [ ] 实现图片内容、大小、尺寸校验。
- [ ] 实现方向处理、缩放与重新编码。
- [ ] 在具体类 OssAvatarStorageService 实现上传、签名、删除方法，接入 AuthServiceImpl.toVO()，不新增存储接口或 Impl 配对。
- [ ] 复用 BusinessException、ResultCode，补充 multipart 异常映射和必要错误码。
- [ ] 完成图片单测和隔离 OSS 集成验证。

交付：后端可安全生成并存储规范化头像图片。

### 阶段 D：头像关联与补偿

- [ ] 增加上传、删除接口及测试。
- [ ] 在具体类 AvatarService 内用 TransactionTemplate 实现短事务与用户行锁，保持上传、删除在事务外。
- [ ] 实现头像关联更新，并将旧 Key 返回给外层以便提交后删除。
- [ ] 头像写方法返回 void，Controller 随后调用 AuthService.getCurrentUser()，不新建响应组装器或事务辅助 Service。
- [ ] 实现确定回滚补偿、结果不确定核对。
- [ ] 实现事务外直接删除、有限超时和失败日志。
- [ ] 核对人工清理步骤，不开发调度或后台自动重试。
- [ ] 验证并发替换不误删有效图片。

交付：头像替换、恢复默认、尽力删除和失败记录可运行，遗留图片可按文档人工核查。

### 阶段 E：静态页面

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
- [ ] 实现默认头像、签名刷新和会话代次保护。
- [ ] 验证手机宽度与弹窗键盘操作。

交付：注册时可选填写三项资料，以及登录后完整可用的个人资料界面。

### 阶段 F：联调与交付

- [ ] 运行必要后端测试。
- [ ] 用两个用户验证资料隔离。
- [ ] 验证注册可选字段所有组合，以及登录、上传分别失败时的恢复行为。
- [ ] 验证旧两字段注册请求、登录、分类、账单功能。
- [ ] 逐项完成浏览器验收。
- [ ] 检查无凭证、无签名 URL 被提交。
- [ ] 记录数据库版本、OSS 配置项和清理操作。
- [ ] 明确尚未执行或未通过的测试，不把代码片段当作测试结果。

交付：具备测试证据、操作说明和回退方案的功能版本。

---

## 16. 发布、回退与运行维护

### 16.1 发布顺序

1. 备份并执行兼容性数据库迁移。
2. 准备 OSS 配置与凭证。
3. 发布 Java 后端。
4. 验证资料查询、上传、签名展示、删除。
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
- 保持系统时钟正确，避免签名有效期异常。
- 实际部署有反向代理时同步核对上传大小与超时。
- 更换访问域名后不需要改用户表 Object Key。
- 轮换凭证时验证上传、签名与清理权限。
- 不将头像可访问性检查加入每次用户资料数据库事务。

---

## 17. 最终验收定义

完成必须同时满足：

1. 昵称可重复、可修改、可清空；为空时显示用户名。
2. 邮箱仅做格式与长度校验，可重复、可清空。
3. 用户通过本地文件选择设置头像，不存在 URL 输入框。
4. 图片经过服务端内容与尺寸校验，处理后存入阿里云 OSS。
5. 数据库保存 avatar_object_key，接口返回 avatar_url。
6. 默认头像、上传预览、立即生效和恢复默认行为清楚一致。
7. 用户只能修改自己的资料和头像。
8. 资料保存与头像保存互不覆盖。
9. 数据库确定回滚后尝试删除新图片；旧图或新图删除失败均记录日志，具备人工核查步骤，不依赖清理表或后台自动重试。
10. 签名过期不会删除头像或造成无限重试。
11. 老用户、注册登录和原有记账功能正常。
12. 完成必要测试，并如实记录验证结果。
13. 未查阅、未修改 Vue 前端。
14. 注册页支持可选昵称、邮箱和本地头像，一次点击完成注册、自动登录和可选上传，用户无需到资料页重新选择。
15. RegisterDTO、注册 Service、注册响应和前端请求覆盖昵称、邮箱；头像通过新账号的认证上传接口保存。
16. 账号创建后若登录或头像失败，准确提示完成阶段，只重试失败阶段，不重复注册或删除已创建账号。
17. 新增生产 Java 文件按第 8.2 节收敛为 6 个；响应转换、业务异常和现有用户持久层均复用，事务通过 AvatarService 内的 TransactionTemplate 明确控制。

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

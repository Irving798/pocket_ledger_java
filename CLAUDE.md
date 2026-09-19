# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 仓库概览

口袋账本（Pocket Ledger）个人记账应用，单仓多模块，**不是 git 仓库**（无版本控制，改动前无法依赖 git 回滚）：

- `backend/` — Java 8 + Spring Boot 2.3 + MyBatis-Plus 3.4 + MySQL 8，JWT 认证，运行在 **8080** 端口。
- `fronted_static/` — 零构建原生 HTML/CSS/JS 前端（目录名 `fronted` 是既成事实，勿改名），直接浏览器打开 `index.html` 即可，对接 Java 后端。
- `frontend_vue/` — Vue 3 + TS SPA（pnpm  monorepo 独立模块）。**注意：其 README 和 playwright.config.ts 中关于「FastAPI 后端 / 8000 端口」的描述是迁移自旧仓库的残留，本仓后端是 Java 版（8080），以 `.env` 的 `VITE_DEFAULT_API_BASE_URL=http://localhost:8080` 为准**；README 引用的 `docs/frontend-vue3-migration-design.md` 在本仓不存在。
- `docs/superpowers/plans/` — 待实施的设计文档（如用户资料 + 阿里云 OSS 头像，仅针对 backend 和 fronted_static，明确禁止改动 frontend_vue）。

## 后端（backend/）

### 构建与运行（Windows PowerShell）

工具链固定路径，**不要改全局 JAVA_HOME**，只在当前会话临时指定：

```powershell
$env:JAVA_HOME = "C:\fly_develop\Java\temurin-jdk8u504-b01"
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" clean test          # 构建+单测
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" spring-boot:run     # 运行（8080）
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" -Dtest=BillControllerWebTest test        # 单个测试类
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" -Dtest=BillControllerWebTest#方法名 test  # 单个测试方法
```

备用入口 `.\mvnw.cmd`（同样固定 Maven 3.8.6）。两种方式都会自动读取 `.mvn/maven.config` → 使用 `.mvn/settings.xml` 的阿里云镜像，无需额外参数。

数据库：默认连本机 MySQL `127.0.0.1:3306/pocket_ledger_yihai`（root/123456），可用环境变量 `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` 覆盖。建库脚本在 `src/main/java/com/fly/pocket_ledger_java/sql/pocket_ledger_init.sql`（刻意放在 java 源码树内）。

真实数据库集成测试默认跳过，需显式开启：

```powershell
$env:RUN_DB_INTEGRATION_TESTS = "true"
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" -Dtest=DatabaseConnectionIntegrationTest test
```

### 架构与契约（改代码前必读）

分层：`controller → service(接口) → service/impl → mapper(MyBatis-Plus) → entity`；入参用 `dto/`（带 javax.validation 校验注解），出参用 `vo/`。`service/support/` 放跨方法的领域规则与装配器（BillRules / BillAssembler）。

**响应契约是最高优先级约束**（来源：`backend/docs/pocket-ledger-api.html`）：

- 统一响应体 `ApiResponse { code, message, data }`，**code 与 HTTP 状态码恒等**，`code=200` 成功，失败时 `data=null`。
- 状态码与文案全部集中在 `common/ResultCode.java` 枚举，禁止散落在 Controller/Service。
- 业务错误抛 `BusinessException`，由 `GlobalExceptionHandler`(@RestControllerAdvice) 统一转响应；参数校验失败固定 422 + `参数错误：字段 原因` 模板（对齐旧 FastAPI 后端的 Pydantic 语义，前端依赖此格式）。

**认证链路**：无 Spring Security 过滤器链，仅用 spring-security-crypto 的 BCrypt 做密码哈希。`AuthInterceptor`（HandlerInterceptor）拦截 `/**`，免登录清单收敛在 `application.yml` 的 `auth.ignore-urls`（加免登录接口只改 yml）。验签通过后把 `LoginUser` 存入 `util/UserContext`（ThreadLocal），`afterCompletion` 必须清理防线程复用串号；Controller 用 `UserContext.getUserId()` 取身份，缺失时抛 401。JWT 密钥走 `JWT_SECRET` 环境变量。

**技术基线硬约束**：Java 8（无 record，用 Lombok @Getter + 私有构造 + 静态工厂）、`javax.*` 命名空间（严禁 jakarta）、MyBatis XML 放 `resources/mapper/`、开启驼峰映射。测试用 `@WebMvcTest` 切片 + JUnit 5（Boot 2.3 自带）。

## Vue 前端（frontend_vue/）

Node ≥ 22.12 + pnpm 10（corepack）。常用命令：

```bash
pnpm dev          # 5173
pnpm lint         # ESLint 10 flat config
pnpm typecheck    # vue-tsc
pnpm test         # Vitest（jsdom）
pnpm test:e2e     # Playwright（需先启动 Java 后端；dev server 自动拉起）
```

**提交门禁**：`pnpm lint && pnpm typecheck && pnpm test && pnpm build` 全绿。

架构：`features/` 按业务域切分（auth/categories/bills/dashboard/settings），每域内含 api/model/validation/组件；`shared/api/` 统一请求封装；`stores/` Pinia。页面编排（LedgerPage）只组合不写逻辑。

### 跨前后端的跨版本契约（禁止破坏）

- localStorage 键：`pl_token` / `pl_theme` / `pl_api_base`（静态版与 Vue 版共用，禁止改名）。
- 金额：线上传两位小数字符串，内部一律整数分计算，禁止 `Number` 累加货币。
- 日期：`YYYY-MM-DD` 本地时区语义，禁止 UTC 截取。

## 静态前端（fronted_static/）

零构建，无 lint/test 命令，改动后直接用浏览器验证。结构：`js/api.js`（API 封装，解包统一响应体）、`js/charts.js`（纯 SVG 图表）、`js/app.js`（主逻辑）。

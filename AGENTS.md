# Repository Guidelines

## 修改范围（强制）

本项目所有修改一律禁止触碰 `frontend_vue/`，包括源码、配置、依赖、锁文件及生成产物；不要在该目录执行安装、格式化或构建等可能写入文件的命令。前端开发、修复和验证仅针对 `fronted_static/`（实际目录名，勿改名为 `frontend_static/`）。涉及前后端联动时，仅修改 `backend/` 与 `fronted_static/` 中的相关实现。

## 项目结构

- `backend/`：Java 8、Spring Boot 2.3、MyBatis-Plus、MySQL 8。业务代码在 `src/main/java/com/fly/pocket_ledger_java/`，按 `controller → service/impl → mapper` 分层；测试在 `src/test/java/`，配置与 Mapper XML 在 `src/main/resources/`。
- `frontend_vue/`：仅本地保留的 Vue 前端，已通过根目录 `.gitignore` 排除，不上传、不修改、不纳入验证范围。
- `fronted_static/`：唯一维护的前端，使用原生 HTML/CSS/JS；`index.html` 为入口，`css/styles.css` 存放样式，`js/api.js` 封装请求，`js/charts.js` 绘制图表，`js/app.js` 管理页面逻辑。
- `backend/docs/pocket-ledger-api.html` 为接口契约；`docs/superpowers/plans/` 存放实施文档，不代表功能已实现。

## 构建、测试与开发

后端命令在 `backend/` 执行；使用 JDK 8，按需临时设置会话 `JAVA_HOME`，不要修改全局配置。Maven Wrapper 固定为 3.8.6。

```powershell
.\mvnw.cmd clean test       # 编译并测试
.\mvnw.cmd clean package    # 测试并打包
.\mvnw.cmd spring-boot:run  # 启动服务，默认 8080
```

静态前端无需安装依赖或构建。启动后端后，直接用浏览器打开 `fronted_static/index.html`；也可在 `fronted_static/` 执行 `python -m http.server 5173`，访问 `http://localhost:5173`。

## 代码风格与复用

Java 使用四空格缩进，保持 Java 8 和 `javax.*` 兼容；类名采用 PascalCase，方法和变量采用 camelCase。静态前端沿用所在文件的缩进、引号和分号风格，复用现有 CSS 与原生 JavaScript 组织方式，不引入新的前端框架或构建链。

新增实现前全仓检索相似业务及调用点。后端优先复用 `BillRules`、`BillAssembler` 和现有 DTO/VO；统一使用 `ApiResponse`、`ResultCode`、`BusinessException` 与 `GlobalExceptionHandler`。前端复用 `fronted_static/js/` 中的请求、图表及页面逻辑，避免重复封装或引入另一套架构。

## 测试要求

后端使用 JUnit 5、Mockito、AssertJ；接口测试沿用 `@WebMvcTest`，类名采用 `*Test` 或 `*WebTest`。单类测试示例：`.\mvnw.cmd -Dtest=BillControllerWebTest test`。真实数据库测试需设置 `$env:RUN_DB_INTEGRATION_TESTS = "true"`，并准备测试库。

静态前端无自动化测试或 lint 命令，改动后通过浏览器验证受影响的交互，如登录、账单增删改查、筛选和图表，并检查控制台及请求结果。未配置覆盖率门槛；不将 Vue 的检查作为本项目验收要求。

## Git 管理与合并请求

项目根目录统一管理 Git，远程 `origin` 为 [Irving798/pocket_ledger_java](https://github.com/Irving798/pocket_ledger_java)，主分支为 `main`；不要在子目录另建仓库。旧文档中“不是 Git 仓库”的描述已失效。

日常开发从最新 `main` 创建 `feat/功能名` 或 `fix/问题名` 分支。提交前执行 `git status --short`、`git diff`，用 `git add <具体路径>` 暂存，再用 `git diff --cached` 核对范围；不要强制添加被忽略的文件。提交消息使用 `feat: 新增账单筛选`、`fix: 修正金额校验`、`docs: 更新贡献指南` 或 `chore: 初始化项目版本管理`。

首次推送使用 `git push -u origin main`；后续功能分支使用 `git push -u origin <分支名>` 并创建 PR。PR 说明问题、变更范围、验证结果及配置影响；关联已有问题，界面改动附截图，明确未执行或失败的检查。

## 配置与协作约束

数据库与密钥通过 `DB_*`、`JWT_SECRET` 环境变量配置，不提交真实凭据。实际 API 地址为 `http://localhost:8080`；页面 API 设置中的已保存地址可能覆盖默认值。保留 `pl_token`、`pl_theme`、`pl_api_base`；前端金额按整数分计算，传输两位小数字符串，日期使用本地 `YYYY-MM-DD`。

根目录 `.gitignore` 排除 Vue 目录、IDE 配置、构建产物、依赖和本地环境文件；继续沿用 `backend/.gitignore` 与 `.gitattributes`。提交前确认数据库密码、JWT 密钥等仍为环境变量引用或开发占位值。

使用简体中文沟通。涉及三个以上文件改动时，先提交中文方案并等待确认。遇到 `settings.json` 的 `deny` 禁令立即停止；修改该文件前必须获得许可。

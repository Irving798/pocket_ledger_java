# Pocket Ledger Java

Pocket Ledger 后端服务，基于 Java 8、Spring Boot 2.3、MyBatis-Plus 和 MySQL。

## 技术栈

- Eclipse Temurin JDK 8u504-b01（源码级别和目标字节码均为 1.8）
- Apache Maven 3.8.6
- Maven Compiler Plugin 3.8.1
- Spring Boot 2.3.0.RELEASE（Spring Framework 5.2.6.RELEASE）
- MyBatis-Plus 3.4.3.4
- MySQL 8 / HikariCP
- Maven Wrapper（备用，同样固定为 Maven 3.8.6）

## 项目结构

```text
src/main/java/com/fly/pocket_ledger_java/
├── PocketLedgerJavaApplication.java
├── controller/     # HTTP 接口层
├── service/        # 业务接口
│   └── impl/       # 业务实现
├── mapper/         # MyBatis-Plus Mapper
├── entity/         # 数据库实体
├── dto/            # 请求参数对象
├── vo/             # 接口响应对象
├── config/         # 框架配置
├── exception/      # 全局异常处理
└── util/           # 通用工具（按需添加）
```

## 数据库配置

默认连接本机 Docker MySQL：

```text
Host: 127.0.0.1
Port: 3306
Database: pocket_ledger_yihai
Username: root
Password: 123456
```

生产或其他环境请使用环境变量覆盖默认值：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`。

## 构建和运行

日常开发使用本地安装的 Maven 3.8.6。当前开发机的工具目录为：

- JDK：`C:\fly_develop\Java\temurin-jdk8u504-b01`
- Maven：`C:\fly_develop\Java\apache-maven-3.8.6`

项目不要求、也不建议修改 Windows 的全局 `JAVA_HOME`。IDEA 已把 Project SDK 和 Maven Runner 设为项目 JDK 8；如果从命令行构建，可只在当前 PowerShell 会话中临时指定 JDK（关闭终端后自动失效），然后确认 Maven 运行在 Java 1.8.0_504 上：

```powershell
$projectJdk8 = "C:\fly_develop\Java\temurin-jdk8u504-b01"
$env:JAVA_HOME = $projectJdk8
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" -version
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" clean test
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" spring-boot:run
```

仓库同时保留 Maven Wrapper 作为新电脑或 CI 的可复现备用入口；它也固定使用 Maven 3.8.6：

```powershell
.\mvnw.cmd -version
.\mvnw.cmd clean test
```

两种方式都会读取 `.mvn/maven.config`，并通过 `.mvn/settings.xml` 使用阿里云 Maven 镜像。

执行真实数据库连接测试：

```powershell
$env:RUN_DB_INTEGRATION_TESTS = "true"
& "C:\fly_develop\Java\apache-maven-3.8.6\bin\mvn.cmd" -Dtest=DatabaseConnectionIntegrationTest test
```

## 分类接口

查询全部分类：

```http
GET /categories
```

按收支类型筛选：

```http
GET /categories?type=expense
GET /categories?type=income
```

只接受 `income` 和 `expense`，结果按 `sort`、`id` 升序返回。


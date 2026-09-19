# 口袋账本 · PocketLedger 前端

纯原生 HTML / CSS / JavaScript 实现，**零依赖、零构建**，配合后端 Spring Boot 版（pocket_ledger_java）使用。

## 界面预览

- 深色极光 + 玻璃拟态设计（可一键切换浅色主题，自动记忆）
- 统计卡片：总支出 / 总收入 / 结余（数字滚动动画，跟随筛选条件实时变化）
- 收支趋势图：纯 SVG 柱状图，按天 / 按月自适应，悬停查看明细
- 分类构成环形图：图例联动高亮，支出 / 收入一键切换
- 账单明细：按日期分组 + 当日收支小计，支持按分类、收支类型、日期筛选与排序、分页
- 记一笔弹窗：收支切换、大金额输入、emoji 分类网格、自定义分类、前端校验
- 批量删除：勾选多条后底部浮起操作栏，一键删除
- 快捷键：`N` 记一笔，`Esc` 关闭弹窗

## 运行方式

### 1. 启动后端（在 pocket_ledger_java 项目根目录）

```bash
# 需 JDK 8 + Maven 3.8.6，并先启动 MySQL（pocket_ledger_yihai 库）
mvn spring-boot:run
```

后端默认运行在 `http://localhost:8080`。

### 2. 打开前端

直接用浏览器打开 `index.html` 即可（Java 后端已配置 CORS，无需额外服务）。

也可以用任意静态服务器：

```bash
python -m http.server 5173
# 浏览器访问 http://localhost:5173
```

## 目录结构

```
frontend/
├── index.html      # 页面结构
├── css/styles.css  # 设计系统（主题变量 / 玻璃拟态 / 动效 / 响应式）
└── js/
    ├── api.js      # API 封装（统一解包 Result{code,message,data}）
    ├── charts.js   # 纯 SVG 图表（趋势柱状图 + 分类环形图）
    └── app.js      # 主逻辑（状态、渲染、筛选分页、弹窗表单）
```

## 设置

右上角齿轮可修改后端地址（默认 `http://localhost:8080`），保存在浏览器 localStorage；
**localStorage 里保存过的地址会覆盖代码默认值**——如果之前连过 8000 端口的旧后端，请在这里改回
`http://localhost:8080`，或在控制台执行 `localStorage.removeItem("pl_api_base")` 清除。
导航栏上的状态点会实时显示与后端的连接状态（绿=已连接，红=未连接）。

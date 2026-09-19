/* ============================================================
   口袋账本 —— API 层（v4.0）
   认证：POST /auth/register、POST /auth/login、GET /auth/me
   分类：GET  /categories?type=    （只读字典）
   账单：POST /bills/add、GET /bills/list、GET /bills/{id}、
         PUT  /bills/{id}、DELETE /bills
   统一返回结构：Result { code, message, data }
   认证方式：localStorage 存 JWT，请求头带 Authorization: Bearer <token>
   ============================================================ */

const API = (() => {
  "use strict";

  const STORAGE_KEY = "pl_api_base";
  const TOKEN_KEY = "pl_token";
  const DEFAULT_BASE = "http://localhost:8080";

  function getBase() {
    return (localStorage.getItem(STORAGE_KEY) || DEFAULT_BASE).replace(/\/+$/, "");
  }

  function setBase(url) {
    localStorage.setItem(STORAGE_KEY, url.replace(/\/+$/, ""));
  }

  /* ---------- token ---------- */
  function getToken() { return localStorage.getItem(TOKEN_KEY) || ""; }
  function setToken(t) { localStorage.setItem(TOKEN_KEY, t); }
  function clearToken() { localStorage.removeItem(TOKEN_KEY); }

  /**
   * 统一请求封装：
   * - 自动拼接 baseURL、携带 Authorization、序列化 JSON body
   * - 解包 Result 信封；code !== 200 或 HTTP 非 2xx 时抛出带后端 message 的 Error
   * - 401 视为登录态失效：清除本地 token，并给 Error 打上 .auth 标记（登录接口自身除外）
   */
  async function request(path, { method = "GET", body, timeout = 12000 } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);

    const headers = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const token = getToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;

    let res;
    try {
      res = await fetch(getBase() + path, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: controller.signal,
        cache: "no-store",   // GET 一律绕过缓存，保证增删改后读到最新数据
      });
    } catch (e) {
      if (e.name === "AbortError") throw new Error("请求超时，请检查后端服务是否正常运行");
      throw new Error(`无法连接后端服务，请确认后端已启动（当前地址 ${getBase()}，可用右上角齿轮修改）`);
    } finally {
      clearTimeout(timer);
    }

    let payload = null;
    try { payload = await res.json(); } catch { /* 非 JSON 响应，忽略 */ }

    const message = (payload && (payload.message || payload.detail)) || `请求失败（HTTP ${res.status}）`;
    if (!res.ok || (payload && typeof payload.code === "number" && payload.code !== 200)) {
      const err = new Error(message);
      if (res.status === 401 && !path.startsWith("/auth/login")) {
        if (token) clearToken();
        err.auth = true;
      }
      throw err;
    }
    return payload ? payload.data : null;
  }

  /** 拼接查询参数（跳过空值） */
  function buildQuery(params) {
    const qs = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== "") qs.set(k, v);
    });
    const s = qs.toString();
    return s ? `?${s}` : "";
  }

  /* ---------- 认证 ---------- */

  function register(username, password) {
    return request("/auth/register", { method: "POST", body: { username, password } });
  }

  async function login(username, password) {
    const data = await request("/auth/login", { method: "POST", body: { username, password } });
    setToken(data.access_token);
    return data;
  }

  function logout() { clearToken(); }

  function me() { return request("/auth/me"); }

  /* ---------- 分类（只读字典） ---------- */

  function listCategories(type) {
    return request("/categories" + buildQuery({ type }));
  }

  /* ---------- 账单 ---------- */

  /**
   * 分页查询账单
   * @param {Object} f { start, end, type, categoryId, order, page, pageSize, includeBreakdowns }
   *   includeBreakdowns=true 时后端批量加载细分（breakdowns 数组）；默认 false（breakdowns 为 null）
   * @returns {Promise<{total, page, page_size, income_total, expense_total, list}>}
   */
  function listBills(f) {
    return request("/bills/list" + buildQuery({
      start_date: f.start,
      end_date: f.end,
      bill_type: f.type,
      category_id: f.categoryId,
      order: f.order || "desc",
      page: f.page || 1,
      page_size: f.pageSize || 10,
      include_breakdowns: f.includeBreakdowns === true,
    }));
  }

  /**
   * 拉取筛选范围内的全部账单（供图表聚合使用）
   * 后端 page_size 上限 100，循环翻页；硬上限 50 页（5000 条）防止失控
   * 图表只聚合总账单金额，显式不加载细分（include_breakdowns=false）
   */
  async function listAllBills(f) {
    const pageSize = 100;
    const first = await listBills({ ...f, page: 1, pageSize, order: "desc", includeBreakdowns: false });
    const list = [...first.list];
    const pages = Math.min(Math.ceil(first.total / pageSize), 50);
    if (pages > 1) {
      const rest = await Promise.all(
        Array.from({ length: pages - 1 }, (_, i) =>
          listBills({ ...f, page: i + 2, pageSize, order: "desc", includeBreakdowns: false })
        )
      );
      rest.forEach((r) => list.push(...r.list));
    }
    return list;
  }

  /** 账单详情（始终带完整细分：breakdowns 数组，系统“其他”在末尾） */
  function getBill(id) {
    return request(`/bills/${id}`);
  }

  /** 新增账单 @param {{category_id, amount, description, bill_date}} data */
  function addBill(data) {
    return request("/bills/add", { method: "POST", body: data });
  }

  /** 更新账单 */
  function updateBill(id, data) {
    return request(`/bills/${id}`, { method: "PUT", body: data });
  }

  /** 批量删除 @param {number[]} ids @returns {Promise<number>} 实际删除条数 */
  function deleteBills(ids) {
    return request("/bills", { method: "DELETE", body: { ids } });
  }

  /** 健康检查（后端根路径） */
  function health() {
    return request("/", { timeout: 4000 });
  }

  return {
    getBase, setBase, getToken, setToken, clearToken,
    register, login, logout, me,
    listCategories,
    listBills, listAllBills, getBill, addBill, updateBill, deleteBills, health,
  };
})();

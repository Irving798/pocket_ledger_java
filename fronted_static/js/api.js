/* ============================================================
   口袋账本 —— API 层（v4.1）
   认证：POST /auth/register、POST /auth/login、GET /auth/me、
         PUT  /auth/me、POST /auth/me/avatar（multipart）、
         DELETE /auth/me/avatar
   分类：GET  /categories?type=    （只读字典）
   账单：POST /bills/add、GET /bills/list、GET /bills/{id}、
         PUT  /bills/{id}、DELETE /bills
   统一返回结构：Result { code, message, data }
   认证方式：localStorage 存 JWT，请求头带 Authorization: Bearer <token>
   v4.1：request 同时支持 JSON 与 FormData；写请求结果不确定时标记
         uncertain，不自动重发；401 区分当前会话与过时会话（stale）
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

  function logout() { clearToken(); }

  function me() { return request("/auth/me"); }

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

  /** 健康检查（后端根路径，同样走统一响应包装） */
  function health() {
    return request("/", { timeout: 4000 });
  }

  return {
    getBase, setBase, getToken, setToken, clearToken,
    // 新方法必须导出，app.js 才能调用。
    register, login, logout, me, updateMe, uploadAvatar, removeAvatar,
    listCategories,
    listBills, listAllBills, getBill, addBill, updateBill, deleteBills, health,
  };
})();

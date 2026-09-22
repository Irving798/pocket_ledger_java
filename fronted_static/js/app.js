/* ============================================================
   口袋账本 —— 主逻辑（v5.0）
   登录/注册 · 状态管理 · 列表渲染 · 筛选分页 · 弹窗表单 · 批量操作
   分类来源：GET /categories（后端只读字典，前端不再维护常量）
   账单字段：category_id / category_name / type（type 由分类决定）
   v4.1：账单细分编辑器（formBreakdowns 只存用户显式项，系统“其他”
         由总金额实时计算，不进数组、不参与提交）+ 列表细分展开展示
   v5.0：用户资料弹窗（昵称/邮箱 PUT、头像上传/恢复默认，两处图片
         共用选择-预览-上传能力）；注册分阶段提交（资料随注册写入、
         头像登录后单独上传），失败恢复绝不重复建号；会话代次保护，
         旧响应不覆盖新账号，写请求结果不确定时先核对再提示
   ============================================================ */

(() => {
  "use strict";

  /* ==================== 常量 ==================== */

  // 后端分类 icon 标识 → emoji（展示用），未收录的按类型兜底
  const ICON_EMOJI = {
    food: "🍜", transport: "🚌", shopping: "🛍️", housing: "🏠", entertainment: "🎮",
    medical: "💊", education: "📚", gift: "🧧", other_expense: "📦",
    salary: "💼", bonus: "🏆", investment: "📈", parttime: "💻", other_income: "💰",
  };

  // 分类徽章底色（按名称哈希稳定取色）
  const TINTS = [
    "rgba(129,140,248,.17)", "rgba(192,132,252,.17)", "rgba(244,114,182,.17)",
    "rgba(251,113,133,.17)", "rgba(251,191,36,.17)", "rgba(52,211,153,.17)",
    "rgba(34,211,238,.17)", "rgba(163,230,53,.17)",
  ];

  const WEEKDAYS = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

  // 单个账单最多 20 个显式细分（与后端 Schema 一致）
  const MAX_BREAKDOWNS = 20;

  const SVG = {
    check: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="m9 11 3 3L22 4"/></svg>`,
    alert: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 8v4M12 16h.01"/></svg>`,
    pencil: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/></svg>`,
    trash: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
    chevL: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"/></svg>`,
    chevR: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m9 18 6-6-6-6"/></svg>`,
    chevD: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m6 9 6 6 6-6"/></svg>`,
  };

  /* ==================== 状态 ==================== */

  const state = {
    user: null,         // 当前登录用户 {id, username}
    dateMode: "single", // 查询模式：single 单日 | range 范围
    filters: { start: "", end: "", type: "", categoryId: null, order: "desc" },
    page: 1,
    pageSize: 10,
    total: 0,
    bills: [],          // 当前页账单
    chartBills: [],     // 筛选范围内全部账单（图表聚合用）
    categories: { expense: [], income: [] },  // 分类字典（来自后端）
    catById: new Map(), // category_id -> 分类对象（查 icon 用）
    selection: new Set(),
    editingId: null,
    formType: "expense",
    formCategory: null, // 选中的分类 id
    formBreakdowns: [], // 细分编辑器：只存用户显式项 [{item_name, amount}]，系统“其他”不进数组
    expandedBreakdowns: new Set(), // 列表中已展开细分面板的账单 id
    donutType: "expense",
  };

  // 两处图片各自保存 File 和预览地址，不能相互覆盖。
  const profileState = {
    pendingFile: null, previewUrl: null, selecting: false, selectionId: 0,
    saving: false, uploading: false, removing: false, sessionGeneration: 0,
  };
  const registrationState = {
    phase: "idle", pendingFile: null, previewUrl: null, selecting: false, selectionId: 0,
    registeredUserId: null, registeredUsername: null, generation: 0,
  };

  /* ==================== DOM 引用 ==================== */

  const $ = (id) => document.getElementById(id);
  const apiStatus = $("apiStatus"), apiStatusText = $("apiStatusText");
  const userName = $("userName"), logoutBtn = $("logoutBtn");
  const themeToggle = $("themeToggle");
  const settingsBtn = $("settingsBtn"), settingsPopover = $("settingsPopover");
  const apiBaseInput = $("apiBaseInput"), apiSaveBtn = $("apiSaveBtn");
  const statExpense = $("statExpense"), statIncome = $("statIncome");
  const statBalance = $("statBalance"), statCount = $("statCount");
  const trendSub = $("trendSub"), trendChartEl = $("trendChart");
  const donutSub = $("donutSub"), donutChartEl = $("donutChart"), donutLegendEl = $("donutLegend");
  const donutTypeSeg = $("donutTypeSeg");
  const typeSeg = $("typeSeg"), categoryFilter = $("categoryFilter");
  const startDate = $("startDate"), endDate = $("endDate");
  const dateModeSeg = $("dateModeSeg"), singleDateWrap = $("singleDateWrap");
  const rangeDateWrap = $("rangeDateWrap"), singleDate = $("singleDate");
  const orderBtn = $("orderBtn"), resetBtn = $("resetBtn"), selectAll = $("selectAll");
  const totalBadge = $("totalBadge"), billList = $("billList");
  const pageInfo = $("pageInfo"), pagination = $("pagination"), pageSizeSel = $("pageSize");
  const addBtn = $("addBtn");
  const batchBar = $("batchBar"), batchCount = $("batchCount"), batchSum = $("batchSum");
  const batchDeleteBtn = $("batchDeleteBtn"), batchCancelBtn = $("batchCancelBtn");
  const billModal = $("billModal"), modalTitle = $("modalTitle"), billForm = $("billForm");
  const typeToggle = $("typeToggle"), amountField = $("amountField"), amountInput = $("amountInput");
  const amountError = $("amountError"), categoryGrid = $("categoryGrid");
  const categoryError = $("categoryError"), billDateInput = $("billDate"), descInput = $("descInput");
  const descCounter = $("descCounter"), dateError = $("dateError");
  const modalSubmit = $("modalSubmit");
  // 细分编辑器
  const breakdownRows = $("breakdownRows"), breakdownSummary = $("breakdownSummary");
  const bdSystemRow = $("bdSystemRow"), bdSystemAmount = $("bdSystemAmount");
  const bdAllocated = $("bdAllocated"), bdRemainWrap = $("bdRemainWrap"), bdRemain = $("bdRemain");
  const bdFullTag = $("bdFullTag"), bdOverTag = $("bdOverTag"), bdOver = $("bdOver");
  const addBreakdownBtn = $("addBreakdownBtn"), breakdownError = $("breakdownError");
  const breakdownCounter = $("breakdownCounter");
  const confirmModal = $("confirmModal"), confirmTitle = $("confirmTitle"), confirmText = $("confirmText");
  const confirmOk = $("confirmOk"), confirmCancel = $("confirmCancel");
  const toastStack = $("toastStack");
  const authOverlay = $("authOverlay"), authForm = $("authForm"), authTitle = $("authTitle");
  const authUsername = $("authUsername"), authPassword = $("authPassword");
  const authError = $("authError"), authSubmit = $("authSubmit"), authToggleBtn = $("authToggleBtn");
  // 登录弹窗内的后端地址设置：连不上时不用进主界面也能改（顶栏齿轮会被登录遮罩挡住）
  const authApiBase = $("authApiBase"), authApiSaveBtn = $("authApiSaveBtn");
  // 沿用现有 $，所有 ID 与资料 / 注册区块的 HTML 对应。
  const navAvatar = $("navAvatar"), profileBtn = $("profileBtn"), profileModal = $("profileModal");
  const profileForm = $("profileForm"), profileNickname = $("profileNickname"), profileEmail = $("profileEmail");
  const profileSubmit = $("profileSubmit"), profileError = $("profileError"), avatarError = $("avatarError");
  const avatarFile = $("avatarFile"), avatarPreview = $("avatarPreview");
  const registerNickname = $("registerNickname"), registerEmail = $("registerEmail");
  const registerAvatarFile = $("registerAvatarFile"), registerAvatarPreview = $("registerAvatarPreview");
  const registerAvatarError = $("registerAvatarError");

  /* ==================== 工具函数 ==================== */

  const nf2 = new Intl.NumberFormat("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const toNum = (v) => Number(v) || 0;
  const fmtMoney = (v) => {
    const n = toNum(v);
    return (n < 0 ? "-¥" : "¥") + nf2.format(Math.abs(n));
  };

  /** 表单金额字符串 → Number；非「正数、最多两位小数」返回 null（细分行/总金额共用） */
  const parseMoney = (v) => {
    const s = String(v ?? "").trim();
    return /^\d+(\.\d{1,2})?$/.test(s) && Number(s) > 0 ? Number(s) : null;
  };

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  function todayStr() {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  }

  function catEmoji(categoryId, type) {
    const c = state.catById.get(categoryId);
    return (c && ICON_EMOJI[c.icon]) || (type === "income" ? "💰" : "🧾");
  }

  function catTint(name) {
    let h = 0;
    for (const ch of String(name)) h = (h * 31 + ch.codePointAt(0)) >>> 0;
    return TINTS[h % TINTS.length];
  }

  function debounce(fn, ms) {
    let t;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  }

  /** 401（登录态失效）统一处理：返回 true 表示已接管 */
  function handleAuthError(e) {
    // API 层已标记为过时的响应不应污染当前账号的界面。
    if (e && e.stale) return true;
    if (!e || !e.auth) return false;
    toast("登录已失效，请重新登录", "error");
    showLogin();
    return true;
  }

  /* ==================== Toast ==================== */

  function toast(message, type = "success") {
    const el = document.createElement("div");
    el.className = `toast ${type}`;
    el.innerHTML = `${type === "success" ? SVG.check : SVG.alert}<span>${escapeHtml(message)}</span>`;
    toastStack.appendChild(el);
    setTimeout(() => {
      el.classList.add("leaving");
      el.addEventListener("animationend", () => el.remove(), { once: true });
    }, 3200);
  }

  /* ==================== 确认弹窗 ==================== */

  let confirmResolve = null;

  function confirmDialog({ title, text }) {
    confirmTitle.textContent = title;
    confirmText.innerHTML = text;
    confirmModal.hidden = false;
    document.body.style.overflow = "hidden";
    return new Promise((resolve) => { confirmResolve = resolve; });
  }

  function closeConfirm(result) {
    if (confirmModal.hidden) return;
    confirmModal.hidden = true;
    // 关闭一层弹窗时仍需照顾其他可见弹窗。
    syncScrollLock();
    if (confirmResolve) { confirmResolve(result); confirmResolve = null; }
  }

  /* ==================== 数字滚动动画 ==================== */

  function animateMoney(el, target) {
    const from = parseFloat(el.dataset.value || "0");
    el.dataset.value = target;
    if (from === target) { el.textContent = fmtMoney(target); return; }
    const dur = 650;
    const t0 = performance.now();
    (function tick(t) {
      const p = Math.min(1, (t - t0) / dur);
      const eased = 1 - Math.pow(1 - p, 3);
      el.textContent = fmtMoney(from + (target - from) * eased);
      if (p < 1) requestAnimationFrame(tick);
    })(t0);
  }

  /* ==================== 用户资料与头像（两处共用） ==================== */

  /** 与 Java String.trim 对齐，避免前后端对首尾空白的解释不同。 */
  function normalizeOptional(value) {
    const normalized = String(value ?? "").replace(/^[\u0000- ]+|[\u0000- ]+$/g, "");
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

  /* ==================== 登录 / 注册 ==================== */

  let authMode = "login";   // login | register
  // 与 phase 分开：一个控制网络/提交互斥，一个记录业务完成阶段。
  let authSubmitting = false;

  function showLogin() {
    resetFeatureSession();
    // 会话代次已更新，旧请求的 finally 不应重新控制新登录表单。
    authSubmitting = false;
    authPassword.value = "";
    setAuthMode("login");
    authOverlay.hidden = false;
    authApiBase.value = API.getBase();   // 回填当前后端地址，方便确认/修改
    syncScrollLock();
    setTimeout(() => authUsername.focus(), 90);
  }

  function hideLogin() {
    authOverlay.hidden = true;
    syncScrollLock();
  }

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

  /** 唯一的认证提交入口：注册 → 登录 →（有头像时）上传头像，任何一步失败都不重复建号。 */
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

  /** 登录成功后的入场：拿用户 → 显示身份 → 拉分类字典 → 加载数据 */
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
      populateCategoryFilter();               // 分类筛选下拉（只依赖静态字典，登录期间渲染一次）
      setDateMode("single", { reload: false });   // 默认单日（今天）
      $("appLoadRetry").hidden = true;
      refresh();
    } catch (err) {
      if (generation !== profileState.sessionGeneration) return;
      if (handleAuthError(err)) return;
      $("appLoadRetry").hidden = false;
      toast("账号已登录，页面数据加载失败，可点击重新加载", "error");
    }
  }

  async function loadCategories() {
    const list = await API.listCategories();
    state.categories.expense = list.filter((c) => c.type === "expense");
    state.categories.income = list.filter((c) => c.type === "income");
    state.catById = new Map(list.map((c) => [c.id, c]));
  }

  /** 渲染分类筛选下拉：支出 / 收入两个 optgroup，与弹窗分类网格同源 */
  function populateCategoryFilter() {
    const groups = [["expense", "支出"], ["income", "收入"]];
    categoryFilter.innerHTML = `<option value="">全部分类</option>` + groups
      .map(([type, label]) => `
        <optgroup label="${label}">
          ${state.categories[type].map((c) =>
            `<option value="${c.id}">${ICON_EMOJI[c.icon] || "🧾"} ${escapeHtml(c.name)}</option>`).join("")}
        </optgroup>`)
      .join("");
  }

  /* ==================== 日期查询模式 ==================== */

  /** 切换查询模式：single 单日（默认今天） / range 范围 */
  function setDateMode(mode, { reload = true } = {}) {
    state.dateMode = mode;
    const isSingle = mode === "single";
    [...dateModeSeg.children].forEach((b) => b.classList.toggle("active", b.dataset.mode === mode));
    singleDateWrap.hidden = !isSingle;
    rangeDateWrap.hidden = isSingle;

    if (isSingle) {
      if (!singleDate.value) singleDate.value = todayStr();
      singleDate.max = todayStr();
      state.filters.start = singleDate.value;
      state.filters.end = singleDate.value;
    } else {
      if (!startDate.value) startDate.value = todayStr();
      if (!endDate.value) endDate.value = todayStr();
      state.filters.start = startDate.value;
      state.filters.end = endDate.value;
    }

    if (reload) {
      state.page = 1;
      clearSelection(false);
      refresh();
    }
  }

  /* ==================== 数据加载 ==================== */

  function refresh() {
    loadList();
    loadCharts();
  }

  async function loadList() {
    renderSkeleton();
    try {
      // 页面列表显式加载细分（图表的 listAllBills 仍传 false，不加载）
      const data = await API.listBills({
        ...state.filters, page: state.page, pageSize: state.pageSize, includeBreakdowns: true,
      });
      // 删除后当前页可能已空 → 回退到最后一页重取
      if (data.list.length === 0 && data.total > 0 && state.page > 1) {
        state.page = Math.ceil(data.total / state.pageSize);
        return loadList();
      }
      state.total = data.total;
      state.bills = data.list;
      // 展开状态只保留当前页还存在的账单
      const pageIds = new Set(data.list.map((b) => b.id));
      state.expandedBreakdowns = new Set([...state.expandedBreakdowns].filter((id) => pageIds.has(id)));
      updateStats(data);
      renderList();
      renderPagination();
    } catch (e) {
      if (handleAuthError(e)) return;
      state.bills = [];
      state.total = 0;
      updateStats({ income_total: 0, expense_total: 0, total: 0 });
      totalBadge.textContent = "加载失败";
      pageInfo.textContent = "";
      pagination.innerHTML = "";
      billList.innerHTML = `
        <div class="empty-state">
          <div style="font-size:52px; margin-bottom:12px;">🔌</div>
          <div class="empty-title">数据加载失败</div>
          <div class="empty-sub">${escapeHtml(e.message)}</div>
          <button class="btn btn-primary btn-sm" data-action="retry">重新加载</button>
        </div>`;
    }
  }

  async function loadCharts() {
    try {
      state.chartBills = await API.listAllBills(state.filters);
      renderCharts();
    } catch (e) {
      if (handleAuthError(e)) return;
      const failHtml = `<div class="chart-empty"><span>图表加载失败</span></div>`;
      trendChartEl.innerHTML = failHtml;
      donutChartEl.innerHTML = failHtml;
      donutLegendEl.innerHTML = "";
    }
  }

  /* ==================== 统计卡片 ==================== */

  function updateStats(data) {
    const income = toNum(data.income_total);
    const expense = toNum(data.expense_total);
    animateMoney(statIncome, income);
    animateMoney(statExpense, expense);
    animateMoney(statBalance, income - expense);
    statCount.textContent = `共 ${data.total} 笔`;
    totalBadge.textContent = `${data.total} 笔`;
  }

  /* ==================== 账单列表渲染 ==================== */

  function renderSkeleton() {
    const row = `
      <div class="skeleton-row">
        <div class="skeleton-line sk-badge"></div>
        <div class="sk-lines">
          <div class="skeleton-line" style="width:38%"></div>
          <div class="skeleton-line" style="width:22%"></div>
        </div>
        <div class="skeleton-line" style="width:70px"></div>
      </div>`;
    billList.innerHTML = `<div class="skeleton-group">${row.repeat(5)}</div>`;
  }

  function fmtGroupHeader(dateStr) {
    const d = new Date(dateStr + "T00:00:00");
    const y = d.getFullYear();
    const main = (y === new Date().getFullYear())
      ? `${d.getMonth() + 1}月${d.getDate()}日`
      : `${y}年${d.getMonth() + 1}月${d.getDate()}日`;
    return { main, week: WEEKDAYS[d.getDay()] };
  }

  function renderList() {
    if (!state.bills.length) {
      billList.innerHTML = `
        <div class="empty-state">
          <div style="font-size:54px; margin-bottom:12px;">🧾</div>
          <div class="empty-title">暂无账单</div>
          <div class="empty-sub">当前筛选条件下没有记录，点右下角记一笔吧</div>
          <button class="btn btn-primary btn-sm" data-action="add">记一笔</button>
        </div>`;
      return;
    }

    // 按日期分组（保持后端排序）
    const groups = new Map();
    state.bills.forEach((b) => {
      if (!groups.has(b.bill_date)) groups.set(b.bill_date, []);
      groups.get(b.bill_date).push(b);
    });

    let rowIndex = 0;
    let html = "";
    for (const [date, list] of groups) {
      const inc = list.filter((b) => b.type === "income").reduce((s, b) => s + toNum(b.amount), 0);
      const exp = list.filter((b) => b.type === "expense").reduce((s, b) => s + toNum(b.amount), 0);
      const hd = fmtGroupHeader(date);
      html += `
        <div class="bill-group">
          <div class="group-header">
            <span class="group-date">${hd.main}<span class="weekday">${hd.week}</span></span>
            <span class="group-subtotal">
              ${inc > 0 ? `<span class="income">收 ${fmtMoney(inc)}</span>` : ""}
              ${exp > 0 ? `<span class="expense">支 ${fmtMoney(exp)}</span>` : ""}
            </span>
          </div>`;
      for (const b of list) {
        const isIncome = b.type === "income";
        const checked = state.selection.has(b.id);
        // 细分提示只统计用户显式项（系统“其他”是展示层的虚拟行）
        const userBds = Array.isArray(b.breakdowns) ? b.breakdowns.filter((x) => x.source === "user") : [];
        const hasBds = userBds.length > 0;
        const expanded = state.expandedBreakdowns.has(b.id);
        html += `
          <div class="bill-item">
            <div class="bill-row${checked ? " checked" : ""}" data-id="${b.id}" style="--i:${rowIndex++}">
              <input type="checkbox" class="row-check" ${checked ? "checked" : ""} aria-label="选择这条账单">
              <span class="cat-badge" style="background:${catTint(b.category_name)}">${catEmoji(b.category_id, b.type)}</span>
              <div class="bill-info">
                <span class="bill-cat">${escapeHtml(b.category_name)}</span>
                ${b.description ? `<span class="bill-desc">${escapeHtml(b.description)}</span>` : ""}
                ${hasBds ? `
                  <button type="button" class="bd-toggle${expanded ? " open" : ""}" data-action="toggle-bd"
                          title="展开 / 收起细分明细">已细分 ${userBds.length} 项<span class="bd-caret">${SVG.chevD}</span></button>` : ""}
              </div>
              <span class="bill-amount ${b.type}">${isIncome ? "+" : "−"}${fmtMoney(b.amount)}</span>
              <div class="row-actions">
                <button class="row-action-btn" data-action="edit" title="编辑">${SVG.pencil}</button>
                <button class="row-action-btn danger" data-action="del" title="删除">${SVG.trash}</button>
              </div>
            </div>
            ${hasBds && expanded ? renderBreakdownPanel(b) : ""}
          </div>`;
      }
      html += `</div>`;
    }
    billList.innerHTML = html;
  }

  /* ==================== 细分面板（列表展开区，只读展示） ==================== */

  function renderBreakdownPanel(bill) {
    // 后端保证系统“其他”始终排在末尾；这里只渲染，不提供编辑/删除/复选
    const rows = bill.breakdowns.map((x) => `
      <div class="bd-line${x.source === "system" ? " bd-line-system" : ""}">
        <span class="bd-line-name">${escapeHtml(x.item_name)}${x.source === "system" ? `<em class="bd-tag">自动补足</em>` : ""}</span>
        <span class="bd-line-amount num">${fmtMoney(x.amount)}</span>
      </div>`).join("");
    return `<div class="bd-panel">${rows}</div>`;
  }

  /* ==================== 分页 ==================== */

  function pageNumbers(p, total) {
    if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
    const set = new Set([1, 2, p - 1, p, p + 1, total - 1, total].filter((n) => n >= 1 && n <= total));
    const arr = [...set].sort((a, b) => a - b);
    const out = [];
    arr.forEach((n, i) => {
      if (i > 0 && n - arr[i - 1] > 1) out.push("...");
      out.push(n);
    });
    return out;
  }

  function renderPagination() {
    const totalPages = Math.max(1, Math.ceil(state.total / state.pageSize));
    pageInfo.textContent = `第 ${state.page} / ${totalPages} 页 · 共 ${state.total} 条`;
    const p = state.page;
    const parts = [];
    parts.push(`<button class="page-btn" data-page="${p - 1}" ${p <= 1 ? "disabled" : ""} title="上一页">${SVG.chevL}</button>`);
    pageNumbers(p, totalPages).forEach((n) => {
      parts.push(n === "..."
        ? `<span class="page-ellipsis">…</span>`
        : `<button class="page-btn${n === p ? " active" : ""}" data-page="${n}">${n}</button>`);
    });
    parts.push(`<button class="page-btn" data-page="${p + 1}" ${p >= totalPages ? "disabled" : ""} title="下一页">${SVG.chevR}</button>`);
    pagination.innerHTML = parts.join("");
  }

  /* ==================== 图表渲染 ==================== */

  function renderCharts() {
    const bills = state.chartBills;

    // 趋势图：按天 / 按月自适应分桶
    const { buckets, caption } = bucketize(bills, state.filters);
    trendSub.textContent = caption;
    Charts.renderTrend(trendChartEl, buckets);

    // 环形图：按分类聚合
    const agg = new Map();
    bills
      .filter((b) => b.type === state.donutType)
      .forEach((b) => agg.set(b.category_name, (agg.get(b.category_name) || 0) + toNum(b.amount)));
    const data = [...agg.entries()].map(([name, value]) => ({ name, value }));
    donutSub.textContent = data.length ? `共 ${data.length} 个分类` : "当前范围内暂无数据";
    Charts.renderDonut(donutChartEl, donutLegendEl, data, {
      centerLabel: state.donutType === "expense" ? "总支出" : "总收入",
    });
  }

  function bucketize(bills, filters) {
    if (!bills.length) return { buckets: [], caption: "暂无数据" };
    const sorted = bills.map((b) => b.bill_date).sort();
    const start = filters.start || sorted[0];
    const end = filters.end || sorted[sorted.length - 1];
    const days = Math.round((new Date(end) - new Date(start)) / 86400000) + 1;

    if (days <= 93) {
      // 按天：补齐范围内每一天
      const map = new Map();
      const cursor = new Date(start + "T00:00:00");
      for (let i = 0; i < days; i++) {
        const k = `${cursor.getFullYear()}-${String(cursor.getMonth() + 1).padStart(2, "0")}-${String(cursor.getDate()).padStart(2, "0")}`;
        map.set(k, { income: 0, expense: 0 });
        cursor.setDate(cursor.getDate() + 1);
      }
      bills.forEach((b) => {
        const slot = map.get(b.bill_date);
        if (slot) slot[b.type === "income" ? "income" : "expense"] += toNum(b.amount);
      });
      const buckets = [...map.entries()].map(([k, v]) => {
        const d = new Date(k + "T00:00:00");
        return {
          label: `${d.getMonth() + 1}/${d.getDate()}`,
          fullLabel: `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`,
          ...v,
        };
      });
      return { buckets, caption: `按天统计 · 共 ${days} 天` };
    }

    // 按月：补齐范围内每一月
    const map = new Map();
    bills.forEach((b) => {
      const k = b.bill_date.slice(0, 7);
      if (!map.has(k)) map.set(k, { income: 0, expense: 0 });
      map.get(k)[b.type === "income" ? "income" : "expense"] += toNum(b.amount);
    });
    const buckets = [];
    let [y, m] = start.slice(0, 7).split("-").map(Number);
    const [ey, em] = end.slice(0, 7).split("-").map(Number);
    while (y < ey || (y === ey && m <= em)) {
      const k = `${y}-${String(m).padStart(2, "0")}`;
      const v = map.get(k) || { income: 0, expense: 0 };
      buckets.push({ label: `${m}月`, fullLabel: `${y}年${m}月`, ...v });
      m += 1;
      if (m > 12) { m = 1; y += 1; }
    }
    return { buckets, caption: `按月统计 · 共 ${buckets.length} 个月` };
  }

  /* ==================== 选择 & 批量操作 ==================== */

  function findBill(id) {
    return state.bills.find((b) => b.id === id);
  }

  function clearSelection(rerender = true) {
    state.selection.clear();
    updateBatchBar();
    updateSelectAllState();
    if (rerender) renderList();
  }

  function updateSelectAllState() {
    const pageIds = state.bills.map((b) => b.id);
    const selectedOnPage = pageIds.filter((id) => state.selection.has(id)).length;
    selectAll.checked = pageIds.length > 0 && selectedOnPage === pageIds.length;
    selectAll.classList.toggle("indeterminate", selectedOnPage > 0 && selectedOnPage < pageIds.length);
  }

  function updateBatchBar() {
    const n = state.selection.size;
    batchCount.textContent = n;
    if (n > 0) {
      let inc = 0, exp = 0;
      state.bills.forEach((b) => {
        if (state.selection.has(b.id)) {
          if (b.type === "income") inc += toNum(b.amount); else exp += toNum(b.amount);
        }
      });
      batchSum.textContent = `支 ${fmtMoney(exp)} · 收 ${fmtMoney(inc)}`;
      if (batchBar.hidden) {
        batchBar.hidden = false;
        requestAnimationFrame(() => batchBar.classList.add("show"));
      } else {
        batchBar.classList.add("show");
      }
    } else {
      batchBar.classList.remove("show");
    }
  }

  async function handleDelete(ids) {
    const n = ids.length;
    let text;
    if (n === 1) {
      const b = findBill(ids[0]);
      text = `确定要删除「<b>${escapeHtml(b ? b.category_name : "")}</b>」这条账单吗？此操作不可恢复。`;
    } else {
      text = `确定要删除选中的 <b>${n}</b> 条账单吗？此操作不可恢复。`;
    }
    const ok = await confirmDialog({ title: "删除账单", text });
    if (!ok) return;

    confirmOk.disabled = true;
    try {
      const count = await API.deleteBills(ids);
      toast(`成功删除 ${count} 条`);
      state.selection.clear();
      updateBatchBar();
      updateSelectAllState();
      refresh();
    } catch (e) {
      if (handleAuthError(e)) return;
      toast(e.message, "error");
    } finally {
      confirmOk.disabled = false;
    }
  }

  /* ==================== 记账 / 编辑弹窗 ==================== */

  function setFormType(type) {
    state.formType = type;
    [...typeToggle.children].forEach((btn) => btn.classList.toggle("active", btn.dataset.type === type));
    amountField.classList.toggle("expense-mode", type === "expense");
    amountField.classList.toggle("income-mode", type === "income");
    renderCategoryGrid();
    selectCategory(null);
  }

  function renderCategoryGrid() {
    categoryGrid.innerHTML = state.categories[state.formType]
      .map((c) => `
        <button type="button" class="cat-chip" data-id="${c.id}">
          <span class="cat-emoji">${ICON_EMOJI[c.icon] || "🧾"}</span>${escapeHtml(c.name)}
        </button>`)
      .join("");
  }

  function selectCategory(id) {
    state.formCategory = id;   // null 或分类 id（数字）
    [...categoryGrid.children].forEach((chip) =>
      chip.classList.toggle("active", chip.dataset.id === String(id)));
    hideError(categoryError);
  }

  function showError(el, msg) {
    el.textContent = msg;
    el.hidden = false;
    // 重触发 shake 动画
    el.style.animation = "none";
    void el.offsetWidth;
    el.style.animation = "";
  }
  function hideError(el) { el.hidden = true; }
  function clearErrors() { [amountError, categoryError, dateError, breakdownError].forEach(hideError); }

  /* ==================== 细分编辑器 ==================== */

  // 渲染动态行（formBreakdowns 是唯一数据源，输入变化由事件委托写回数组）
  function renderBreakdownRows() {
    breakdownRows.innerHTML = state.formBreakdowns.map((row, i) => `
      <div class="bd-row" data-index="${i}">
        <input class="input bd-name" placeholder="名称" maxlength="50" autocomplete="off" value="${escapeHtml(row.item_name)}">
        <input class="input num bd-amount" placeholder="0.00" inputmode="decimal" autocomplete="off" value="${row.amount ?? ""}">
        <button type="button" class="bd-del" data-bd-del="${i}" title="删除该行">${SVG.trash}</button>
      </div>`).join("");
    const n = state.formBreakdowns.length;
    addBreakdownBtn.disabled = n >= MAX_BREAKDOWNS;   // 20 行后禁用添加
    breakdownCounter.textContent = n ? `${n} / ${MAX_BREAKDOWNS} 项` : `可选 · 最多 ${MAX_BREAKDOWNS} 项`;
  }

  /** 实时合计（整数分运算，规避 0.1+0.2 浮点误差）：
   *  整行全空忽略；半填的行计入 incomplete；返回剩余分与超额标志 */
  function breakdownTotals() {
    const totalParsed = parseMoney(amountInput.value);
    const totalCents = totalParsed === null ? 0 : Math.round(totalParsed * 100);
    let explicitCents = 0;
    let incomplete = false;
    state.formBreakdowns.forEach((r) => {
      const name = String(r.item_name ?? "").trim();
      const amount = parseMoney(r.amount);
      if (name === "" && amount === null) return;              // 整行全空：不计
      if (name === "" || amount === null) { incomplete = true; return; }
      explicitCents += Math.round(amount * 100);
    });
    return { totalCents, explicitCents, remainingCents: totalCents - explicitCents, incomplete };
  }

  // 实时反馈：无行隐藏合计；不足显示“其他”；分满显示已全部细分；超额显示超出金额
  function updateBreakdownSummary() {
    const hasRows = state.formBreakdowns.length > 0;
    breakdownSummary.hidden = !hasRows;
    if (!hasRows) return;

    const t = breakdownTotals();
    bdAllocated.textContent = fmtMoney(t.explicitCents / 100);

    const hasRemainder = t.remainingCents > 0;
    bdSystemRow.hidden = !hasRemainder;
    bdRemainWrap.hidden = !hasRemainder;
    if (hasRemainder) {
      bdSystemAmount.textContent = fmtMoney(t.remainingCents / 100);
      bdRemain.textContent = fmtMoney(t.remainingCents / 100);
    }
    bdFullTag.hidden = t.remainingCents !== 0;
    bdOverTag.hidden = t.remainingCents >= 0;
    if (t.remainingCents < 0) bdOver.textContent = fmtMoney(-t.remainingCents / 100);
  }

  /** 编辑回显：只回填 source=user 的显式项；系统“其他”忽略，由当前总金额重新计算。
   *  列表数据未加载细分（breakdowns 为 null）时先调详情接口补拉。 */
  function fillBreakdownsFromBill(bill) {
    if (Array.isArray(bill.breakdowns)) {
      state.formBreakdowns = bill.breakdowns
        .filter((b) => b.source === "user")
        .map((b) => ({ item_name: b.item_name, amount: toNum(b.amount).toFixed(2) }));
      renderBreakdownRows();
      updateBreakdownSummary();
    } else {
      const editingId = bill.id;
      API.getBill(editingId)
        .then((detail) => {
          if (state.editingId !== editingId || billModal.hidden) return;   // 弹窗已关/已切账单：丢弃
          fillBreakdownsFromBill(detail);
        })
        .catch(() => { /* 拉取失败按无细分处理，提交以后端校验为准 */ });
    }
  }

  function openModal(bill = null) {
    state.editingId = bill ? bill.id : null;
    modalTitle.textContent = bill ? "编辑账单" : "记一笔";
    modalSubmit.querySelector(".btn-text").textContent = bill ? "保存修改" : "保存";

    setFormType(bill ? bill.type : "expense");
    amountInput.value = bill ? toNum(bill.amount).toFixed(2) : "";

    // 细分编辑器：新增清空；编辑回显只取用户显式项（可能需要补拉详情）
    state.formBreakdowns = [];
    renderBreakdownRows();
    updateBreakdownSummary();
    if (bill) fillBreakdownsFromBill(bill);

    if (bill) selectCategory(bill.category_id);

    billDateInput.value = bill ? bill.bill_date : todayStr();
    billDateInput.max = todayStr();
    descInput.value = (bill && bill.description) || "";
    descCounter.textContent = `${descInput.value.length} / 200`;

    clearErrors();
    billModal.hidden = false;
    document.body.style.overflow = "hidden";
    setTimeout(() => amountInput.focus(), 90);
  }

  function closeModal() {
    billModal.hidden = true;
    // 关闭一层弹窗时仍需照顾其他可见弹窗。
    syncScrollLock();
  }

  function setSubmitting(on) {
    modalSubmit.disabled = on;
    modalSubmit.querySelector(".btn-spinner").hidden = !on;
    modalSubmit.querySelector(".btn-text").textContent = on
      ? "保存中…"
      : (state.editingId ? "保存修改" : "保存");
  }

  function validateForm() {
    clearErrors();
    let ok = true;

    const v = amountInput.value.trim();
    if (!v || !/^\d+(\.\d{1,2})?$/.test(v) || Number(v) <= 0) {
      showError(amountError, "请输入大于 0 的金额（最多两位小数）");
      ok = false;
    }

    if (!state.formCategory) {
      showError(categoryError, "请选择一个分类");
      ok = false;
    }

    if (!billDateInput.value) {
      showError(dateError, "请选择账单日期");
      ok = false;
    } else if (billDateInput.value > todayStr()) {
      showError(dateError, "账单日期不能是未来日期");
      ok = false;
    }

    // 细分行：有行时每行必须名称+金额齐全且金额 > 0，合计不得超过总金额，名称不得用保留名
    if (state.formBreakdowns.length > 0) {
      const t = breakdownTotals();
      if (t.incomplete) {
        showError(breakdownError, "每个细分项需同时填写名称和大于 0 的金额（最多两位小数）");
        ok = false;
      } else if (t.remainingCents < 0) {
        showError(breakdownError, "细分金额合计已超过账单总额，请调整");
        ok = false;
      } else if (state.formBreakdowns.some((r) => r.item_name.trim() === "其他")) {
        showError(breakdownError, "「其他」是系统保留名称，请换一个细分名称");
        ok = false;
      }
    }

    return ok;
  }

  /* ==================== 连接状态 ==================== */

  async function checkHealth() {
    try {
      await API.health();
      apiStatus.classList.add("ok");
      apiStatus.classList.remove("fail");
      apiStatusText.textContent = "已连接";
    } catch {
      apiStatus.classList.remove("ok");
      apiStatus.classList.add("fail");
      apiStatusText.textContent = "未连接";
    }
  }

  /* ==================== 主题 ==================== */

  function toggleTheme() {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    localStorage.setItem("pl_theme", next);
  }

  /* ==================== 事件绑定 ==================== */

  function bindEvents() {
    // 所有资料事件在页面启动时集中绑定，打开弹窗不重复绑定。
    bindProfileEvents();

    // 登录 / 注册
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

    // 登录弹窗内直接改后端地址：保存后立即重连检测，不刷新页面、不要求先登录
    authApiSaveBtn.addEventListener("click", () => {
      // 恢复流程不能悄悄切换到另一个后端继续上传。
      if (authSubmitting || registrationState.selecting || registrationState.phase !== "idle") return;
      const v = authApiBase.value.trim();
      if (!v) { toast("请输入后端地址", "error"); return; }
      API.setBase(v);
      authApiBase.value = API.getBase();
      toast("后端地址已保存，正在重新检测连接…");
      checkHealth();
    });

    // 退出登录：清 token 后整页重载，所有状态随之归零
    logoutBtn.addEventListener("click", () => {
      // 先使旧响应失效，再清令牌、刷新页面。
      resetFeatureSession();
      API.logout();
      location.reload();
    });

    // 顶部：主题 / 设置
    themeToggle.addEventListener("click", toggleTheme);

    settingsBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      settingsPopover.hidden = !settingsPopover.hidden;
      if (!settingsPopover.hidden) {
        apiBaseInput.value = API.getBase();
        apiBaseInput.focus();
      }
    });
    document.addEventListener("click", (e) => {
      if (!settingsPopover.hidden && !settingsPopover.contains(e.target) && !settingsBtn.contains(e.target)) {
        settingsPopover.hidden = true;
      }
    });
    apiSaveBtn.addEventListener("click", () => {
      const v = apiBaseInput.value.trim();
      if (!v) { toast("请输入后端地址", "error"); return; }
      API.setBase(v);
      location.reload();   // 换地址后整页重连（含登录态校验）
    });

    // 筛选工具栏
    typeSeg.addEventListener("click", (e) => {
      const btn = e.target.closest("button[data-type]");
      if (!btn) return;
      [...typeSeg.children].forEach((b) => b.classList.toggle("active", b === btn));
      state.filters.type = btn.dataset.type;
      // 类型与已选分类冲突 → 清掉分类筛选，避免必然为空的矛盾组合
      if (state.filters.type && state.filters.categoryId) {
        const cat = state.catById.get(state.filters.categoryId);
        if (cat && cat.type !== state.filters.type) {
          state.filters.categoryId = null;
          categoryFilter.value = "";
        }
      }
      state.page = 1;
      clearSelection(false);
      refresh();
    });

    categoryFilter.addEventListener("change", () => {
      const id = categoryFilter.value ? Number(categoryFilter.value) : null;
      state.filters.categoryId = id;
      // 分类决定收支类型：与当前类型筛选冲突时，自动跟随到该分类的类型
      if (id && state.filters.type) {
        const cat = state.catById.get(id);
        if (cat && cat.type !== state.filters.type) {
          state.filters.type = cat.type;
          [...typeSeg.children].forEach((b) => b.classList.toggle("active", b.dataset.type === cat.type));
        }
      }
      state.page = 1;
      clearSelection(false);
      refresh();
    });

    dateModeSeg.addEventListener("click", (e) => {
      const btn = e.target.closest("button[data-mode]");
      if (!btn || btn.dataset.mode === state.dateMode) return;
      setDateMode(btn.dataset.mode);
    });

    singleDate.addEventListener("change", () => {
      if (state.dateMode !== "single") return;
      state.filters.start = singleDate.value;
      state.filters.end = singleDate.value;
      state.page = 1;
      clearSelection(false);
      refresh();
    });

    startDate.addEventListener("change", () => {
      if (state.dateMode !== "range") return;
      state.filters.start = startDate.value;
      endDate.min = startDate.value || "";
      state.page = 1;
      clearSelection(false);
      refresh();
    });
    endDate.addEventListener("change", () => {
      if (state.dateMode !== "range") return;
      state.filters.end = endDate.value;
      startDate.max = endDate.value || "";
      state.page = 1;
      clearSelection(false);
      refresh();
    });

    orderBtn.addEventListener("click", () => {
      state.filters.order = state.filters.order === "desc" ? "asc" : "desc";
      orderBtn.classList.toggle("asc", state.filters.order === "asc");
      orderBtn.title = state.filters.order === "desc" ? "当前：最新优先，点击切换" : "当前：最早优先，点击切换";
      state.page = 1;
      refresh();
    });

    resetBtn.addEventListener("click", () => {
      state.filters = { start: todayStr(), end: todayStr(), type: "", categoryId: null, order: "desc" };
      state.page = 1;
      singleDate.value = todayStr();
      startDate.value = ""; endDate.value = "";
      startDate.max = ""; endDate.min = "";
      orderBtn.classList.remove("asc");
      [...typeSeg.children].forEach((b) => b.classList.toggle("active", b.dataset.type === ""));
      categoryFilter.value = "";
      setDateMode("single", { reload: false });
      clearSelection(false);
      refresh();
    });

    selectAll.addEventListener("change", () => {
      const pageIds = state.bills.map((b) => b.id);
      if (selectAll.checked) pageIds.forEach((id) => state.selection.add(id));
      else pageIds.forEach((id) => state.selection.delete(id));
      renderList();
      updateBatchBar();
      updateSelectAllState();
    });

    pageSizeSel.addEventListener("change", () => {
      state.pageSize = Number(pageSizeSel.value);
      state.page = 1;
      clearSelection(false);
      loadList();
    });

    // 环形图类型切换（不需要重新请求，本地聚合即可）
    donutTypeSeg.addEventListener("click", (e) => {
      const btn = e.target.closest("button[data-type]");
      if (!btn || btn.dataset.type === state.donutType) return;
      [...donutTypeSeg.children].forEach((b) => b.classList.toggle("active", b === btn));
      state.donutType = btn.dataset.type;
      renderCharts();
    });

    // 列表：编辑 / 删除 / 细分展开 / 空状态按钮（事件委托，动作彼此隔离）
    billList.addEventListener("click", (e) => {
      const actionBtn = e.target.closest("[data-action]");
      if (!actionBtn) return;
      const act = actionBtn.dataset.action;
      if (act === "add") { openModal(); return; }
      if (act === "retry") { refresh(); checkHealth(); return; }
      if (act === "toggle-bd") {
        const row = actionBtn.closest(".bill-row");
        if (!row) return;
        const id = Number(row.dataset.id);
        if (state.expandedBreakdowns.has(id)) state.expandedBreakdowns.delete(id);
        else state.expandedBreakdowns.add(id);
        renderList();
        return;
      }
      const row = actionBtn.closest(".bill-row");
      if (!row) return;
      const id = Number(row.dataset.id);
      if (act === "edit") openModal(findBill(id));
      if (act === "del") handleDelete([id]);
    });

    billList.addEventListener("change", (e) => {
      if (!e.target.classList.contains("row-check")) return;
      const row = e.target.closest(".bill-row");
      const id = Number(row.dataset.id);
      if (e.target.checked) state.selection.add(id);
      else state.selection.delete(id);
      row.classList.toggle("checked", e.target.checked);
      updateBatchBar();
      updateSelectAllState();
    });

    // 分页（事件委托）
    pagination.addEventListener("click", (e) => {
      const btn = e.target.closest("button[data-page]");
      if (!btn || btn.disabled) return;
      const target = Number(btn.dataset.page);
      const totalPages = Math.max(1, Math.ceil(state.total / state.pageSize));
      if (target < 1 || target > totalPages || target === state.page) return;
      state.page = target;
      clearSelection(false);
      loadList();
      document.querySelector(".list-card").scrollIntoView({ behavior: "smooth", block: "start" });
    });

    // 批量操作栏
    batchDeleteBtn.addEventListener("click", () => handleDelete([...state.selection]));
    batchCancelBtn.addEventListener("click", () => clearSelection());

    // FAB & 弹窗
    addBtn.addEventListener("click", () => openModal());
    $("modalClose").addEventListener("click", closeModal);
    $("modalCancel").addEventListener("click", closeModal);
    billModal.addEventListener("mousedown", (e) => { if (e.target === billModal) closeModal(); });

    typeToggle.addEventListener("click", (e) => {
      const btn = e.target.closest("button[data-type]");
      if (!btn || btn.dataset.type === state.formType) return;
      setFormType(btn.dataset.type);
    });

    categoryGrid.addEventListener("click", (e) => {
      const chip = e.target.closest(".cat-chip");
      if (!chip) return;
      selectCategory(Number(chip.dataset.id));
    });

    amountInput.addEventListener("input", () => {
      let v = amountInput.value.replace(/[^\d.]/g, "");
      const dot = v.indexOf(".");
      if (dot !== -1) v = v.slice(0, dot + 1) + v.slice(dot + 1).replace(/\./g, "");
      let [int, dec] = v.split(".");
      int = int.slice(0, 10); // 后端 DECIMAL(12,2) → 整数最多 10 位
      amountInput.value = dec !== undefined ? `${int}.${dec.slice(0, 2)}` : int;
      hideError(amountError);
      updateBreakdownSummary();   // 总金额变化 → 系统“其他”/剩余实时重算
    });
    amountInput.addEventListener("blur", () => {
      const v = amountInput.value.trim();
      if (v && /^\d+(\.\d{0,2})?$/.test(v)) amountInput.value = Number(v).toFixed(2);
      updateBreakdownSummary();
    });

    // 细分编辑器：添加 / 删除 / 输入全部走事件委托
    addBreakdownBtn.addEventListener("click", () => {
      if (state.formBreakdowns.length >= MAX_BREAKDOWNS) return;
      state.formBreakdowns.push({ item_name: "", amount: "" });
      renderBreakdownRows();
      updateBreakdownSummary();
      const names = breakdownRows.querySelectorAll(".bd-name");
      if (names.length) names[names.length - 1].focus();
    });

    breakdownRows.addEventListener("input", (e) => {
      const row = e.target.closest(".bd-row");
      if (!row) return;
      const i = Number(row.dataset.index);
      if (e.target.classList.contains("bd-name")) {
        state.formBreakdowns[i].item_name = e.target.value;
      } else if (e.target.classList.contains("bd-amount")) {
        let v = e.target.value.replace(/[^\d.]/g, "");
        const dot = v.indexOf(".");
        if (dot !== -1) v = v.slice(0, dot + 1) + v.slice(dot + 1).replace(/\./g, "");
        const [int, dec] = v.split(".");
        e.target.value = dec !== undefined ? `${int.slice(0, 10)}.${dec.slice(0, 2)}` : int;
        state.formBreakdowns[i].amount = e.target.value;
      } else {
        return;
      }
      hideError(breakdownError);
      updateBreakdownSummary();
    });

    breakdownRows.addEventListener("focusout", (e) => {
      if (!e.target.classList.contains("bd-amount")) return;
      const row = e.target.closest(".bd-row");
      const i = Number(row.dataset.index);
      const n = parseMoney(e.target.value);
      if (n !== null) {
        e.target.value = n.toFixed(2);      // 失焦统一补成两位小数
        state.formBreakdowns[i].amount = e.target.value;
        updateBreakdownSummary();
      }
    });

    breakdownRows.addEventListener("click", (e) => {
      const btn = e.target.closest("[data-bd-del]");
      if (!btn) return;
      state.formBreakdowns.splice(Number(btn.dataset.bdDel), 1);
      renderBreakdownRows();
      updateBreakdownSummary();
    });

    descInput.addEventListener("input", () => {
      descCounter.textContent = `${descInput.value.length} / 200`;
    });

    billDateInput.addEventListener("change", () => hideError(dateError));

    billForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (!validateForm()) return;

      // v4.0：只提交 category_id，类型由后端从分类推导
      // v4.1：细分整体提交（空数组=不启用/清空），系统“其他”不序列化
      const payload = {
        category_id: state.formCategory,
        amount: amountInput.value.trim(),   // 字符串提交，后端解析为 Decimal
        description: descInput.value.trim() || null,
        bill_date: billDateInput.value,
        breakdowns: state.formBreakdowns.map((r) => ({
          item_name: r.item_name.trim(),
          amount: parseMoney(r.amount).toFixed(2),
        })),
      };

      setSubmitting(true);
      try {
        if (state.editingId) {
          await API.updateBill(state.editingId, payload);
          toast("更新成功");
          closeModal();
          refresh();
        } else {
          await API.addBill(payload);
          toast("添加成功");
          closeModal();
          state.page = 1; // 回到第一页，立即看到新账单
          refresh();
        }
      } catch (err) {
        if (handleAuthError(err)) return;
        toast(err.message, "error");
      } finally {
        setSubmitting(false);
      }
    });

    // 确认弹窗
    confirmOk.addEventListener("click", () => closeConfirm(true));
    confirmCancel.addEventListener("click", () => closeConfirm(false));
    confirmModal.addEventListener("mousedown", (e) => { if (e.target === confirmModal) closeConfirm(false); });

    // 快捷键：N 记一笔，Esc 关闭弹层（登录浮层不可关闭、快捷键也不响应）
    document.addEventListener("keydown", (e) => {
      if (!authOverlay.hidden) return;
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
      if (e.key === "Escape") {
        if (!confirmModal.hidden) return closeConfirm(false);
        if (!billModal.hidden) return closeModal();
        if (!settingsPopover.hidden) settingsPopover.hidden = true;
        return;
      }
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test((document.activeElement || {}).tagName || "");
      if ((e.key === "n" || e.key === "N") && !typing && billModal.hidden && confirmModal.hidden) {
        e.preventDefault();
        openModal();
      }
    });

    // 窗口尺寸变化 → 重绘图表（去抖）
    window.addEventListener("resize", debounce(() => renderCharts(), 220));
  }

  /* ==================== 启动 ==================== */

  bindEvents();
  checkHealth();
  setInterval(checkHealth, 30000);

  // 有 token 先验证（过期会被踢回登录页）；没有就直接进登录页
  if (API.getToken()) {
    enterApp().catch(() => {
      API.logout();
      showLogin();
    });
  } else {
    showLogin();
  }
})();

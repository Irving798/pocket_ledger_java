/* ============================================================
   口袋账本 —— 纯 SVG 图表模块（零依赖）
   renderTrend  收支趋势柱状图（悬停 tooltip、生长动画）
   renderDonut  分类构成环形图（图例联动高亮、扫入动画）
   ============================================================ */

const Charts = (() => {
  "use strict";

  const SVG_NS = "http://www.w3.org/2000/svg";

  const PALETTE = [
    "#818cf8", "#c084fc", "#f472b6", "#fb7185",
    "#fbbf24", "#34d399", "#22d3ee", "#a3e635",
  ];
  const OTHER_COLOR = "#64748b";
  const INCOME_COLOR = "#34d399";
  const EXPENSE_COLOR = "#fb7185";

  const moneyFmt = new Intl.NumberFormat("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const fmt2 = (v) => moneyFmt.format(v);

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  function svgEl(tag, attrs = {}) {
    const e = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    return e;
  }

  /** 坐标轴数字紧凑格式：1200 → 1.2k，12000 → 1.2万 */
  function fmtCompact(v) {
    if (v >= 10000) {
      const s = v / 10000;
      return (s >= 100 ? Math.round(s) : +s.toFixed(1)) + " 万";
    }
    if (v >= 1000) {
      const s = v / 1000;
      return (s >= 100 ? Math.round(s) : +s.toFixed(1)) + "k";
    }
    return String(Math.round(v));
  }

  /** 把最大值向上取整成“好看”的刻度（1 / 1.5 / 2 / 2.5 / 5 … ×10ⁿ） */
  function niceMax(v) {
    if (v <= 0) return 100;
    const pow = Math.pow(10, Math.floor(Math.log10(v)));
    const n = v / pow;
    for (const s of [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) {
      if (n <= s) return s * pow;
    }
    return 10 * pow;
  }

  function emptyNode(text) {
    const d = document.createElement("div");
    d.className = "chart-empty";
    d.innerHTML = `
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="M3 3v16a2 2 0 0 0 2 2h16"/><path d="m7 14 4-4 4 3 5-6"/>
      </svg>
      <span>${escapeHtml(text)}</span>`;
    return d;
  }

  /* ------------------------------------------------------------
     收支趋势柱状图
     buckets: [{ label: "8/26", fullLabel: "2026年8月26日", income, expense }]
     ------------------------------------------------------------ */
  function renderTrend(container, buckets) {
    container.innerHTML = "";
    if (!buckets.length || buckets.every((b) => b.income === 0 && b.expense === 0)) {
      container.appendChild(emptyNode("当前筛选范围内暂无收支数据"));
      return;
    }

    const W = container.clientWidth || 640;
    const H = container.clientHeight || 264;
    const padL = 50, padR = 12, padT = 16, padB = 30;
    const plotW = W - padL - padR;
    const plotH = H - padT - padB;

    const maxV = niceMax(Math.max(...buckets.map((b) => Math.max(b.income, b.expense))));
    const baseY = padT + plotH;
    const y = (v) => padT + (1 - v / maxV) * plotH;

    const svg = svgEl("svg", { viewBox: `0 0 ${W} ${H}`, role: "img" });

    // 横向网格线 + Y 轴刻度
    const GRID = 4;
    for (let i = 0; i <= GRID; i++) {
      const v = (maxV / GRID) * i;
      const yy = y(v);
      if (i > 0) {
        svg.appendChild(svgEl("line", {
          x1: padL, x2: W - padR, y1: yy, y2: yy, class: "chart-grid-line",
        }));
      }
      const t = svgEl("text", { x: padL - 10, y: yy + 3.5, "text-anchor": "end", class: "chart-axis-text" });
      t.textContent = i === 0 ? "0" : fmtCompact(v);
      svg.appendChild(t);
    }
    // 基线
    svg.appendChild(svgEl("line", { x1: padL, x2: W - padR, y1: baseY, y2: baseY, stroke: "var(--border-strong)", "stroke-width": 1 }));

    const n = buckets.length;
    const slot = plotW / n;
    const barW = Math.max(3, Math.min(13, slot * 0.26));
    const gap = Math.min(4, barW * 0.4);

    // X 轴标签（自动稀疏抽稀）
    const step = Math.ceil(n / Math.max(2, Math.floor(plotW / 58)));
    buckets.forEach((b, i) => {
      if (i % step !== 0 && i !== n - 1) return;
      const cx = padL + slot * i + slot / 2;
      const t = svgEl("text", { x: cx, y: H - 9, "text-anchor": "middle", class: "chart-axis-text" });
      t.textContent = b.label;
      svg.appendChild(t);
    });

    // 柱体（收入左、支出右，成对）
    const barsGroup = svgEl("g", {});
    buckets.forEach((b, i) => {
      const cx = padL + slot * i + slot / 2;
      [
        [b.income, INCOME_COLOR, cx - barW - gap / 2],
        [b.expense, EXPENSE_COLOR, cx + gap / 2],
      ].forEach(([val, color, x]) => {
        if (val <= 0) return;
        const bh = Math.max(3, plotH * (val / maxV));
        const rect = svgEl("rect", {
          x, y: baseY - bh, width: barW, height: bh,
          rx: Math.min(barW / 2, 4),
          fill: color, class: "trend-bar",
          style: `animation-delay:${Math.min(i * 26, 700)}ms`,
        });
        barsGroup.appendChild(rect);
      });
    });
    svg.appendChild(barsGroup);

    // 悬停参考线
    const guide = svgEl("line", {
      class: "chart-hover-guide", y1: padT - 4, y2: baseY, x1: -10, x2: -10, opacity: 0,
    });
    svg.appendChild(guide);

    const tooltip = document.createElement("div");
    tooltip.className = "chart-tooltip";

    // 悬停热区
    const overlay = svgEl("g", {});
    buckets.forEach((b, i) => {
      const hit = svgEl("rect", { x: padL + slot * i, y: padT, width: slot, height: plotH, fill: "transparent" });
      hit.addEventListener("mouseenter", (e) => {
        const cx = padL + slot * i + slot / 2;
        guide.setAttribute("x1", cx);
        guide.setAttribute("x2", cx);
        guide.setAttribute("opacity", 1);
        tooltip.innerHTML = `
          <div class="tt-date">${escapeHtml(b.fullLabel)}</div>
          <div class="tt-row"><i style="background:${INCOME_COLOR}"></i>收入<b class="num">¥${fmt2(b.income)}</b></div>
          <div class="tt-row"><i style="background:${EXPENSE_COLOR}"></i>支出<b class="num">¥${fmt2(b.expense)}</b></div>`;
        tooltip.classList.add("show");
        positionTooltip(e);
      });
      hit.addEventListener("mousemove", positionTooltip);
      hit.addEventListener("mouseleave", () => {
        guide.setAttribute("opacity", 0);
        tooltip.classList.remove("show");
      });
      overlay.appendChild(hit);
    });
    svg.appendChild(overlay);

    container.appendChild(svg);
    container.appendChild(tooltip);

    function positionTooltip(e) {
      const box = container.getBoundingClientRect();
      let left = e.clientX - box.left;
      left = Math.max(80, Math.min(left, box.width - 80));
      let top = e.clientY - box.top - tooltip.offsetHeight - 14;
      if (top < 4) top = e.clientY - box.top + 18;
      tooltip.style.left = left + "px";
      tooltip.style.top = top + "px";
    }
  }

  /* ------------------------------------------------------------
     分类构成环形图
     data: [{ name, value }]
     opts:  { centerLabel }
     ------------------------------------------------------------ */
  function renderDonut(container, legendEl, data, opts = {}) {
    container.innerHTML = "";
    legendEl.innerHTML = "";

    const filtered = data.filter((d) => d.value > 0).sort((a, b) => b.value - a.value);
    if (!filtered.length) {
      container.appendChild(emptyNode("暂无分类数据"));
      return;
    }

    // 取前 6 名，其余合并为「其他」
    let items = filtered;
    if (filtered.length > 6) {
      const rest = filtered.slice(6).reduce((s, x) => s + x.value, 0);
      items = [...filtered.slice(0, 6), { name: "其他", value: rest, isOther: true }];
    }
    const total = items.reduce((s, x) => s + x.value, 0);

    const SIZE = 200, C = SIZE / 2, R = 74, STROKE = 26;
    const CIRC = 2 * Math.PI * R;

    const svg = svgEl("svg", { viewBox: `0 0 ${SIZE} ${SIZE}`, role: "img" });
    const g = svgEl("g", { transform: `rotate(-90 ${C} ${C})` });
    svg.appendChild(g);

    const center = document.createElement("div");
    center.className = "donut-center";
    const setCenter = (label, value) => {
      center.innerHTML = `<span class="dc-label">${escapeHtml(label)}</span><span class="dc-value num">¥${fmt2(value)}</span>`;
    };
    setCenter(opts.centerLabel || "总计", total);

    const gapPx = items.length > 1 ? 2.5 : 0; // 段间缝隙
    const segs = [];
    let offset = 0;

    items.forEach((it, i) => {
      const color = it.isOther ? OTHER_COLOR : PALETTE[i % PALETTE.length];
      const frac = it.value / total;
      const len = frac * CIRC;
      const dash = Math.max(0.5, len - gapPx);
      const seg = svgEl("circle", {
        cx: C, cy: C, r: R,
        fill: "none",
        stroke: color,
        "stroke-width": STROKE,
        "stroke-dasharray": `0 ${CIRC}`,          // 动画起点：0 长度
        "stroke-dashoffset": -offset,
        class: "donut-seg",
      });
      seg.style.transitionDelay = `${i * 80}ms`;
      g.appendChild(seg);
      segs.push(seg);
      it._color = color;
      it._frac = frac;
      offset += len;

      // 扫入动画
      requestAnimationFrame(() => requestAnimationFrame(() => {
        seg.setAttribute("stroke-dasharray", `${dash} ${CIRC - dash}`);
      }));
      // 动画结束后清掉延迟，避免影响悬停加粗
      setTimeout(() => { seg.style.transitionDelay = "0ms"; }, 900 + i * 80);
    });

    // 图例 + 联动高亮
    items.forEach((it, i) => {
      const li = document.createElement("li");
      li.innerHTML = `
        <span class="dl-dot" style="background:${it._color}"></span>
        <span class="dl-name">${escapeHtml(it.name)}</span>
        <span class="dl-pct">${(it._frac * 100).toFixed(1)}%</span>
        <span class="dl-amount num">¥${fmt2(it.value)}</span>`;
      legendEl.appendChild(li);

      const activate = () => {
        container.classList.add("dim");
        segs.forEach((s, j) => s.classList.toggle("active", j === i));
        [...legendEl.children].forEach((l, j) => l.classList.toggle("active", j === i));
        setCenter(it.name, it.value);
      };
      const deactivate = () => {
        container.classList.remove("dim");
        segs.forEach((s) => s.classList.remove("active"));
        [...legendEl.children].forEach((l) => l.classList.remove("active"));
        setCenter(opts.centerLabel || "总计", total);
      };
      li.addEventListener("mouseenter", activate);
      li.addEventListener("mouseleave", deactivate);
      segs[i].addEventListener("mouseenter", activate);
      segs[i].addEventListener("mouseleave", deactivate);
    });

    container.appendChild(svg);
    container.appendChild(center);
  }

  return { renderTrend, renderDonut, PALETTE };
})();

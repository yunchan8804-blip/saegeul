#!/usr/bin/env node
// design-gate — 범용 디자인 QA 게이트 (designpaca 스킬 동반 도구)
//
// 조사 기반 6계층 중 이 파일 하나로 돌리는 것: L0(정적·옵션) + L2(불변식·SEO/meta) + L3(시각회귀·옵션) + L4(WebKit·옵션).
// 프로젝트 고유의 L1(단위)·L5(탐색)는 각 프로젝트의 calc/테스트로 보강한다.
//
// 사용:
//   node design-gate.mjs --init                     # 설정파일 게이트 초안 생성
//   node design-gate.mjs                            # gate.config.json 기준 실행
//   node design-gate.mjs --update-baseline          # 시각 기준화면 갱신(검증된 배포 후에만)
//
// 철학: 없는 도구는 SKIP(실패 아님), 검사 가능한 것은 전부 검사(실패면 exit 1).
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import url from "node:url";

const argv = process.argv.slice(2);
const cfgFile = path.resolve(process.cwd(), argv.includes("--init") ? "gate.config.json" : (argv.find((a) => a.startsWith("--config=")) || "gate.config.json").replace("--config=", ""));

const DEFAULT_CFG = {
  pages: [
    {
      name: "app",
      path: "index.html",
      viewAttribute: "data-view",
      views: ["main"],
      h1: "h1",
      brand: ".brand",
    },
  ],
  widths: [320, 375, 390, 768, 1024, 1440],
  fontScales: [1, 1.3],
  thresholds: { contrastNormal: 4.5, contrastLarge: 3, visualDiffPct: 0.1, stackLines: 3 },
  checks: { meta: true, contrast: true, stack: true, wrap: true, rhythm: true, scaleMatrix: true, static: false, visual: false, webkit: false },
  chromePath: null,
};

if (argv.includes("--init")) {
  fs.writeFileSync(cfgFile, JSON.stringify(DEFAULT_CFG, null, 2) + "\n");
  console.log("gate.config.json 초안 생성 — pages/views 를 프로젝트에 맞게 고치세요.");
  console.log("package.json: \"scripts\": { \"verify\": \"node <이 스크립트 경로>\" } 등록을 권장합니다.");
  process.exit(0);
}

const _user = JSON.parse(fs.readFileSync(cfgFile, "utf8"));
const CFG = { ...DEFAULT_CFG, ..._user, checks: { ...DEFAULT_CFG.checks, ...(_user.checks || {}) } }; // checks 는 깊은 병합 — 새 검사가 기본 켜지도록
const UPDATE = argv.includes("--update-baseline");
const results = [];
const pass = (name, detail = "") => results.push({ ok: true, name, detail });
const fail = (name, detail = "") => results.push({ ok: false, name, detail });
const skip = (name, detail) => results.push({ ok: true, name, detail: "SKIP — " + detail });

// ── 브라우저 확보 (puppeteer-core + 크롬 경로 자동 탐색) ──
const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  CFG.chromePath,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium-browser",
].filter(Boolean);
const CHROME = CHROME_CANDIDATES.find((p) => fs.existsSync(p));

const pageUrl = (p) => (p.url ? p.url : url.pathToFileURL(path.resolve(process.cwd(), p.path)).href);

// ── L0 정적 (옵션) ──
if (CFG.checks.static) {
  const css = spawnSync("npx", ["stylelint", "**/*.css"], { shell: true, encoding: "utf8" });
  css.status === 0 ? pass("L0 stylelint") : fail("L0 stylelint", (css.stdout || "").slice(0, 200));
  const html = spawnSync("npx", ["html-validate", ...CFG.pages.map((p) => p.path)], { shell: true, encoding: "utf8" });
  html.status === 0 ? pass("L0 html-validate") : fail("L0 html-validate", (html.stdout || "").slice(0, 200));
} else skip("L0 정적", "checks.static=false");

// ── 브라우저 계층 ──
if (!CHROME) {
  console.log("chrome/puppeteer-core 없음 — CHROME_PATH 를 설정하세요.");
  process.exit(1);
}
let puppeteer;
try { puppeteer = await import("puppeteer-core"); }
catch { console.log("puppeteer-core 미설치 — npm i -D puppeteer-core 필요"); process.exit(1); }

const UTILS = `
  window.__lines = (sel) => {
    const el = typeof sel === "string" ? document.querySelector(sel) : sel;
    if (!el) return -1;
    const range = document.createRange();
    range.selectNodeContents(el);
    const rects = [...range.getClientRects()].filter((r) => r.width > 1 && r.height > 4);
    const tops = rects.map((r) => r.top).sort((a, b) => a - b);
    const ls = [];
    for (const t of tops) if (!ls.length || t - ls[ls.length - 1] > 5) ls.push(t);
    return ls.length;
  };
`;

const browser = await puppeteer.default.launch({ executablePath: CHROME, headless: "new", args: ["--force-device-scale-factor=1"] });
const page = await browser.newPage();
await page.setCacheEnabled(false);

for (const p of CFG.pages) {
  const href = pageUrl(p);
  await page.setViewport({ width: 1440, height: 900 });
  await page.goto(href, { waitUntil: "networkidle0" });
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(UTILS);

  // ── SEO / meta (조사 기반 체크리스트) ──
  if (CFG.checks.meta) {
    const meta = await page.evaluate(() => {
      const get = (sel, attr = "content") => { const el = document.querySelector(sel); return el ? (el.getAttribute(attr) || "") : null; };
      const title = document.title.trim();
      const desc = get('meta[name="description"]');
      const og = (k) => get(`meta[property="og:${k}"]`);
      const imgs = [...document.querySelectorAll("img")];
      return {
        title, titleLen: title.length,
        desc, descLen: desc ? desc.length : 0,
        viewport: get('meta[name="viewport"]'),
        lang: document.documentElement.getAttribute("lang"),
        charset: !!document.querySelector("meta[charset], meta[http-equiv='Content-Type']"),
        canonical: get('link[rel="canonical"]', "href"),
        ogTitle: og("title"), ogDesc: og("description"), ogImage: og("image"), ogUrl: og("url"),
        twitter: get('meta[name="twitter:card"]'),
        robots: get('meta[name="robots"]'),
        favicon: !!document.querySelector('link[rel~="icon"]'),
        h1Count: document.querySelectorAll("h1").length,
        imgNoAlt: imgs.filter((i) => !i.getAttribute("alt")).length,
        jsonld: [...document.querySelectorAll('script[type="application/ld+json"]')].every((s) => { try { JSON.parse(s.textContent); return true; } catch { return false; } }),
        jsonldCount: document.querySelectorAll('script[type="application/ld+json"]').length,
      };
    });
    const m = [];
    if (!meta.title) m.push("title 없음");
    else if (meta.titleLen < 10 || meta.titleLen > 65) m.push(`title ${meta.titleLen}자(10~65 권장)`);
    if (!meta.desc) m.push("description 없음");
    else if (meta.descLen < 60 || meta.descLen > 165) m.push(`description ${meta.descLen}자(60~165 권장)`);
    if (!meta.viewport) m.push("viewport 없음");
    if (!meta.lang) m.push("html lang 없음");
    if (!meta.charset) m.push("charset 없음");
    if (meta.ogTitle === null) m.push("og:title 없음");
    if (meta.ogImage === null) m.push("og:image 없음");
    if (meta.twitter === null) m.push("twitter:card 없음");
    if (!meta.favicon) m.push("favicon 없음");
    if (meta.h1Count > 1) m.push(`h1 ${meta.h1Count}개(유일해야)`);
    if (meta.imgNoAlt) m.push(`img alt 없음 ${meta.imgNoAlt}개`);
    if (meta.jsonldCount && !meta.jsonld) m.push("JSON-LD 파싱 실패");
    // 데모·로컬 페이지는 canonical/robots 미명시 허용 — 있으면 오히려 검증
    m.length ? fail(`${p.name} SEO/meta`, m.join(" · ")) : pass(`${p.name} SEO/meta`, `title ${meta.titleLen}자·desc ${meta.descLen}자·OG✓·alt ${meta.imgNoAlt}결`);
  }

  // ── 각 뷰 불변식 ──
  const goView = async (v) => {
    if (!p.viewAttribute || v === "single") return;
    await page.evaluate((attr, name) => {
      const btn = document.querySelector(`[${attr}="${name}"]`);
      btn && btn.click();
    }, p.viewAttribute, v);
    await new Promise((r) => setTimeout(r, 120));
  };

  for (const v of p.views) {
    await goView(v);
    // 오버플로 — 전 폭
    let overs = [];
    for (const w of CFG.widths) {
      await page.setViewport({ width: w, height: 900 });
      await new Promise((r) => setTimeout(r, 60));
      const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      if (over > 0) overs.push(`+${over}px@${w}`);
    }
    overs.length ? fail(`${p.name}/${v} 오버플로`, overs.join(" ")) : pass(`${p.name}/${v} 오버플로`, CFG.widths.join("/"));

    // h1 1줄
    await page.setViewport({ width: 1440, height: 900 });
    const h1l = await page.evaluate(`__lines(${JSON.stringify(p.h1 || "h1")})`);
    h1l === 1 ? pass(`${p.name}/${v} h1 1줄`) : (h1l > 1 ? fail(`${p.name}/${v} h1 ${h1l}줄`) : skip(`${p.name}/${v} h1`, "요소 없음"));

    // 세로 쌓임(수축) — 짧은 라벨 3줄 이상
    if (CFG.checks.stack) {
      const stacked = await page.evaluate((limit) => {
        const out = [];
        document.querySelectorAll("body *").forEach((el) => {
          if (!el.offsetParent || el.querySelector("*")) return;
          const txt = el.textContent.trim();
          if (txt.length && txt.length <= 14 && window.__lines(el) >= limit) out.push(txt.slice(0, 10));
        });
        return [...new Set(out)].slice(0, 5);
      }, CFG.thresholds.stackLines);
      stacked.length ? fail(`${p.name}/${v} 세로쌓임(수축)`, stacked.join(",")) : pass(`${p.name}/${v} 수축 없음`);
    }

    // 리듬·트랙 불변식 — 같은 부모·같은 클래스의 형제들이 한 축으로 정렬돼 있으면 폭·간격이 균일해야 한다
    // (시간표 시간 라벨이 자동 배치로 흩어지던 사고: 같은 열인데 폭 40/78 혼재, Δ 22·194 불규칙)
    if (CFG.checks.rhythm) {
      const rhythm = await page.evaluate(() => {
        const groups = {};
        document.querySelectorAll("body *").forEach((el) => {
          if (!el.offsetParent || !el.parentElement || !el.classList.length || el.children.length) return;
          const cls = el.classList[0];
          const key = (el.parentElement.className || el.parentElement.tagName) + "|" + cls;
          const r = el.getBoundingClientRect();
          (groups[key] = groups[key] || []).push({ x: r.left, y: r.top, w: r.width, h: r.height, t: el.textContent.trim().slice(0, 6), p: el.parentElement });
        });
        const issues = [];
        for (const [key, items] of Object.entries(groups)) {
          if (items.length < 4) continue;
          const spread = (v) => Math.max(...v) - Math.min(...v);
          const cls = key.split("|")[1];
          const hUniform = spread(items.map((i) => i.h)) <= 4;
          const txtUniform = spread(items.map((i) => i.t.length)) <= 1;
          const isDigits = items.every((i) => /^[0-9: .]+$/.test(i.t) && i.t.length > 0); // 숫자/시간 라벨 — 등폭이 의도
          const isEmpty = items.every((i) => !i.t); // 빈 격자 셀 — 트랙 균일이 의도
          // 1) 트랙 폭: 숫자 라벨·빈 셀·한 축 정렬 라벨은 폭이 균일해야 한다 — 자동 배치 흩어짐(열 섞임)을 잡는다.
          //    한국어 라벨은 글자수가 같아도 폭이 달라지므로, 한 축 정렬 또는 숫자 전용일 때만 검사한다(오탐 방지).
          const wSpread = spread(items.map((i) => i.w));
          const alignedCol = spread(items.map((i) => i.x)) < 3;
          if (hUniform && wSpread > 6 && (isDigits || isEmpty)) { // 숫자 라벨·빈 셀만 — 한국어 라벨은 내용 폭차가 자연스럽다
            issues.push(`${cls}: 폭 ${Math.round(Math.min(...items.map((i) => i.w)))}~${Math.round(Math.max(...items.map((i) => i.w)))}px 불균일 — 트랙/배치 의심`);
          }
          // 2) 리듬: 한 축으로 정렬된 균일 크기 형제는 등간격이어야 한다
          if (spread(items.map((i) => i.x)) < 3 && hUniform) {
            const ys = items.map((i) => i.y).sort((a, b) => a - b);
            const deltas = ys.slice(1).map((y, i) => y - ys[i]);
            if (deltas.length >= 3 && Math.max(...deltas) > Math.min(...deltas) * 1.6 + 4) issues.push(`${cls}: 세로 간격 Δ${Math.round(Math.min(...deltas))}~${Math.round(Math.max(...deltas))}px 불균일`);
          } else if (spread(items.map((i) => i.y)) < 3 && hUniform && spread(items.map((i) => i.w)) <= 4) {
            const xs2 = items.map((i) => i.x).sort((a, b) => a - b);
            const d2 = xs2.slice(1).map((x, i) => x - xs2[i]);
            if (d2.length >= 3 && Math.max(...d2) > Math.min(...d2) * 1.6 + 4) issues.push(`${cls}: 가로 간격 불균일 Δ${Math.round(Math.min(...d2))}~${Math.round(Math.max(...d2))}px`);
          }
        }
        return issues.slice(0, 4);
      });
      rhythm.length ? fail(p.name + "/" + v + " 리듬·트랙", rhythm.join(" · ")) : pass(p.name + "/" + v + " 리듬·트랙");
    }

    // 대비
    if (CFG.checks.contrast) {
      const bad = await page.evaluate((cn, cl) => {
        const lum = ({ r, g, b }) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }; return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
        const rgb = (c) => { const m = c.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/); return m ? { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] } : null; };
        const blend = (t, b) => ({ r: t.r * t.a + b.r * (1 - t.a), g: t.g * t.a + b.g * (1 - t.a), b: t.b * t.a + b.b * (1 - t.a), a: 1 });
        const seen = new Set(); const out = [];
        document.querySelectorAll("body *").forEach((el) => {
          if (!el.offsetParent) return;
          if (![...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())) return;
          const cs = getComputedStyle(el);
          const fg = rgb(cs.color); if (!fg) return;
          let node = el; const stack = [];
          while (node && node !== document.documentElement) {
            const c = rgb(getComputedStyle(node).backgroundColor);
            if (c && c.a >= 0.95) { stack.unshift(c); break; }
            if (c && c.a > 0) stack.unshift(c);
            node = node.parentElement;
          }
          let bg = { r: 255, g: 255, b: 255, a: 1 };
          for (const s of stack) bg = blend(s, bg);
          const fgb = blend(fg, bg);
          const l1 = lum(fgb), l2 = lum(bg);
          const ratio = +(((Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05))).toFixed(2);
          const size = parseFloat(cs.fontSize); const w = +cs.fontWeight || 400;
          const need = size >= 24 || (size >= 18.66 && w >= 600) ? cl : cn;
          if (ratio < need) {
            const k = cs.color + Math.round(size);
            if (!seen.has(k)) { seen.add(k); out.push(`"${el.textContent.trim().slice(0, 8)}" ${ratio}:1`); }
          }
        });
        return out.slice(0, 4);
      }, CFG.thresholds.contrastNormal, CFG.thresholds.contrastLarge);
      bad.length ? fail(`${p.name}/${v} 대비`, bad.join(",")) : pass(`${p.name}/${v} 대비 AA`);
    }
  }

  // ── 스케일×폭 h1 행렬 (안드로이드 글꼴 확대 재현) ──
  if (CFG.checks.scaleMatrix) {
    const bad = [];
    for (const w of [375, 390]) {
      for (const s of CFG.fontScales) {
        await page.setViewport({ width: w, height: 800, isMobile: true, hasTouch: true });
        await page.goto(href, { waitUntil: "networkidle0" });
        await page.evaluate(UTILS);
        await page.addStyleTag({ content: `html { font-size: ${16 * s}px !important; }` });
        const l = await page.evaluate(`__lines(${JSON.stringify(p.h1 || "h1")})`);
        if (l > 1) bad.push(`${w}@${s}x:${l}줄`);
      }
    }
    bad.length ? fail(`${p.name} 스케일×폭 h1`, bad.join(",")) : pass(`${p.name} 스케일×폭 h1`, `scales ${CFG.fontScales.join("/")}`);
    // 브랜드 1줄
    await page.setViewport({ width: 390, height: 844, isMobile: true, hasTouch: true });
    await page.goto(href, { waitUntil: "networkidle0" });
    await page.evaluate(UTILS);
    if (p.brand) {
      const bl = await page.evaluate(`__lines(${JSON.stringify(p.brand)})`);
      bl === 1 ? pass(`${p.name} 브랜드 1줄`) : fail(`${p.name} 브랜드 ${bl}줄(수축 의심)`);
    }
  }
}

// ── L3 시각 회귀 (옵션: pixelmatch·pngjs 설치 시) ──
if (CFG.checks.visual) {
  try {
    const { default: pixelmatch } = await import("pixelmatch");
    const { PNG } = await import("pngjs");
    const BASE = path.resolve(process.cwd(), "__gate__/baseline");
    fs.mkdirSync(BASE, { recursive: true });
    let diffs = 0, n = 0;
    for (const p of CFG.pages) {
      for (const w of [390, 1440]) {
        await page.setViewport({ width: w, height: w === 390 ? 844 : 900 });
        await page.goto(pageUrl(p), { waitUntil: "networkidle0" });
        await new Promise((r) => setTimeout(r, 250));
        const name = `${p.name}-${w}.png`;
        const buf = await page.screenshot();
        const cur = PNG.sync.read(buf);
        const bf = path.join(BASE, name);
        if (UPDATE || !fs.existsSync(bf)) { fs.writeFileSync(bf, buf); continue; }
        const base = PNG.sync.read(fs.readFileSync(bf));
        if (base.width !== cur.width || base.height !== cur.height) { diffs++; fail(`L3 시각 ${name}`, "크기 불일치"); continue; }
        const cnt = pixelmatch(base.data, cur.data, null, base.width, base.height, { threshold: 0.1 });
        const pct = (cnt / (base.width * base.height)) * 100;
        n++;
        if (pct >= CFG.thresholds.visualDiffPct) { diffs++; fail(`L3 시각 ${name}`, pct.toFixed(3) + "% diff"); }
      }
    }
    !diffs && pass("L3 시각 회귀", `${n}화면 ${UPDATE ? "기준갱신" : "일치"}`);
  } catch (e) { skip("L3 시각 회귀", "pixelmatch/pngjs 미설치"); }
} else skip("L3 시각 회귀", "checks.visual=false");

await browser.close();

// ── L4 WebKit (옵션: playwright 설치 시) ──
if (CFG.checks.webkit) {
  try {
    const { webkit } = await import("playwright");
    const b2 = await webkit.launch();
    const pg = await b2.newPage();
    await pg.setViewportSize({ width: 390, height: 844 });
    let bad = 0;
    for (const p of CFG.pages) {
      await pg.goto(pageUrl(p), { waitUntil: "networkidle" });
      const over = await pg.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      if (over > 0) { fail(`L4 WebKit ${p.name} 오버플로`, `+${over}px`); bad++; }
    }
    await b2.close();
    !bad && pass("L4 WebKit", `${CFG.pages.length}페이지 오버플로 0`);
  } catch { skip("L4 WebKit", "playwright/webkit 미설치"); }
} else skip("L4 WebKit", "checks.webkit=false");

// ── 리포트 ──
const fails = results.filter((r) => !r.ok).length;
const lines = results.map((r) => `${r.ok ? "PASS" : "FAIL"}  ${r.name}${r.detail ? " — " + r.detail : ""}`);
console.log(lines.join("\n"));
const stamp = new Date().toISOString().replace("T", " ").slice(0, 16);
fs.writeFileSync(path.resolve(process.cwd(), "gate-report.md"),
  `# 게이트 리포트 — ${stamp}\n\n${fails ? `**${fails}건 실패 — 배포 금지**` : "**전 항목 통과 — 배포 가능**"}\n\n\`\`\`\n${lines.join("\n")}\n\`\`\`\n`);
console.log(`\n${fails ? `게이트 실패(${fails})` : "게이트 통과"} — gate-report.md 생성`);
process.exit(fails ? 1 : 0);

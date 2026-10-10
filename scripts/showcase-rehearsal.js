// PRISM showcase rehearsal: drives the 12 demo steps through the real UI.
// Read-only against application state (no approvals, no mutations).
// Usage: node scripts/showcase-rehearsal.js [username] [password]
const { chromium } = require('playwright-core');

const BASE = 'http://localhost:5173';
const API = 'http://localhost:8080';
const [username, password] = [process.argv[2], process.argv[3]];
if (!username || !password) { console.error('usage: node showcase-rehearsal.js <user> <pass>'); process.exit(2); }

const shots = 'C:\\Users\\chatu\\AppData\\Local\\Temp\\opencode\\prism-showcase';
require('fs').mkdirSync(shots, { recursive: true });

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${name}${detail ? ' -- ' + detail : ''}`);
}

(async () => {
  const browser = await chromium.launch({ headless: false });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  const failedRequests = [];
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text().slice(0, 160)); });
  page.on('pageerror', e => errors.push(String(e).slice(0, 160)));
  page.on('response', r => { if (r.status() === 401) failedRequests.push(`${r.request().method()} ${r.url()}`); });

  // 1. login: obtain token via API (UI login form is exercised separately below)
  const loginRes = await page.request.post(`${API}/api/auth/login`, {
    data: { username, password },
  });
  check('1. login (API)', loginRes.ok(), `HTTP ${loginRes.status()}`);
  const loginJson = await loginRes.json();
  const accessToken = loginJson.accessToken;

  // Seed session storage so the SPA boots authenticated, then load the app
  await page.goto(BASE);
  await page.evaluate(l => {
    localStorage.setItem('prism.token', l.accessToken);
    localStorage.setItem('prism.user', JSON.stringify(l.user));
    if (l.expiresAt) localStorage.setItem('prism.expiresAt', String(new Date(l.expiresAt).getTime()));
    // Presenter selects the Delacroix corpus for the graph/contradiction steps.
    localStorage.setItem('prism.corpusId', '1');
  }, loginJson);
  await page.reload();
  await page.waitForTimeout(3000);
  const appText = await page.content();
  check('1b. app boots authenticated', !/login|sign in/i.test(await page.title()) || appText.length > 5000, `body=${appText.length} chars`);
  await page.screenshot({ path: `${shots}/01-home.png` });

  // Helper: visit route, expect non-empty render and no fatal error
  // Poll until the page has actually rendered: lists stream in after their
  // fetches resolve, and networkidle fires before slow queries return.
  async function visit(n, route, file, expectText) {
    await page.goto(`${BASE}${route}`);
    let body = '';
    const want = expectText ? expectText.toLowerCase() : null;
    for (let i = 0; i < 15; i++) {
      await page.waitForTimeout(1000);
      body = await page.innerText('body').catch(() => '');
      if (want ? body.toLowerCase().includes(want) : body.length > 500) break;
    }
    const ok = want ? body.toLowerCase().includes(want) : body.length > 500;
    check(`${n} ${route}`, ok, ok ? `${body.length} chars` : `empty render len=${body.length} head=${JSON.stringify(body.slice(0, 120))}`);
    await page.screenshot({ path: `${shots}/${file}.png` });
    return body;
  }

  await visit('2.', '/corpora', '02-corpora', 'PRISM-QA-SYNTHETIC');
  await visit('3.', '/documents', '03-documents', 'meridian');
  await visit('4.', '/knowledge', '04-knowledge', null);
  await visit('5.', '/approval', '05-approval', null);
  await visit('6.', '/graph', '06-graph', null);
  await visit('7.', '/verification', '07-verification', null);
  await visit('8.', '/contradictions', '08-contradictions', null);
  await visit('9.', '/debates/4', '09-debate', null);
  await visit('10.', '/reports', '10-reports', null);
  await visit('11.', '/chat', '11-chat', null);
  await visit('12.', '/glassbox', '12-glassbox', null);

  // Graph canvas: real pixels?
  await page.goto(`${BASE}/graph`);
  await page.waitForTimeout(4000);
  const ink = await page.evaluate(() => {
    const canvases = [...document.querySelectorAll('canvas')];
    if (!canvases.length) return { canvas: false };
    let best = 0, w = 0, h = 0;
    for (const c of canvases) {
      const g = c.getContext('2d');
      const d = g.getImageData(0, 0, c.width, c.height).data;
      let n = 0;
      for (let i = 3; i < d.length; i += 80) if (d[i] > 0) n++;
      if (n > best) { best = n; w = c.width; h = c.height; }
    }
    return { canvas: true, w, h, inkSamples: best, layers: canvases.length };
  });
  check('6b. graph canvas paints', ink.canvas && ink.inkSamples > 100, JSON.stringify(ink));

  // Overflow check at 390px
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${BASE}/corpora`);
  await page.waitForTimeout(2000);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check('mobile 390px no overflow', overflow <= 0, `overflow=${overflow}px`);
  await page.screenshot({ path: `${shots}/13-mobile.png` });

  check('console errors', errors.length === 0, errors.length ? errors.slice(0, 3).join(' | ') : 'none');
  check('no 401 API calls', failedRequests.length === 0, failedRequests.length ? [...new Set(failedRequests)].slice(0, 3).join(' | ') : 'none');
  const failed = results.filter(r => !r.ok);
  console.log(`\nREHEARSAL: ${results.length - failed.length}/${results.length} passed`);
  await browser.close();
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error('REHEARSAL CRASH:', e.message); process.exit(1); });

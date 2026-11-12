/* End-to-end UI test of the AURUM XAU app (web build of the same React Native code) against a running backend.
 * Usage: node e2e/app.e2e.cjs <webUrl> <backendUrl> <apiToken> <screenshotDir>
 * The Android-native shell (WebView chart, notifications, SQLite) is not exercised here; see docs/TEST_REPORT.md. */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const [webUrl, backendUrl, token, shotDir] = process.argv.slice(2);
if (!webUrl || !backendUrl || !token || !shotDir) {
  console.error('usage: node e2e/app.e2e.cjs <webUrl> <backendUrl> <apiToken> <screenshotDir>');
  process.exit(2);
}
fs.mkdirSync(shotDir, { recursive: true });
const results = [];

async function step(name, fn) {
  const t0 = Date.now();
  try {
    await fn();
    results.push({ name, status: 'PASS', ms: Date.now() - t0 });
    console.log(`PASS  ${name}`);
  } catch (e) {
    results.push({ name, status: 'FAIL', ms: Date.now() - t0, error: String(e.message || e).split('\n')[0] });
    console.log(`FAIL  ${name}: ${String(e.message || e).split('\n')[0]}`);
  }
}

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 400, height: 860 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  const consoleErrors = [];
  page.on('pageerror', (e) => consoleErrors.push(String(e.message)));
  const shot = (n) => page.screenshot({ path: path.join(shotDir, n + '.png') });
  const tab = async (id) => page.getByTestId(id).first().click();
  const more = async (id) => {
    await tab('tab-more');
    await page.getByTestId(id).click();
  };
  const back = async () => page.goBack();

  await step('app loads and shows connect prompt (not configured)', async () => {
    await page.goto(webUrl, { waitUntil: 'networkidle' });
    await page.getByTestId('connect-prompt').waitFor({ timeout: 20000 });
    await shot('01-first-launch');
  });

  await step('rejects plain http to a public host', async () => {
    await page.getByTestId('open-settings').click();
    await page.getByTestId('backend-url').fill('http://example.com');
    await page.getByTestId('api-token').fill(token);
    await page.getByTestId('connect').click();
    const txt = await page.getByTestId('connect-result').textContent({ timeout: 5000 });
    if (!/https/i.test(txt)) throw new Error('expected https warning, got: ' + txt);
  });

  await step('wrong token is rejected', async () => {
    await page.getByTestId('backend-url').fill(backendUrl);
    await page.getByTestId('api-token').fill('wrong-token-000000000');
    await page.getByTestId('connect').click();
    await page.waitForFunction(() => /token/i.test(document.querySelector('[data-testid="connect-result"]')?.textContent || ''), null, { timeout: 10000 });
  });

  await step('connects with the correct token and shows provider health', async () => {
    await page.getByTestId('api-token').fill(token);
    await page.getByTestId('connect').click();
    await page.waitForFunction(() => /(saved|mehfooz)/i.test(document.querySelector('[data-testid="connect-result"]')?.textContent || ''), null, { timeout: 10000 });
    await page.getByTestId('health-card').waitFor({ timeout: 15000 });
    const h = await page.getByTestId('health-card').textContent();
    if (!/DEMO/.test(h)) throw new Error('health card does not show DEMO mode');
    await shot('10-settings-health');
    await back();
  });

  await step('dashboard: DEMO DATA status, live quote, decision within view', async () => {
    await tab('tab-dashboard');
    await page.getByTestId('decision-card').waitFor({ timeout: 15000 });
    await page.waitForFunction(() => /DEMO/.test(document.querySelector('[data-testid="feed-status"]')?.textContent || ''), null, { timeout: 15000 });
    await page.waitForFunction(() => /\d+\.\d{2}/.test(document.querySelector('[data-testid="quote-bid"]')?.textContent || ''), null, { timeout: 15000 });
    const action = await page.getByTestId('decision-action').textContent();
    if (!/(BUY|SELL|WAIT)/.test(action)) throw new Error('no decision action: ' + action);
    await page.getByTestId('banner-demo').waitFor({ timeout: 5000 });
    await page.getByTestId('action-panel').waitFor();
    await shot('02-dashboard');
  });

  await step('quote updates live over WebSocket', async () => {
    const a = await page.getByTestId('quote-age').textContent();
    await page.waitForTimeout(3000);
    const bid1 = await page.getByTestId('quote-bid').textContent();
    await page.waitForFunction((b) => document.querySelector('[data-testid="quote-bid"]')?.textContent !== b, bid1, { timeout: 20000 });
    if (!a) throw new Error('no quote age');
  });

  await step('chart renders candles in the chart frame and switches timeframe', async () => {
    await tab('tab-chart');
    const frame = page.frameLocator('[data-testid="chart-frame"]');
    await frame.locator('canvas').first().waitFor({ timeout: 20000 });
    await page.waitForTimeout(2500);
    const legend = await frame.locator('#legend').textContent();
    if (!/XAU\/USD M5/.test(legend)) throw new Error('legend missing M5: ' + legend);
    await page.getByTestId('tf-M15').click();
    await page.waitForFunction(() => true);
    await frame.locator('#legend').filter({ hasText: 'M15' }).waitFor({ timeout: 15000 });
    await shot('03-chart');
  });

  await step('chart overlays toggle panel works', async () => {
    await page.getByTestId('toggle-overlays').click();
    await page.getByTestId('ov-rsi').waitFor({ timeout: 5000 });
    await page.getByTestId('ov-macd').click();
    await page.waitForTimeout(800);
    await shot('03b-chart-overlays');
    await page.getByTestId('ov-macd').click();
    await page.getByTestId('toggle-overlays').click();
  });

  await step('chart full-screen toggles', async () => {
    await page.getByTestId('fullscreen').click();
    await page.getByTestId('exit-fullscreen').waitFor({ timeout: 5000 });
    await page.getByTestId('exit-fullscreen').click();
    await page.getByTestId('tf-M5').waitFor({ timeout: 5000 });
  });

  await step('trade radar shows decision and action panel', async () => {
    await tab('tab-radar');
    await page.getByTestId('decision-card').first().waitFor({ timeout: 10000 });
    await page.getByTestId('action-panel').first().waitFor();
    await shot('04-radar');
  });

  await step('AI analyst answers with the labelled rule-based fallback', async () => {
    await tab('tab-ai');
    await page.getByTestId('ai-explain').click();
    await page.getByTestId('ai-text').waitFor({ timeout: 30000 });
    const txt = await page.getByTestId('ai-text').textContent();
    if (!/Rule-based/i.test(txt)) throw new Error('expected rule-based label');
    await shot('05-ai');
  });

  await step('market structure screen', async () => {
    await more('nav-structure');
    await page.getByTestId('structure-events').waitFor({ timeout: 15000 });
    await page.getByTestId('order-blocks').waitFor();
    await shot('06-structure');
    await back();
  });

  await step('auto levels screen with zone bounds and reasons', async () => {
    await more('nav-levels');
    await page.getByTestId('zone-list').waitFor({ timeout: 15000 });
    await shot('07-levels');
    await back();
  });

  await step('economic calendar screen (calendar disabled => shown as unavailable)', async () => {
    await more('nav-calendar');
    await page.getByTestId('news-gate').waitFor({ timeout: 15000 });
    const g = await page.getByTestId('news-gate').textContent();
    if (!/(unavailable|dastiyab)/i.test(g)) throw new Error('calendar unavailability not shown: ' + g);
    await shot('08-calendar');
    await back();
  });

  await step('signals & history screen', async () => {
    await more('nav-signals');
    await page.waitForTimeout(2500);
    const body = await page.locator('body').textContent();
    if (!/(signal record|No signals|BUY|SELL)/i.test(body)) throw new Error('signals screen did not render');
    await shot('09-signals');
    await back();
  });

  await step('performance screen + backtest run completes', async () => {
    await more('nav-performance');
    await page.getByTestId('perf-DEMO').click();
    await page.getByTestId('perf-report').waitFor({ timeout: 15000 });
    await page.getByTestId('run-backtest').click();
    await page.getByTestId('backtest-result').waitFor({ timeout: 240000 });
    const r = await page.getByTestId('backtest-result').textContent();
    if (!/Walk-forward|walk/i.test(r)) throw new Error('walk-forward section missing');
    await shot('11-performance-backtest');
    await back();
  });

  await step('language switch to English persists across screens', async () => {
    await more('nav-settings');
    await page.getByTestId('lang-en').click();
    await back();
    await tab('tab-dashboard');
    const t = await page.getByTestId('tab-more').textContent();
    if (!/More/.test(t)) throw new Error('tab label not English: ' + t);
    await page.reload({ waitUntil: 'networkidle' });
    await page.getByTestId('decision-card').waitFor({ timeout: 20000 });
    const t2 = await page.getByTestId('tab-more').textContent();
    if (!/More/.test(t2)) throw new Error('language not persisted after reload');
    await shot('12-dashboard-english');
  });

  await step('no uncaught page errors', async () => {
    if (consoleErrors.length) throw new Error(consoleErrors.slice(0, 3).join(' | '));
  });

  await browser.close();
  fs.writeFileSync(path.join(shotDir, 'results.json'), JSON.stringify(results, null, 2));
  const failed = results.filter((r) => r.status === 'FAIL').length;
  console.log(`\n${results.length - failed}/${results.length} passed`);
  process.exit(failed ? 1 : 0);
})();

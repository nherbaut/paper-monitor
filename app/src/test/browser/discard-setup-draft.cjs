// Run SetupDraftDiscardResourceTest with -Dpaper-monitor.draft-browser-check=true.
// Expose Playwright through NODE_PATH; Chrome is used directly.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

(async () => {
    const base = process.env.PAPER_MONITOR_TEST_URL || 'http://localhost:8081';
    const output = path.resolve('target/browser-checks');
    await fs.mkdir(output, {recursive: true});
    const browser = await chromium.launch({executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome',
        headless: true, args: ['--no-sandbox']});
    try {
        for (const [index, id] of process.argv.slice(2).entries()) {
            const context = await browser.newContext({viewport: index === 0
                ? {width: 1280, height: 900} : {width: 390, height: 844}});
            if (index === 1) await context.addCookies([{name: 'paper_monitor_session',
                value: process.env.PAPER_MONITOR_TEST_COOKIE, url: base}]);
            const page = await context.newPage();
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            let releaseHistory;
            const historyReady = new Promise(resolve => { releaseHistory = resolve; });
            await page.route('**/api/setup/history', async route => {
                await historyReady;
                await route.fulfill({json: []});
            });
            await page.goto(`${base}/setup?draft=${id}`);
            await page.locator('[data-step="3"]').waitFor({state: 'visible'});
            // A slow external history lookup must not prevent discarding a restored draft.
            assert.equal(await page.locator('#discard-draft').isEnabled(), true);
            releaseHistory();
            await page.locator('#feed-title').fill('My saved review draft');
            await page.locator('#title-continue').click();
            await page.locator('[data-step="4"]').waitFor({state: 'visible'});
            await page.reload();
            await page.locator('[data-step="4"]').waitFor({state: 'visible'});
            assert.equal(await page.evaluate(() => localStorage.getItem('paperMonitorSetupDraftId')), id);
            await page.locator('#discard-draft').waitFor({state: 'visible'});
            await page.screenshot({path: path.join(output, index === 0 ? 'discard-draft-desktop.png' : 'discard-draft-mobile.png')});
            // An API error must preserve the draft, its URL and all entered state.
            await page.route(`**/api/setup/drafts/${id}/discard`, route => route.fulfill({status: 500,
                json: {error: 'Could not discard this draft. Please try again.'}}));
            await page.locator('#discard-draft').click();
            await page.locator('#discard-status.error').waitFor({state: 'visible'});
            assert.equal(await page.evaluate(() => localStorage.getItem('paperMonitorSetupDraftId')), id);
            assert.equal(await page.locator('#setup-workspace').evaluate(element => element.inert), false);
            assert.equal((await context.request.get(`${base}/api/setup/drafts/${id}`)).status(), 200);
            await page.unroute(`**/api/setup/drafts/${id}/discard`);
            // A stale draft removed elsewhere is also safe to forget locally.
            if (index === 1) assert.equal((await context.request.post(`${base}/api/setup/drafts/${id}/discard`)).status(), 200);
            await page.locator('#discard-draft').click();
            await page.waitForURL(`${base}/setup`);
            await page.locator('#setup-start-choice').waitFor({state: 'visible'});
            assert.equal(await page.evaluate(() => localStorage.getItem('paperMonitorSetupDraftId')), null);
            assert.equal((await context.request.get(`${base}/api/setup/drafts/${id}`)).status(), 404);
            assert.equal(await page.locator('#discard-draft').isVisible(), false);
            await page.reload();
            await page.locator('#setup-start-choice').waitFor({state: 'visible'});
            assert.equal(await page.locator('#feed-title').inputValue(), '');
            assert.deepEqual(errors, []);
            await context.close();
        }
        console.log('Draft browser checks passed: resume, failure recovery, discard, stale drafts, clean reload, desktop and mobile.');
    } finally {
        await browser.close();
    }
})().catch(error => {console.error(error); process.exitCode = 1;});

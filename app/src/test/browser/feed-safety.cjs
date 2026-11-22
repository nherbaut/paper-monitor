// Run through FeedSafetyResourceTest with -Dpaper-monitor.browser-check=true.
// Install playwright outside the repo and expose it using NODE_PATH; Chrome is used directly.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

(async () => {
    const base = process.env.PAPER_MONITOR_TEST_URL || 'http://localhost:8081';
    const feedId = process.argv[2];
    const output = path.resolve('target/browser-checks');
    await fs.mkdir(output, { recursive: true });
    const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome',
        headless: true, args: ['--no-sandbox'] });
    try {
        const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
        await context.addCookies([{ name: 'paper_monitor_session', value: process.env.PAPER_MONITOR_TEST_COOKIE, url: base }]);
        const page = await context.newPage();
        page.setDefaultTimeout(15000);
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        const mutations = [];
        page.on('request', request => {
            if (request.method() === 'POST' && /\/papers\/(?:batch$|\d+\/status)/.test(new URL(request.url()).pathname)) {
                mutations.push(request);
            }
        });
        await page.goto(`${base}/admin#feeds/${feedId}/configuration`);
        await page.locator(`[data-paper-feed-panel="${feedId}"]`).waitFor({ state: 'visible' });
        assert.equal(await page.locator('select[name="userId"]').count(), 0);
        await page.locator(`[data-paper-feed-panel="${feedId}"] input[name="email"]`).waitFor({ state: 'visible' });
        await page.locator(`[data-paper-feed-panel="${feedId}"] form[action$="/share"]`).screenshot({ path: path.join(output, 'sharing-by-email.png') });

        await page.goto(`${base}/classify?logicalFeedId=${feedId}&state=NEW`);
        await page.locator('#classification-title').filter({ hasText: 'research title' }).waitFor({ state: 'visible' });
        await page.waitForFunction(() => !document.querySelector('#classification-card').classList.contains('entering'));
        const card = page.locator('#classification-card');
        const box = await card.boundingBox();
        // A vertical gesture cannot later turn into a horizontal swipe, even for multi-target workflows.
        await page.mouse.move(box.x + 5, box.y + 45);
        await page.mouse.down();
        await page.mouse.move(box.x + 7, box.y + 90, { steps: 5 });
        await page.mouse.move(box.x + 220, box.y + 110, { steps: 6 });
        await page.mouse.up();
        assert.equal(await card.evaluate(el => el.classList.contains('dragging')), false);
        assert.equal(mutations.length, 0);
        // Mouse text selection must remain available and must not classify the paper.
        const text = page.locator('#classification-abstract');
        await text.scrollIntoViewIfNeeded();
        const textBox = await text.locator('p').first().evaluate(el => {
            const range = document.createRange();
            range.setStart(el.firstChild, 0);
            range.setEnd(el.firstChild, 50);
            const rect = range.getClientRects()[0];
            return { x: rect.x, y: rect.y, height: rect.height, width: rect.width };
        });
        await page.mouse.move(textBox.x + 3, textBox.y + textBox.height / 2);
        await page.mouse.down();
        await page.mouse.move(textBox.x + Math.min(260, textBox.width - 3), textBox.y + textBox.height / 2, { steps: 10 });
        await page.mouse.up();
        assert.ok(await page.evaluate(() => window.getSelection().toString().length > 0));
        assert.equal(mutations.length, 0);
        await page.evaluate(() => window.getSelection().removeAllRanges());
        await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
        await page.mouse.wheel(0, 350);
        await page.waitForFunction(() => document.querySelector('#classification-card').scrollTop > 0);
        assert.equal(await card.evaluate(el => el.classList.contains('dragging')), false);
        await card.evaluate(el => { el.scrollTop = 0; });
        await page.screenshot({ path: path.join(output, 'swipe-readable-card.png') });

        await page.goto(`${base}/?logicalFeedId=${feedId}`);
        await page.locator('.browser-paper-card').first().waitFor({ state: 'visible' });
        await page.locator('#state-search-input').fill('no-such-paper-zzzz');
        await page.waitForFunction(() => document.querySelector('#reader-batch-update-button').disabled);
        await page.locator('#reader-export-button').click();
        assert.match(await page.locator('#reader-batch-update-button').getAttribute('title'), /current view contains no papers/);
        assert.equal(await page.locator('#reader-open-review-button').isDisabled(), true);
        await page.screenshot({ path: path.join(output, 'disabled-batch-action.png') });
        await page.locator('#state-search-input').fill('');
        await page.waitForFunction(() => !document.querySelector('#reader-batch-update-button').disabled);
        await page.locator('#reader-batch-update-button').click();
        assert.equal(await page.locator('#batch-paper-status').inputValue(), '');
        await page.locator('#batch-paper-modal').screenshot({ path: path.join(output, 'batch-neutral-selection.png') });
        await page.locator('#batch-paper-status').selectOption('TODO');
        let dialogText = '';
        page.once('dialog', async dialog => { dialogText = dialog.message(); await dialog.dismiss(); });
        await page.locator('#batch-paper-apply').click();
        await page.locator('#batch-paper-form-feedback').filter({ hasText: 'No papers were changed' }).waitFor();
        assert.match(dialogText, /1 paper\(s\)/);
        assert.match(dialogText, /research title/);
        assert.equal(mutations.length, 0, 'Cancelling preview must not mutate papers');
        await page.locator('#batch-paper-operation').selectOption('add_tag');
        await page.locator('#batch-paper-tag').fill('confirmed-by-browser');
        page.once('dialog', async dialog => { await dialog.accept(); });
        await page.locator('#batch-paper-apply').click();
        await page.locator('#batch-paper-modal').waitFor({ state: 'hidden' });
        assert.equal(mutations.length, 1);
        const params = new URLSearchParams(mutations[0].postData());
        assert.equal(params.get('selection'), 'ids');
        assert.equal(params.getAll('paperIds').length, 1);
        const mobileContext = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
        await mobileContext.addCookies([{ name: 'paper_monitor_session', value: process.env.PAPER_MONITOR_TEST_COOKIE, url: base }]);
        const mobile = await mobileContext.newPage();
        mobile.setDefaultTimeout(15000);
        mobile.on('pageerror', error => errors.push(error.message));
        await mobile.goto(`${base}/classify?logicalFeedId=${feedId}&state=NEW`);
        await mobile.locator('#classification-title').filter({ hasText: 'research title' }).waitFor({ state: 'visible' });
        const mobileCard = mobile.locator('#classification-card');
        const mobileBox = await mobileCard.boundingBox();
        const cdp = await mobileContext.newCDPSession(mobile);
        const touch = async (type, x, y) => cdp.send('Input.dispatchTouchEvent', {
            type, touchPoints: type === 'touchEnd' ? [] : [{ x, y }]
        });
        await touch('touchStart', mobileBox.x + mobileBox.width / 2, mobileBox.y + mobileBox.height - 30);
        for (let step = 1; step <= 8; step++) {
            await touch('touchMove', mobileBox.x + mobileBox.width / 2, mobileBox.y + mobileBox.height - 30 - step * 25);
        }
        await touch('touchEnd');
        await mobile.waitForFunction(() => document.querySelector('#classification-card').scrollTop > 0);
        assert.equal(await mobileCard.evaluate(el => el.classList.contains('dragging')), false);
        await mobileCard.evaluate(el => { el.scrollTop = 0; });
        await mobile.screenshot({ path: path.join(output, 'mobile-swipe-readable-card.png') });
        // Deliberate horizontal touch gestures still classify papers.
        const statusRequest = mobile.waitForRequest(request => request.method() === 'POST' && /\/papers\/\d+\/status/.test(request.url()));
        await touch('touchStart', mobileBox.x + mobileBox.width - 25, mobileBox.y + 50);
        for (let step = 1; step <= 10; step++) {
            await touch('touchMove', mobileBox.x + mobileBox.width - 25 - step * 25, mobileBox.y + 50);
        }
        await touch('touchEnd');
        await statusRequest;
        assert.deepEqual(errors, []);
        console.log('Browser checks passed: email sharing, scroll, selection, disabled actions, neutral state, preview cancellation and confirmed snapshot.');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });

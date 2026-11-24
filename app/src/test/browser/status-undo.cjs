// Run StatusUndoResourceTest with -Dpaper-monitor.undo-browser-check=true and Playwright on NODE_PATH.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
(async () => {
    const base = process.env.PAPER_MONITOR_TEST_URL || 'http://localhost:8081';
    const eventId = process.argv[2];
    const output = path.resolve('target/browser-checks/status-undo');
    await fs.mkdir(output, {recursive: true});
    const browser = await chromium.launch({executablePath: '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
    try {
        const context = await browser.newContext({viewport: {width: 1280, height: 900}});
        await context.addCookies([{name: 'paper_monitor_session', value: process.env.PAPER_MONITOR_TEST_COOKIE, url: base}]);
        const page = await context.newPage();
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.goto(base + '/logs');
        const event = page.locator(`#event-${eventId}`);
        await event.getByRole('button', {name: 'Undo to NEW'}).waitFor();
        await page.screenshot({path: path.join(output, 'undo-available.png')});
        await page.setViewportSize({width: 390, height: 844});
        await event.screenshot({path: path.join(output, 'undo-mobile.png')});
        await event.getByRole('button', {name: 'Undo to NEW'}).click();
        await page.getByRole('status').filter({hasText: 'Status change undone.'}).waitFor();
        assert.ok((await page.locator(`#event-${eventId}`).textContent()).includes('Current state: NEW'));
        assert.ok((await page.locator(`#event-${eventId}`).textContent()).includes('already undone'));
        assert.equal(await page.locator(`#event-${eventId} button`).count(), 0);
        await page.setViewportSize({width: 1280, height: 900});
        await page.screenshot({path: path.join(output, 'undo-complete.png')});
        await page.reload();
        assert.equal(await page.locator(`#event-${eventId} button`).count(), 0);
        assert.deepEqual(errors, []);
    } finally {await browser.close();}
})().catch(error => {console.error(error); process.exitCode = 1;});

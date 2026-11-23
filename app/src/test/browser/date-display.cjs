// Run FeedSafetyResourceTest#formatsAdminAndExportDatesInTheApplicationTimezone
// with -Dpaper-monitor.date-browser-check=true and Playwright on NODE_PATH.
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
        // The viewer's timezone must not affect displayed dates.
        const context = await browser.newContext({timezoneId: 'America/Los_Angeles',
            viewport: {width: 1280, height: 900}});
        await context.addCookies([{name: 'paper_monitor_session', value: process.env.PAPER_MONITOR_TEST_COOKIE, url: base}]);
        const page = await context.newPage();
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.goto(`${base}/admin#feeds/${process.argv[2]}/rss`);
        const card = page.locator('[data-rss-feed-editor]:visible');
        await card.waitFor();
        assert.ok((await card.textContent()).includes('Last poll: 2027-01-01 00-30'));
        assert.equal(await page.evaluate(() => window.formatApplicationDate('2026-07-01T12:49:21Z')),
            '2026-07-01 14-49');
        assert.equal(await page.evaluate(() => window.formatApplicationDate('2026-08-23T01:19:29.393747')),
            '2026-08-23 01-19');
        await card.screenshot({path: path.join(output, 'configured-date-format.png')});
        assert.deepEqual(errors, []);
        console.log('Date browser checks passed in a Los Angeles browser with the Europe/Paris application zone.');
    } finally {
        await browser.close();
    }
})().catch(error => {console.error(error); process.exitCode = 1;});

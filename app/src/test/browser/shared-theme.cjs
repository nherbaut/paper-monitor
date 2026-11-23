// Run ThemeResourceTest with -Dpaper-monitor.theme-browser-check=true and Playwright on NODE_PATH.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

(async () => {
    const base = process.env.PAPER_MONITOR_TEST_URL || 'http://localhost:8081';
    const pde = process.env.PAPER_MONITOR_TEST_PDE_URL || 'http://localhost:8093';
    const [feed, paper, review] = process.argv.slice(2);
    const output = path.resolve('target/browser-checks/shared-theme');
    await fs.mkdir(output, {recursive: true});
    const browser = await chromium.launch({executablePath: '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
    try {
        const context = await browser.newContext({viewport: {width: 1280, height: 900}});
        await context.addCookies([{name: 'paper_monitor_session', value: process.env.PAPER_MONITOR_TEST_COOKIE, url: base}]);
        await context.addInitScript(() => {
            if (!localStorage.getItem('paper-monitor.theme')) localStorage.setItem('paper-monitor.theme', 'dark');
            // Observe the palette at the first content paint rather than after app initialization.
            new PerformanceObserver(list => {
                if (list.getEntries().some(entry => entry.name === 'first-contentful-paint')) {
                    window.themeAtFirstPaint = document.documentElement.dataset.theme;
                }
            }).observe({type: 'paint', buffered: true});
        });
        const page = await context.newPage();
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.route('**/api/setup/history', route => route.fulfill({json: []}));
        const pages = [
            ['home', `/?logicalFeedId=${feed}`], ['admin', `/admin#feeds/${feed}/configuration`],
            ['logs', '/logs'], ['setup', '/setup'], ['login', '/login'], ['quick-setup', '/admin/quick-setup?paperFeedName=Theme%20review&rssUrl=https%3A%2F%2Fexample.org%2Ffeed'],
            ['diagram', `/logical-feeds/${feed}/diagram`], ['review', `/reviews/${review}`],
            ['review-paper', `/reviews/${review}/papers/${paper}`]
        ];
        for (const [name, url] of pages) {
            const response = await page.goto(base + url);
            assert.equal(response.status(), 200, name);
            await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
            assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme), 'dark', name);
            // Page and control colors must actually change, not just the theme attribute.
            const palette = await page.evaluate(() => ({ink: getComputedStyle(document.body).color,
                surface: getComputedStyle(document.documentElement).getPropertyValue('--theme-surface').trim()}));
            assert.equal(palette.ink, 'rgb(238, 230, 219)', name);
            assert.equal(palette.surface, '#211c18', name);
            if (name === 'admin' || name === 'logs' || name === 'review-paper') {
                await page.screenshot({path: path.join(output, `${name}-dark.png`)});
            }
            assert.equal(await page.evaluate(() => window.themeAtFirstPaint || document.documentElement.dataset.theme), 'dark', name);
        }
        await page.goto(base + `/admin#workflow/${feed}`);
        await page.locator('[data-workflow-canvas]:visible canvas').first().waitFor();
        await page.locator('[data-theme-toggle]').click();
        await page.locator('[data-theme-toggle]').click();
        await page.screenshot({path: path.join(output, 'workflow-dark.png')});
        await page.setViewportSize({width: 390, height: 844});
        await page.goto(base + '/setup');
        await page.screenshot({path: path.join(output, 'setup-mobile-dark.png')});
        await page.setViewportSize({width: 1280, height: 900});
        // Switching in one tab propagates to other pages on the same origin.
        await page.goto(base + '/admin');
        const second = await context.newPage();
        await second.goto(base + '/logs');
        await page.locator('[data-theme-toggle]').click();
        await second.waitForFunction(() => document.documentElement.dataset.theme === 'light');
        assert.equal(await page.evaluate(() => localStorage.getItem('paper-monitor.theme')), 'light');
        await page.screenshot({path: path.join(output, 'admin-light.png')});
        for (const [name, url] of pages) {
            const response = await page.goto(base + url);
            assert.equal(response.status(), 200, name + ' light');
            assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), 'light', name);
            assert.equal(await page.evaluate(() => getComputedStyle(document.body).color), 'rgb(38, 34, 29)', name);
        }
        await page.goto(base + '/admin');
        await page.locator('[data-theme-toggle]').click();
        const link = page.locator('a[data-pde-link]').first();
        assert.equal(new URL(await link.getAttribute('href')).searchParams.get('theme'), 'dark');
        // A new origin receives the selected theme through its link, then keeps it on navigation.
        const pdeContext = await browser.newContext({viewport: {width: 1280, height: 900}});
        const workbench = await pdeContext.newPage();
        workbench.on('pageerror', error => errors.push(error.message));
        await workbench.goto(pde + '/?theme=dark');
        assert.equal(await workbench.evaluate(() => localStorage.getItem('paper-monitor.theme')), 'dark');
        assert.equal(new URL(workbench.url()).searchParams.has('theme'), false);
        await workbench.waitForFunction(() => document.querySelector('#model-status')?.textContent.includes('available.'));
        const cardBackground = await workbench.locator('.dem-card').first().evaluate(element => getComputedStyle(element).backgroundColor);
        assert.ok(Number(cardBackground.match(/\d+/)[0]) < 100, 'Model cards must use dark tints');
        await workbench.screenshot({path: path.join(output, 'pde-dark.png')});
        for (const suffix of ['/review/five-research-questions', '/review/five-research-questions/graph']) {
            const response = await workbench.goto(pde + suffix);
            assert.equal(response.status(), 200, suffix);
            assert.equal(await workbench.evaluate(() => document.documentElement.dataset.theme), 'dark');
        }
        await workbench.locator('[data-theme-toggle]').click();
        await workbench.locator('[data-theme-toggle]').click();
        await workbench.goto(pde + '/reviews');
        assert.equal(await workbench.evaluate(() => document.documentElement.dataset.bsTheme), 'dark');
        await workbench.locator('[data-theme-toggle]').click();
        assert.equal(await workbench.evaluate(() => document.documentElement.dataset.theme), 'light');
        await workbench.screenshot({path: path.join(output, 'pde-light.png')});
        await workbench.reload();
        assert.equal(await workbench.evaluate(() => document.documentElement.dataset.theme), 'light');
        // Browser storage restrictions must not break navigation or toggling.
        const privateContext = await browser.newContext();
        await privateContext.addInitScript(() => {
            Object.defineProperty(window, 'localStorage', {get() {throw new DOMException('Blocked', 'SecurityError');}});
        });
        const privatePage = await privateContext.newPage();
        privatePage.on('pageerror', error => errors.push(error.message));
        await privatePage.goto(pde + '/reviews?theme=dark');
        assert.equal(await privatePage.evaluate(() => document.documentElement.dataset.theme), 'dark');
        await privatePage.locator('[data-theme-toggle]').click();
        assert.equal(await privatePage.evaluate(() => document.documentElement.dataset.theme), 'light');
        await privatePage.goto(pde + '/reviews?theme=invalid');
        assert.equal(await privatePage.evaluate(() => document.documentElement.dataset.theme), 'light');
        assert.deepEqual(errors, []);
    } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode = 1;});

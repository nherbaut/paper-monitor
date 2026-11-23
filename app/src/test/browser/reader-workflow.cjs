// Invoked by ReaderWorkflowResourceTest with -Dpaper-monitor.browser-check=true.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

(async () => {
    const base = process.env.PAPER_MONITOR_TEST_URL || 'http://localhost:8081';
    const feedId = process.argv[2];
    const output = path.resolve('target/browser-checks/reader-workflow');
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
        const tab = label => page.locator('#reader-tabs button').filter({ hasText: new RegExp(`^${label}`, 'i') });
        const result = async (count, searched = false) => page.waitForFunction(({count, searched}) => {
            const text = document.getElementById('reader-search-status')?.textContent || '';
            return text.startsWith(count + ' paper') && (!searched || text.includes('and search'));
        }, { count, searched });

        await page.goto(base);
        await page.locator(`[data-open-feed="${feedId}"]`).click();
        await page.waitForURL(url => url.searchParams.get('logicalFeedId') === feedId);
        await tab('Todo').click();
        await page.locator('#reader-subtabs button').filter({ hasText: /^Eligible/i }).click();
        await result(35);
        await page.locator('#state-search-input').fill('needle date:>2025');
        await result(35, true);
        await page.waitForFunction(() => [...document.querySelectorAll('#reader-tabs button')].some(el => el.textContent.toLowerCase() === 'new (1)'));
        assert.equal(new URL(page.url()).searchParams.get('status'), 'TODO/ELIGIBLE');
        assert.equal(new URL(page.url()).searchParams.get('search'), 'needle date:>2025');
        await page.locator('#state-search-input').scrollIntoViewIfNeeded();
        await page.screenshot({ path: path.join(output, 'search-results-and-state-counts.png') });

        await page.reload();
        await result(35, true);
        assert.equal(await page.locator('#state-search-input').inputValue(), 'needle date:>2025');
        assert.match(await page.locator('#reader-subtabs button.active').textContent(), /^Eligible/i);
        const firstCard = page.locator('.browser-paper-card').first();
        const paperId = await firstCard.getAttribute('data-paper-id');
        await firstCard.click();
        await page.waitForLoadState('networkidle');
        await page.waitForURL(url => url.searchParams.get('paperId') === paperId);
        assert.equal(await page.locator('#reader-search-status').isVisible(), false);
        await page.reload();
        await page.locator('#pdf-panel.visible').waitFor();
        assert.equal(new URL(page.url()).searchParams.get('paperId'), paperId);
        await page.locator('#pdf-close').click();
        await page.waitForURL(url => !url.searchParams.has('paperId'));
        await tab('New').click();
        await result(1, true);
        assert.equal(new URL(page.url()).searchParams.get('status'), 'NEW');
        await tab('Tags').click();
        await page.locator('#tag-badge-list button').filter({ hasText: /^review \(36\)$/ }).click();
        await page.waitForURL(url => url.searchParams.get('view') === 'tags' && url.searchParams.get('tag') === 'review');
        await page.reload();
        await page.locator('#tag-badge-list button.active').filter({ hasText: /^review/ }).waitFor();
        assert.match(await page.locator('#reader-tabs button.active').textContent(), /Tags/);

        await tab('Todo').click();
        await page.locator('#reader-subtabs button').filter({ hasText: /^Eligible/i }).click();
        await page.locator('#state-search-input').fill('no-such-article');
        await result(0, true);
        await page.waitForFunction(() => [...document.querySelectorAll('#reader-tabs button')]
            .filter(el => !el.textContent.startsWith('Tags')).every(el => el.textContent.endsWith('(0)')));
        await page.locator('#state-search-input').fill('');
        await result(35);
        await page.waitForFunction(() => [...document.querySelectorAll('#reader-tabs button')].some(el => el.textContent.toLowerCase() === 'new (2)'));
        assert.equal(new URL(page.url()).searchParams.has('search'), false);

        const account = page.locator('.topbar-menu');
        const tools = page.locator('#reader-export-menu');
        await account.locator(':scope > summary').click();
        assert.equal(await account.getAttribute('open'), '');
        await tools.locator(':scope > summary').focus();
        await tools.locator(':scope > summary').click();
        assert.equal(await account.getAttribute('open'), null);
        assert.equal(await tools.getAttribute('open'), '');
        await page.locator('#state-search-input').click();
        assert.equal(await tools.getAttribute('open'), null);
        await account.locator(':scope > summary').click();
        await page.keyboard.press('Escape');
        assert.equal(await account.getAttribute('open'), null);
        assert.equal(await account.locator(':scope > summary').evaluate(el => el === document.activeElement), true);
        await page.waitForLoadState('networkidle');
        assert.deepEqual(errors, []);
        console.log('Reader checks passed: dashboard URL, hierarchy, search counts, reload, tags, zero results, reset and menu dismissal.');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });

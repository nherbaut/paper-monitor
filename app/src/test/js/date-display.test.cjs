const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname,
    '../../main/resources/META-INF/resources/assets/vendor/date-display.js'), 'utf8');

function formatter(timeZone) {
    const context = {window: {}, document: {querySelector: () => ({content: timeZone})}};
    vm.runInNewContext(source, context);
    return context.window.formatApplicationDate;
}

test('uses the application zone across daylight saving and year boundaries', () => {
    const format = formatter('Europe/Paris');
    assert.equal(format('2026-12-31T23:30:59.123Z'), '2027-01-01 00-30');
    assert.equal(format('2026-07-01T12:49:21Z'), '2026-07-01 14-49');
    assert.equal(format('2026-03-29T00:30:00Z'), '2026-03-29 01-30');
    assert.equal(format('2026-03-29T01:30:00Z'), '2026-03-29 03-30');
    assert.equal(formatter('UTC')('2026-07-01T12:49:21Z'), '2026-07-01 12-49');
});

test('preserves date-only metadata and handles absent or unrecognized dates', () => {
    const format = formatter('America/Los_Angeles');
    assert.equal(format('2026-07-01'), '2026-07-01');
    assert.equal(format('2026-08-23T01:19:29.393747'), '2026-08-23 01-19');
    assert.equal(format(null), '');
    assert.equal(format(''), '');
    assert.equal(format('unknown'), 'unknown');
});

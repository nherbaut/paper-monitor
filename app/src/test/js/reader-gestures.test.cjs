const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const source = readFileSync(new URL('../../main/resources/META-INF/resources/assets/app/home.js', `file://${__filename}`), 'utf8');
function gestureContext({ multiTarget = false, selection = false } = {}) {
    const start = source.indexOf('    const updateClassificationPointerDrag =');
    const end = source.indexOf('    classificationCard?.addEventListener("pointerdown"', start);
    const context = vm.createContext({
        classificationDragState: { pointerId: 1, startX: 0, startY: 0, multiTarget, captured: false, triggerX: 100 },
        selectionInClassificationCard: () => selection,
        updateClassificationMobileLongPress: () => false,
        classificationMobileLongPress: null,
        resetClassificationDragVisual: () => {},
        classificationCard: { setPointerCapture() {}, classList: { add() {}, toggle() {} }, style: { setProperty() {} } },
        showClassificationDropZones: () => ({ targets: [], deadZoneRadius: 30 }),
        showMadProfessorFeedback() {}, classificationDropTargetForDrag: () => null,
    });
    vm.runInContext(source.slice(start, end) + '\nglobalThis.move = updateClassificationPointerDrag;', context);
    return context;
}
for (const multiTarget of [false, true]) {
    test(`vertical scrolling cancels the gesture (multiTarget=${multiTarget})`, () => {
        const context = gestureContext({ multiTarget });
        context.move({ pointerId: 1, clientX: 12, clientY: 80, preventDefault() { assert.fail('scroll prevented'); } });
        assert.equal(context.classificationDragState, null);
    });
    test(`horizontal background drags still work (multiTarget=${multiTarget})`, () => {
        const context = gestureContext({ multiTarget });
        let prevented = false;
        context.move({ pointerId: 1, clientX: 90, clientY: 12, preventDefault() { prevented = true; } });
        assert.equal(context.classificationDragState.captured, true);
        assert.equal(prevented, true);
    });
}
test('text selection cancels a pending swipe', () => {
    const context = gestureContext({ selection: true });
    context.move({ pointerId: 1, clientX: 140, clientY: 0, preventDefault() { assert.fail('selection prevented'); } });
    assert.equal(context.classificationDragState, null);
});

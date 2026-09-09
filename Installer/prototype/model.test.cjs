const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('./model.js');

test('engineering code matches RestoreMode digit sums, including multi-digit sums and leading zeros', () => {
  assert.equal(M.engineeringCode('2026-07-28'), '27414');
  assert.equal(M.engineeringCode('2026-01-01'), '2127');
  assert.equal(M.engineeringCode('2024-02-29'), '22413');
  assert.equal(M.engineeringCode('2026-12-31'), '3257');
});
test('Beijing day rolls over independently of host timezone and at year boundary', () => {
  assert.equal(M.beijingDate(new Date('2026-07-28T15:59:59Z')), '2026-07-28');
  assert.equal(M.beijingDate(new Date('2026-07-28T16:00:00Z')), '2026-07-29');
  assert.equal(M.beijingDate(new Date('2026-12-31T16:00:00Z')), '2027-01-01');
});
test('invalid calendar dates are rejected instead of normalized', () => {
  for (const date of ['2026-02-29', '2026-13-01', '2026-04-31', '', '28.07.2026', '0000-01-01']) assert.throws(() => M.engineeringCode(date));
});
test('confirmation requires exactly one compatible authorized device', () => {
  assert.equal(M.canConfirm(null), false);
  for (const [key, value] of Object.entries(M.connectionStates)) assert.equal(M.canConfirm(value), key === 'ready', key);
  assert.equal(M.canConfirm({ devices: [M.connectionStates.ready.devices[0], M.connectionStates.unauthorized.devices[0]] }), false);
});
test('remove plan covers both variants and final cleanup, even without APKs', () => {
  for (const installed of ['full', 'light', 'mixed', 'none', 'remnants']) assert.equal(M.planTitle('remove', installed), 'Удаление VoyahTune');
  assert.equal(M.removeSteps.length, 8);
  assert.equal(M.faultStep('cleanup', 'remove'), 7);
  assert.equal(M.faultStep('backup', 'remove'), -1);
  assert.equal(M.faultStep('cleanup', 'full'), -1);
});

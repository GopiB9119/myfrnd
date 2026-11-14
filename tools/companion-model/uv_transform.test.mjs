import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyUvTransform } from './uv_transform.mjs';

function assertUv(actual, expected) {
  actual.forEach((value, i) => assert.ok(Math.abs(value - expected[i]) < 1e-9, `${actual} != ${expected}`));
}

test('without rotation the bake is offset + scale * uv', () => {
  assertUv(applyUvTransform([0.25, 0.5], [0.1, 0.2], 0, [16, 8]), [4.1, 4.2]);
});

test('the KHR_texture_transform spec example samples the lower-left quadrant', () => {
  // offset [0, 1], rotation 90 degrees, scale 0.5: the whole texture maps onto u 0..0.5, v 0.5..1.
  const transform = [[0, 1], Math.PI / 2, [0.5, 0.5]];
  assertUv(applyUvTransform([0, 0], ...transform), [0, 1]);
  assertUv(applyUvTransform([1, 0], ...transform), [0, 0.5]);
  assertUv(applyUvTransform([0, 1], ...transform), [0.5, 1]);
  assertUv(applyUvTransform([1, 1], ...transform), [0.5, 0.5]);
});

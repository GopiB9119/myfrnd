/**
 * Maps one UV through a KHR_texture_transform exactly as Filament's gltfio samples it:
 * `uv' = vec3(uv, 1) * matrixFromUvTransform(offset, rotation, scale)` with Filament's
 * column-major mat3 (libs/gltfio/include/gltfio/math.h). Baking with the same formula keeps the
 * baked model looking the same as the original did in the app.
 */
export function applyUvTransform([u, v], [ox, oy], rotation, [sx, sy]) {
  const c = Math.cos(rotation);
  const s = Math.sin(rotation);
  return [ox + sx * (c * u + s * v), oy + sy * (c * v - s * u)];
}

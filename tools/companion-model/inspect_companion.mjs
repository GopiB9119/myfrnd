// Prints what the app cares about in a GLB: size, triangles, height, rig, textures and skin weights.
// Usage: node inspect_companion.mjs <file.glb> [--weights]
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { getBounds } from '@gltf-transform/functions';
import { MeshoptDecoder } from 'meshoptimizer';
import sharp from 'sharp';
import { statSync } from 'node:fs';

const file = process.argv[2];
if (!file) {
  console.error('Usage: node inspect_companion.mjs <file.glb> [--weights]');
  process.exit(1);
}

await MeshoptDecoder.ready;
const io = new NodeIO().registerExtensions(ALL_EXTENSIONS).registerDependencies({ 'meshopt.decoder': MeshoptDecoder });
const doc = await io.read(file);
const root = doc.getRoot();
const scene = root.getDefaultScene() ?? root.listScenes()[0];

let triangles = 0;
let vertices = 0;
for (const mesh of root.listMeshes()) {
  for (const prim of mesh.listPrimitives()) {
    const count = prim.getIndices()?.getCount() ?? prim.getAttribute('POSITION').getCount();
    triangles += Math.floor(count / 3);
    vertices += prim.getAttribute('POSITION').getCount();
  }
}
const bounds = getBounds(scene);
const fmt = (v) => v.map((n) => n.toFixed(4)).join(', ');
console.log(`file        ${file} (${statSync(file).size} bytes)`);
console.log(`generator   ${root.getAsset().generator}`);
console.log(`extensions  used=[${root.listExtensionsUsed().map((e) => e.extensionName)}] ` +
  `required=[${root.listExtensionsRequired().map((e) => e.extensionName)}]`);
console.log(`geometry    ${triangles} triangles, ${vertices} vertices, ${root.listMeshes().length} meshes`);
console.log(`bounds      min=(${fmt(bounds.min)}) max=(${fmt(bounds.max)}) height=${(bounds.max[1] - bounds.min[1]).toFixed(4)}`);
console.log(`animations  ${root.listAnimations().map((a) => a.getName() || '(unnamed)').join(', ') || 'none'}`);

const printNode = (node, depth) => {
  const t = node.getTranslation();
  const tags = [node.getMesh() ? `mesh=${node.getMesh().getName()}` : '', node.getSkin() ? `skin=${node.getSkin().getName()}` : '']
    .filter(Boolean).join(' ');
  console.log(`${'  '.repeat(depth + 1)}${node.getName() || '(unnamed)'} t=(${fmt(t)}) ${tags}`);
  node.listChildren().forEach((child) => printNode(child, depth + 1));
};
console.log('nodes');
scene.listChildren().forEach((node) => printNode(node, 0));

for (const skin of root.listSkins()) {
  console.log(`skin        ${skin.getName()} joints=[${skin.listJoints().map((j) => j.getName())}]`);
  for (const joint of skin.listJoints()) console.log(`  ${joint.getName().padEnd(8)} world=(${fmt(joint.getWorldTranslation())})`);
}

for (const material of root.listMaterials()) {
  const slots = {
    baseColor: material.getBaseColorTexture(),
    normal: material.getNormalTexture(),
    metallicRoughness: material.getMetallicRoughnessTexture(),
    occlusion: material.getOcclusionTexture(),
    emissive: material.getEmissiveTexture(),
  };
  console.log(`material    ${material.getName()} alpha=${material.getAlphaMode()} doubleSided=${material.getDoubleSided()} ` +
    `metallic=${material.getMetallicFactor()} roughness=${material.getRoughnessFactor()} emissive=[${material.getEmissiveFactor()}]`);
  for (const [slot, texture] of Object.entries(slots)) {
    if (!texture) continue;
    const meta = await sharp(Buffer.from(texture.getImage())).metadata();
    console.log(`  ${slot.padEnd(17)} ${texture.getMimeType()} ${meta.width}x${meta.height} ${texture.getImage().byteLength} bytes`);
  }
}

const prim = root.listMeshes()[0]?.listPrimitives()[0];
if (prim) console.log(`attributes  ${prim.listSemantics().join(', ')}`);

if (process.argv.includes('--weights') && prim?.getAttribute('JOINTS_0')) {
  const skin = root.listSkins()[0];
  const names = skin.listJoints().map((j) => j.getName());
  const position = prim.getAttribute('POSITION');
  const joints = prim.getAttribute('JOINTS_0');
  const weights = prim.getAttribute('WEIGHTS_0');
  const height = bounds.max[1] - bounds.min[1];
  const bins = new Map();
  const p = [];
  const j = [];
  const w = [];
  for (let i = 0; i < position.getCount(); i++) {
    position.getElement(i, p);
    joints.getElement(i, j);
    weights.getElement(i, w);
    const bin = Math.min(99, Math.floor(((p[1] - bounds.min[1]) / height) * 100));
    const entry = bins.get(bin) ?? { n: 0, sum: new Array(names.length).fill(0) };
    entry.n++;
    for (let k = 0; k < 4; k++) entry.sum[j[k]] += w[k];
    bins.set(bin, entry);
  }
  console.log(`weights     mean per 1% height band: ${names.join(' / ')}`);
  for (const bin of [...bins.keys()].sort((a, b) => a - b)) {
    const { n, sum } = bins.get(bin);
    if (sum.every((s, k) => k === 0 ? s === n : s === 0) && bin < 40) continue;
    console.log(`  ${(bin / 100).toFixed(2)} n=${String(n).padStart(5)} ${sum.map((s) => (s / n).toFixed(3)).join(' ')}`);
  }
}

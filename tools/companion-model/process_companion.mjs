// Builds the app's companion.glb from a source human GLB (for example the Monochrome Moment export).
//
//   node process_companion.mjs <source.glb> [output.glb] [--triangles 50000] [--height 0]
//
// What it does, in order:
//  1. Bakes node transforms (the source stores whole-number positions with a tiny node scale).
//  2. Bakes KHR_texture_transform into the UVs, so the app needs no extension support.
//  3. Drops the emissive map (it is almost black in the source and only costs memory).
//  4. Welds and simplifies the mesh to about --triangles triangles.
//  5. Centers the body on x/z, puts the feet on y = 0 and optionally scales to --height metres.
//  6. Shrinks textures to JPEG: base colour 2048 px, normal and metal/roughness 1024 px.
//  7. Adds a small rig (Hips > Spine > Chest > Head) with smooth height-based skin weights,
//     which the app's procedural motion (breathing, sway, nod) drives.
//  8. Writes one self-contained GLB and checks it against the app's asset rules.
import { Logger, NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { dedup, getBounds, prune, simplify, transformPrimitive, unpartition, weld } from '@gltf-transform/functions';
import { MeshoptDecoder, MeshoptSimplifier } from 'meshoptimizer';
import sharp from 'sharp';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { applyUvTransform } from './uv_transform.mjs';

// Joint heights as a fraction of body height, measured on an adult standing pose.
const JOINTS = [
  { name: 'Hips', at: 0.485 },
  { name: 'Spine', at: 0.56 },
  { name: 'Chest', at: 0.69 },
  { name: 'Head', at: 0.86 },
];
// Height bands (fractions of body height) where weight moves from one joint to the next.
// The neck band is narrow so the shoulders stay with the chest when the head nods.
const BLENDS = [
  { from: 0.445, to: 0.56 }, // Hips  -> Spine
  { from: 0.56, to: 0.69 }, // Spine -> Chest
  { from: 0.835, to: 0.885 }, // Chest -> Head
];
const SLICE_HALF_HEIGHT = 0.01;
const MAX_GLB_BYTES = 5 * 1024 * 1024;

const args = parseArgs(process.argv.slice(2));
const io = new NodeIO()
  .registerExtensions(ALL_EXTENSIONS)
  .registerDependencies({ 'meshopt.decoder': MeshoptDecoder });
await Promise.all([MeshoptDecoder.ready, MeshoptSimplifier.ready]);

const doc = await io.read(args.input);
doc.setLogger(new Logger(Logger.Verbosity.WARN));
const root = doc.getRoot();
const scene = root.getDefaultScene() ?? root.listScenes()[0];
if (!scene) fail('The source has no scene.');
if (root.listSkins().length || root.listAnimations().length) {
  fail('The source already has a skin or animations. The app can load such a model directly; ' +
    'this tool only rigs static meshes.');
}

const meshNodes = [];
scene.traverse((node) => { if (node.getMesh()) meshNodes.push(node); });
const primitives = meshNodes.flatMap((node) => node.getMesh().listPrimitives());
if (meshNodes.length !== 1 || primitives.length !== 1) {
  fail(`Expected exactly one mesh with one primitive (one material); found ${meshNodes.length} mesh nodes ` +
    `and ${primitives.length} primitives. Join the parts into one mesh first (for example in Blender).`);
}
const [meshNode] = meshNodes;
const mesh = meshNode.getMesh();
const prim = primitives[0];
if (prim.getMode() !== 4) fail('The mesh must be made of triangles.');
const material = prim.getMaterial();
if (!material) fail('The mesh has no material.');
const sourceTriangles = triangleCount(prim);

// 1. Node transforms -> vertices.
transformPrimitive(prim, meshNode.getWorldMatrix());

// 2. KHR_texture_transform -> UVs.
bakeTextureTransforms(prim, material);

// 3. Emissive map is unused.
material.setEmissiveTexture(null).setEmissiveFactor([0, 0, 0]).setName('CompanionSkin');
for (const semantic of prim.listSemantics()) {
  if (!['POSITION', 'NORMAL', 'TEXCOORD_0'].includes(semantic)) prim.setAttribute(semantic, null);
}
if (!prim.getAttribute('NORMAL')) fail('The mesh has no normals.');
if (!prim.getAttribute('TEXCOORD_0')) fail('The mesh has no UVs.');

// 4. Fewer triangles. A small error budget keeps the face and hands; it grows only if needed.
await doc.transform(weld());
for (const error of [0.001, 0.002, 0.005, 0.01]) {
  const current = triangleCount(prim);
  if (current <= args.triangles * 1.05) break;
  await doc.transform(simplify({ simplifier: MeshoptSimplifier, ratio: args.triangles / current, error }));
}

// 5. Feet on the floor, body centered, optional scale.
{
  const { min, max } = primitiveBounds(prim);
  const scale = args.height > 0 ? args.height / (max[1] - min[1]) : 1;
  const cx = (min[0] + max[0]) / 2;
  const cz = (min[2] + max[2]) / 2;
  transformPrimitive(prim, [
    scale, 0, 0, 0,
    0, scale, 0, 0,
    0, 0, scale, 0,
    -cx * scale, -min[1] * scale, -cz * scale, 1,
  ]);
}

// 6. Smaller textures.
await compressTexture(material.getBaseColorTexture(), { size: 2048, quality: 90, chroma: '4:2:0' });
await compressTexture(material.getNormalTexture(), { size: 1024, quality: 85, chroma: '4:4:4' });
await compressTexture(material.getMetallicRoughnessTexture(), { size: 1024, quality: 85, chroma: '4:4:4' });
await compressTexture(material.getOcclusionTexture(), { size: 1024, quality: 85, chroma: '4:4:4' });

// 7. Rig.
const height = rigBody(prim);

// Clean scene graph: Character > [Hips > Spine > Chest > Head, Body].
const body = doc.createNode('Body').setMesh(mesh).setSkin(root.listSkins()[0]);
const character = doc.createNode('Character').addChild(root.listSkins()[0].listJoints()[0]).addChild(body);
for (const child of scene.listChildren()) scene.removeChild(child);
for (const node of root.listNodes()) {
  if (node !== character && node !== body && !root.listSkins()[0].listJoints().includes(node)) node.dispose();
}
scene.addChild(character).setName('Scene');
root.setDefaultScene(scene);
mesh.setName('CompanionBody');

// 8. One buffer, no leftovers, then write and check.
for (const extension of root.listExtensionsUsed()) {
  if (extension.extensionName === 'KHR_texture_transform') extension.dispose();
}
await doc.transform(prune({ keepAttributes: true }), dedup(), unpartition());

const glb = await io.writeBinary(doc);
mkdirSync(dirname(args.output), { recursive: true });
writeFileSync(args.output, glb);
verify(glb.byteLength);
console.log(`Source: ${sourceTriangles} triangles. Output: ${triangleCount(prim)} triangles, ` +
  `${prim.getAttribute('POSITION').getCount()} vertices, height ${height.toFixed(4)} m, ${glb.byteLength} bytes.`);
console.log(`Wrote ${args.output}`);

function parseArgs(argv) {
  const options = { triangles: 50000, height: 0 };
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--triangles' || arg === '--height') {
      const value = Number(argv[++i]);
      if (!Number.isFinite(value) || value < 0) fail(`${arg} needs a positive number.`);
      options[arg.slice(2)] = value;
    } else if (arg.startsWith('--')) {
      fail(`Unknown option ${arg}.`);
    } else {
      positional.push(arg);
    }
  }
  if (!positional[0]) {
    fail('Usage: node process_companion.mjs <source.glb> [output.glb] [--triangles 50000] [--height 0]');
  }
  if (options.triangles < 1000) fail('--triangles must be at least 1000.');
  return {
    ...options,
    input: resolve(positional[0]),
    output: resolve(positional[1] ?? 'out/companion.glb'),
  };
}

function fail(message) {
  console.error(`Error: ${message}`);
  process.exit(1);
}

function triangleCount(primitive) {
  return Math.floor((primitive.getIndices()?.getCount() ?? primitive.getAttribute('POSITION').getCount()) / 3);
}

function primitiveBounds(primitive) {
  const position = primitive.getAttribute('POSITION');
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  const p = [];
  for (let i = 0; i < position.getCount(); i++) {
    position.getElement(i, p);
    for (let k = 0; k < 3; k++) {
      min[k] = Math.min(min[k], p[k]);
      max[k] = Math.max(max[k], p[k]);
    }
  }
  return { min, max };
}

function bakeTextureTransforms(primitive, mat) {
  const infos = [
    mat.getBaseColorTextureInfo(),
    mat.getNormalTextureInfo(),
    mat.getMetallicRoughnessTextureInfo(),
    mat.getOcclusionTextureInfo(),
    mat.getEmissiveTextureInfo(),
  ].filter((info, index) => info && [
    mat.getBaseColorTexture(),
    mat.getNormalTexture(),
    mat.getMetallicRoughnessTexture(),
    mat.getOcclusionTexture(),
    mat.getEmissiveTexture(),
  ][index]);

  let baked = null;
  for (const info of infos) {
    const transform = info.getExtension('KHR_texture_transform');
    const texCoord = transform?.getTexCoord() ?? info.getTexCoord();
    if (texCoord !== 0) fail('Only TEXCOORD_0 is supported.');
    const key = transform
      ? JSON.stringify([transform.getOffset(), transform.getRotation(), transform.getScale()])
      : JSON.stringify([[0, 0], 0, [1, 1]]);
    if (baked !== null && baked.key !== key) {
      fail('Textures use different UV transforms, so they cannot share one set of UVs.');
    }
    baked = { key, transform };
    info.setExtension('KHR_texture_transform', null);
    info.setTexCoord(0);
  }
  if (!baked?.transform) return;

  const offset = baked.transform.getOffset();
  const rotation = baked.transform.getRotation();
  const scale = baked.transform.getScale();
  const source = primitive.getAttribute('TEXCOORD_0');
  const uv = doc.createAccessor('TEXCOORD_0')
    .setType('VEC2')
    .setArray(new Float32Array(source.getCount() * 2))
    .setBuffer(source.getBuffer());
  const t = [];
  for (let i = 0; i < source.getCount(); i++) {
    source.getElement(i, t);
    uv.setElement(i, applyUvTransform(t, offset, rotation, scale));
  }
  primitive.setAttribute('TEXCOORD_0', uv);
}

async function compressTexture(texture, { size, quality, chroma }) {
  if (!texture) return;
  const jpeg = await sharp(Buffer.from(texture.getImage()))
    .resize(size, size, { fit: 'inside', withoutEnlargement: true, kernel: 'lanczos3' })
    .removeAlpha()
    // mozjpeg's size tricks, but baseline (not progressive): stb_image on the phone decodes
    // baseline JPEGs faster and without buffering every coefficient of the image.
    .jpeg({
      quality,
      chromaSubsampling: chroma,
      progressive: false,
      optimiseCoding: true,
      trellisQuantisation: true,
      overshootDeringing: true,
      quantisationTable: 3,
    })
    .toBuffer();
  texture.setImage(new Uint8Array(jpeg)).setMimeType('image/jpeg').setURI('');
}

function smoothstep(edge0, edge1, x) {
  const t = Math.min(1, Math.max(0, (x - edge0) / (edge1 - edge0)));
  return t * t * (3 - 2 * t);
}

function sliceCentroid(position, y, bodyHeight, name) {
  let x = 0;
  let z = 0;
  let n = 0;
  const p = [];
  for (let i = 0; i < position.getCount(); i++) {
    position.getElement(i, p);
    if (Math.abs(p[1] - y) > SLICE_HALF_HEIGHT * bodyHeight) continue;
    x += p[0];
    z += p[2];
    n++;
  }
  if (!n) fail(`No vertices near the ${name} height; is the model a standing human?`);
  return [x / n, z / n];
}

function rigBody(primitive) {
  const position = primitive.getAttribute('POSITION');
  const count = position.getCount();
  const { min, max } = primitiveBounds(primitive);
  const bodyHeight = max[1] - min[1];

  // Arms hang beside the torso from hip to chest height, so slices there are lopsided. The pivots are
  // therefore taken from the hip slice (depth only; sideways the hips sit on the body centre line) and
  // the neck slice (no arms there), and the spine runs straight between them.
  const hips = sliceCentroid(position, min[1] + JOINTS[0].at * bodyHeight, bodyHeight, JOINTS[0].name);
  const head = sliceCentroid(position, min[1] + JOINTS[3].at * bodyHeight, bodyHeight, JOINTS[3].name);
  const hipsPivot = [0, hips[1]];
  const world = JOINTS.map((joint) => {
    const t = (joint.at - JOINTS[0].at) / (JOINTS[3].at - JOINTS[0].at);
    return [
      hipsPivot[0] + (head[0] - hipsPivot[0]) * t,
      min[1] + joint.at * bodyHeight,
      hipsPivot[1] + (head[1] - hipsPivot[1]) * t,
    ];
  });
  const p = [];

  const skin = doc.createSkin('CompanionRig');
  const buffer = position.getBuffer();
  const inverseBind = new Float32Array(16 * JOINTS.length);
  let parent = null;
  JOINTS.forEach((joint, j) => {
    const local = parent ? world[j].map((v, k) => v - world[j - 1][k]) : world[j];
    const node = doc.createNode(joint.name).setTranslation(local);
    if (parent) parent.addChild(node);
    skin.addJoint(node);
    inverseBind.set([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -world[j][0], -world[j][1], -world[j][2], 1], j * 16);
    parent = node;
  });
  skin.setSkeleton(skin.listJoints()[0]);
  skin.setInverseBindMatrices(doc.createAccessor('inverseBindMatrices').setType('MAT4').setArray(inverseBind).setBuffer(buffer));

  const jointIndices = new Uint8Array(count * 4);
  const weights = new Float32Array(count * 4);
  for (let i = 0; i < count; i++) {
    position.getElement(i, p);
    const f = (p[1] - min[1]) / bodyHeight;
    const up = BLENDS.map((band) => smoothstep(band.from, band.to, f));
    const w = [1 - up[0], up[0] - up[1], up[1] - up[2], up[2]];
    jointIndices.set([0, 1, 2, 3], i * 4);
    weights.set(w, i * 4);
  }
  primitive
    .setAttribute('JOINTS_0', doc.createAccessor('JOINTS_0').setType('VEC4').setArray(jointIndices).setBuffer(buffer))
    .setAttribute('WEIGHTS_0', doc.createAccessor('WEIGHTS_0').setType('VEC4').setArray(weights).setBuffer(buffer));
  return bodyHeight;
}

function verify(byteLength) {
  const problems = [];
  if (byteLength >= MAX_GLB_BYTES) problems.push(`file is ${byteLength} bytes; the app allows less than ${MAX_GLB_BYTES}`);
  if (root.listExtensionsRequired().length) {
    problems.push(`extensions still required: ${root.listExtensionsRequired().map((e) => e.extensionName)}`);
  }
  if (root.listBuffers().length !== 1) problems.push(`expected one buffer, found ${root.listBuffers().length}`);
  if (root.listTextures().some((t) => !t.getImage())) problems.push('a texture is not embedded');
  const names = root.listSkins()[0]?.listJoints().map((j) => j.getName()) ?? [];
  for (const needed of ['Head', 'Chest', 'Spine']) if (!names.includes(needed)) problems.push(`missing joint ${needed}`);
  const bounds = getBounds(root.getDefaultScene());
  if (Math.abs(bounds.min[1]) > 1e-3) problems.push(`feet are at y=${bounds.min[1]}, expected 0`);
  if (problems.length) fail(`Output check failed:\n - ${problems.join('\n - ')}`);
}

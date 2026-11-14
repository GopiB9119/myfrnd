// Builds the GitHub Pages site into dist/.
// The 3D model and screenshots are taken from the app and store folders, so the site
// always shows exactly what the app ships.
import { copyFile, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const src = path.join(here, 'src');
const dist = path.join(here, 'dist');

const siteUrl = (process.env.SITE_URL || 'https://gopib9119.github.io/myfrnd').replace(/\/+$/, '');
const basePath = new URL(siteUrl).pathname.replace(/\/+$/, '');

const model = path.join(repo, 'app', 'src', 'main', 'assets', 'models', 'companion.glb');
const screenshots = [
  'screenshot_1_companion.png',
  'screenshot_2_eyebreaks.png',
  'screenshot_3_hydration.png',
  'screenshot_4_tasks.png',
  'screenshot_5_editor.png',
  'screenshot_6_privacy.png',
].map((name) => path.join(repo, 'play_store_assets', name));

// <model-viewer>'s bundle includes three.js, Lit and gainmap-js; their licenses ship with it.
const bundledLicenses = [
  '@google/model-viewer',
  'three',
  'lit',
  'lit-html',
  'lit-element',
  '@lit/reactive-element',
  '@monogrid/gainmap-js',
];

async function copyTree(from, to) {
  await mkdir(to, { recursive: true });
  for (const entry of await readdir(from, { withFileTypes: true })) {
    const a = path.join(from, entry.name);
    const b = path.join(to, entry.name);
    if (entry.isDirectory()) {
      await copyTree(a, b);
    } else if (entry.name.endsWith('.html')) {
      const html = (await readFile(a, 'utf8'))
        .replaceAll('%SITE_URL%', siteUrl)
        .replaceAll('%BASE_PATH%', basePath);
      await writeFile(b, html);
    } else {
      await copyFile(a, b);
    }
  }
}

async function vendor() {
  const out = path.join(dist, 'vendor');
  await mkdir(out, { recursive: true });
  const modules = path.join(here, 'node_modules');
  await copyFile(path.join(modules, '@google', 'model-viewer', 'dist', 'model-viewer.min.js'),
    path.join(out, 'model-viewer.min.js'));
  const parts = [];
  for (const name of bundledLicenses) {
    const dir = path.join(modules, ...name.split('/'));
    const pkg = JSON.parse(await readFile(path.join(dir, 'package.json'), 'utf8'));
    const file = (await readdir(dir)).find((f) => /^licen[cs]e/i.test(f));
    if (!file) throw new Error(`No license file found for ${name}`);
    const text = await readFile(path.join(dir, file), 'utf8');
    parts.push(`${name}@${pkg.version} (${pkg.license})\n${'='.repeat(60)}\n${text.trim()}\n`);
  }
  await writeFile(path.join(out, 'LICENSES.txt'),
    `Open-source software bundled in model-viewer.min.js\n\n${parts.join('\n\n')}`);
}

async function assets() {
  const out = path.join(dist, 'assets');
  const shots = path.join(out, 'shots');
  await mkdir(shots, { recursive: true });
  await copyFile(model, path.join(out, 'companion.glb'));
  for (const [i, file] of screenshots.entries()) {
    const n = i + 1;
    await copyFile(file, path.join(shots, `shot-${n}.png`));
    await sharp(file).resize({ width: 540 }).webp({ quality: 82 }).toFile(path.join(shots, `shot-${n}.webp`));
  }
  await socialImage(path.join(out, 'og.png'));
}

// 1200 x 630 link preview: title on the left, two real screenshots on the right.
async function socialImage(file) {
  const phoneW = 260;
  const phoneH = 520;
  const radius = 28;
  const mask = Buffer.from(
    `<svg width="${phoneW}" height="${phoneH}"><rect width="${phoneW}" height="${phoneH}" rx="${radius}" ry="${radius}"/></svg>`);
  const phone = (input) => sharp(input).resize(phoneW, phoneH).composite([{ input: mask, blend: 'dest-in' }]).png().toBuffer();
  const background = Buffer.from(`
    <svg width="1200" height="630" xmlns="http://www.w3.org/2000/svg">
      <defs><linearGradient id="g" x1="0" y1="0" x2="0.4" y2="1">
        <stop offset="0" stop-color="#EEF3E5"/><stop offset="1" stop-color="#DBE8CE"/>
      </linearGradient></defs>
      <rect width="1200" height="630" fill="url(#g)"/>
      <g font-family="Roboto, 'Segoe UI', 'DejaVu Sans', Arial, sans-serif" fill="#1D3025">
        <text x="72" y="190" font-size="30" letter-spacing="5" fill="#536357">YOUR EVERYDAY COMPANION</text>
        <text x="72" y="290" font-size="92" font-weight="700">myfrnd</text>
        <text x="72" y="370" font-size="44" font-weight="600">A little care.</text>
        <text x="72" y="425" font-size="44" font-weight="600">A better day.</text>
        <text x="72" y="500" font-size="26" fill="#536357">A friendly 3D companion for Android</text>
      </g>
      <rect x="640" y="47" width="${phoneW + 12}" height="${phoneH + 12}" rx="${radius + 6}" fill="#D9E1D4"/>
      <rect x="914" y="77" width="${phoneW + 12}" height="${phoneH + 12}" rx="${radius + 6}" fill="#D9E1D4"/>
    </svg>`);
  await sharp(background)
    .composite([
      { input: await phone(screenshots[0]), left: 646, top: 53 },
      { input: await phone(screenshots[2]), left: 920, top: 83 },
    ])
    .flatten({ background: '#EEF3E5' })
    .png()
    .toFile(file);
}

// Fails the build if a page points at a file or an in-page anchor that does not exist.
async function checkLinks() {
  const problems = [];
  const pages = (await readdir(dist)).filter((f) => f.endsWith('.html'));
  for (const page of pages) {
    const html = await readFile(path.join(dist, page), 'utf8');
    const idList = [...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]);
    const ids = new Set(idList);
    for (const id of ids) {
      if (idList.indexOf(id) !== idList.lastIndexOf(id)) problems.push(`${page}: duplicate id "${id}"`);
    }
    for (const [, ref] of html.matchAll(/\s(?:href|src)="([^"]+)"/g)) {
      if (/^(https?:|mailto:|data:)/.test(ref)) continue;
      if (ref.startsWith('#')) {
        if (ref.length > 1 && !ids.has(ref.slice(1))) problems.push(`${page}: missing anchor ${ref}`);
        continue;
      }
      let target = ref.split(/[?#]/)[0];
      if (target.startsWith('/')) {
        if (!target.startsWith(`${basePath}/`)) { problems.push(`${page}: ${ref} is outside ${basePath}/`); continue; }
        target = target.slice(basePath.length + 1);
      }
      const resolved = path.join(dist, target || 'index.html');
      const exists = existsSync(resolved) && ((await stat(resolved)).isFile() || existsSync(path.join(resolved, 'index.html')));
      if (!exists) problems.push(`${page}: broken link ${ref}`);
    }
    if (/%(SITE_URL|BASE_PATH)%/.test(html)) problems.push(`${page}: unreplaced placeholder`);
  }
  if (problems.length) throw new Error(`Link check failed:\n${problems.join('\n')}`);
  return pages.length;
}

async function sizeOf(dir) {
  let total = 0;
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    total += entry.isDirectory() ? await sizeOf(p) : (await stat(p)).size;
  }
  return total;
}

await rm(dist, { recursive: true, force: true });
await copyTree(src, dist);
await vendor();
await assets();
const pages = await checkLinks();
console.log(`Built ${pages} pages for ${siteUrl}/ into ${path.relative(repo, dist)} ` +
  `(${(await sizeOf(dist) / 1e6).toFixed(1)} MB). Link check passed.`);

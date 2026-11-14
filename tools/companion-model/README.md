# Companion model pipeline

This folder turns a source human GLB into `app/src/main/assets/models/companion.glb`, the
3D companion the app shows on the dashboard and in reminder overlays. The output is
deterministic: the same source and options always give a byte-identical file.

The shipped model comes from `monochrome-moment.zip` (file
`source/Monochrome Moment (3).glb`, 22.8 MB, about 250,000 triangles). That zip is the
original asset and is **not** committed.

## Run it

Needs Node.js 20 or newer.

```powershell
cd tools\companion-model
npm ci
npm test

# 1. Take the source GLB out of the zip.
Expand-Archive ..\..\monochrome-moment.zip -DestinationPath $env:TEMP\mm-src -Force
Copy-Item "$env:TEMP\mm-src\source\Monochrome Moment (3).glb" $env:TEMP\mm-src\source.glb

# 2. Build (writes out\companion.glb, which git ignores) and check the result.
node process_companion.mjs $env:TEMP\mm-src\source.glb
node inspect_companion.mjs out\companion.glb

# 3. Ship it, then run the asset tests.
Copy-Item out\companion.glb ..\..\app\src\main\assets\models\companion.glb
cd ..\..
.\gradlew.bat :app:testDebugUnitTest
```

Options: `node process_companion.mjs <source.glb> [output.glb] [--triangles 50000] [--height 0]`.
`--height` scales the body to that many metres (0 keeps the source size, 1.90 m here).
`node inspect_companion.mjs <file.glb> --weights` also prints the average joint weights per
height band, which is the quickest way to check the rig.

## What the build does

1. **Bakes node transforms.** The source stores whole-number positions with a tiny node scale.
2. **Bakes `KHR_texture_transform` into the UVs** (all four textures share one transform), so
   the app needs no glTF extensions. The math is the same as Filament's (`uv_transform.mjs`,
   tested by `npm test`), so the baked model looks the same as the original did.
3. **Drops the emissive map.** It is almost black in the source and only costs memory.
4. **Welds and simplifies** to about 50,000 triangles (from about 250,000).
5. **Centres the body** on x/z and puts the feet on y = 0.
6. **Re-encodes textures as baseline JPEG**: base colour 2048 px; normal and metal/roughness
   1024 px. Baseline (not progressive) JPEGs load about 20% faster on the device.
7. **Adds a small rig**, Hips > Spine > Chest > Head, with smooth height-based skin weights.
   The neck band is narrow, so the shoulders stay with the chest when the head nods.
8. **Checks the result** against the same rules as `CompanionModelAssetTest`: under 5 MB, one
   buffer, embedded images, no required extensions, the joints present, feet on the ground.

Result for the shipped model: 50,000 triangles, 42,085 vertices, 1.90 m tall, about 3.2 MB.

## Using a different model

The app accepts any self-contained GLB that passes `CompanionModelAssetTest`.

- **Static model (no skeleton), one mesh and one material:** run it through this tool. It will
  stop with a clear message if the source has several meshes or materials.
- **Model that already has a skeleton and animation clips:** the app can play it directly,
  as long as the skeleton has joints named `Head`, `Chest` and `Spine` (the asset test checks
  this). Clips are matched by name words such as `idle`/`breathing`, `wave`/`hello`, `walk`
  and `talk`/`nod`/`point`; weapon, fight and running clips are ignored (see
  `HumanRig.resolveMoves`). Without clips, the app moves the Hips/Spine/Chest/Head joints
  itself.

Keep the GLB under 5 MB. Every megabyte is added to the APK and to load time.

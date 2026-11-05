Place your 3D animated character (.glb) model in this directory!

FilamentRenderer automatically checks the following asset paths:
1. models/dev_companion.glb
2. models/character.glb
3. dev_companion.glb
4. character.glb

When a .glb file is present, Google Filament Engine loads it with:
- Translucent zero-alpha background overlay
- Studio lighting (Warm Key light + Cool Rim/Fill light)
- Skeletal animation tracks: "walk_in", "talk", "walk_out"
- Clean memory destruction when off-screen

If no .glb model is present, the app automatically displays the built-in animated companion character (navy sweater, khaki chinos, and talking/walking gestures) as an out-of-the-box fallback!

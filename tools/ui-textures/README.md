# Tulpa UI textures (HyperFrames → PNG)

BlockFrame **maximalist-neobrutalist** GUI sprites for the Tulpa screens, authored as
HTML/CSS and rendered to PNG via [HyperFrames](https://hyperframes.heygen.com).
Style spec: `../../FRAME.md` (4px black borders + 8px hard offset shadows, square
corners, five candy pastels — pink `#FE90E8` / blue `#C0F7FE` / green `#99E885` /
yellow `#F7CB46` / cream `#FFDC8B` — on black/white/offwhite `#FFFDF5`).

Sprites are authored at **2× the GUI-unit size** (so they're crisp around GUI scale 2)
and blitted in the screen code with text/items drawn on top in the MC font.

## Workflow

Requires Node ≥22 + FFmpeg + a headless browser (all auto-resolved by `npx`).

```bash
cd tools/ui-textures
# edit index.html (one composition; data-width/height = the texture's pixel size)
npx hyperframes snapshot --at 0            # -> snapshots/frame-00-at-0.0s.png (OPAQUE white bg)
# eyeball it, iterate, then copy the final into the mod assets:
cp snapshots/frame-00-at-0.0s.png ../../common/src/main/resources/assets/tulpa/textures/gui/<name>.png
```

**Transparent glyph sprites** (icons that sit ON a button/panel, e.g. the eye toggle): `snapshot`
bakes an opaque white background, so use `render --format png-sequence` instead — it writes **RGBA**
frames, preserving the transparent body (`background:#00000000`). Antialiased edges then blend to
transparent (not white), so rotated/curved shapes don't get a white fringe:

```bash
npx hyperframes render --format png-sequence --fps 1 -o renders/<name>
cp renders/<name>/frame_000001.png ../../common/src/main/resources/assets/tulpa/textures/gui/sprites/<name>.png
```

`npx hyperframes doctor --json | jq .ok` checks the render environment.

## Rendered so far
- `panel.png` — the TulpaScreen panel chrome (offwhite ground + dot-grid, blue header
  band, 4px black border, tilted yellow corner badge). → `assets/tulpa/textures/gui/panel.png`
- `eye.png` / `eye_off.png` / `chevron_up.png` / `chevron_down.png` (`eye.html` / `eye_off.html` /
  `chevron.html`) — Cottage (WARM) palette icons: a square amber block + brown border + square pupil
  (eye), a brown diagonal slash (eye_off), amber scroll triangles (chevron). **Explicit pixel grid**
  (tip.html technique — 1px divs), rendered transparent via `render --format png-sequence`, then the
  alpha is snapped to 0/255 and colours to the palette: **pixel art with NO antialias / gradient
  edge** (a gradient on a low-res sprite looks awful). → `assets/tulpa/textures/gui/sprites/`

## To render next
button (yellow CTA), tabs (label-pills), item-slot frame, toast card, dropdown.

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
npx hyperframes snapshot --at 0            # -> snapshots/frame-00-at-0.0s.png
# eyeball it, iterate, then copy the final into the mod assets:
cp snapshots/frame-00-at-0.0s.png ../../common/src/main/resources/assets/tulpa/textures/gui/<name>.png
```

`npx hyperframes doctor --json | jq .ok` checks the render environment.

## Rendered so far
- `panel.png` — the TulpaScreen panel chrome (offwhite ground + dot-grid, blue header
  band, 4px black border, tilted yellow corner badge). → `assets/tulpa/textures/gui/panel.png`

## To render next
button (yellow CTA), tabs (label-pills), item-slot frame, toast card, dropdown.

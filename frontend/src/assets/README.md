# `src/assets`

Brand and illustration assets consumed through Vite's asset pipeline (imported from a module, or
referenced from `index.html`), so every one of them is hashed and emitted by the build. Nothing
here lives in `public/`.

| File | Used by | Notes |
| --- | --- | --- |
| `pronto-logo.jpg` | `app/AppLayout` header brand | 1024×1024 source; the header crops the lockup out of it via `background-position`/`background-size` (see `AppLayout.module.css`). |
| `pronto-mark.png` | the browser favicon (`index.html`) | The character alone — see below. |
| `mascot/*.png` | `shared/components/Mascot` | Per-state mascot art, transparent background. |
| `rollete-animation-images/*.png` | `shared/components/ProfessionIllustration` | One drawing per profession. |

## `pronto-mark.png` — the favicon

**Not a new mark.** It is `mascot/pronto-running-wrench.png` — the existing Pronto running
professional — reframed onto a square canvas so it survives being drawn at 16-32px in a browser
tab. The full logo was used before and was unreadable at that size: the wordmark dominates the
asset and the character next to it collapses into a smudge.

Derived by cropping the source to its alpha bounding box, scaling that box to 94% of the canvas
and centring it on a transparent 256×256 canvas:

```python
from PIL import Image
src = Image.open('src/assets/mascot/pronto-running-wrench.png').convert('RGBA')
mark = src.crop(src.getchannel('A').getbbox())          # (12, 68, 392, 485) -> 380×417
scale = (256 * 0.94) / max(mark.size)
mark = mark.resize([round(v * scale) for v in mark.size], Image.LANCZOS)
canvas = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
canvas.paste(mark, [(256 - v) // 2 for v in mark.size], mark)
canvas.save('src/assets/pronto-mark.png')
```

Re-run that snippet to regenerate it if the mascot art is ever replaced. Transparency is preserved
(RGBA), so the icon sits correctly on both light and dark browser chrome.

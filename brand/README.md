# Ferngeist

A pale green ghost whose tail curls into a fern fiddlehead, on a deep forest-green ground.

*App icon · v2*

<img src="ferngeist-icon.svg" width="160" alt="Ferngeist app icon: a pale green ghost whose tail curls into a fern fiddlehead, on a deep forest-green ground">

A small ghost whose tail unrolls like a young fern. One continuous shape, drawn from circles and a logarithmic spiral, with every main size taken from the golden ratio.

## The name

*Fern* is German for distant, *Geist* for ghost — a ghost from far away. The name is a euphemism for remote control: the agent works the machine from somewhere else.

## The idea

### Half ghost, half fiddlehead

A fern frond starts life as a tight coil, the fiddlehead, and unrolls as it grows. Ferngeist borrows that coil for its tail. The body is a soft sheet ghost: a round head, a scalloped hem, and a side that sweeps down and inward into the curl. Five small leaflets along the outside of the coil, growing toward the outer edge, are what make the tail read as a fern rather than a snail shell.

## Construction

### One unit, scaled by φ

*Construction overlay: the head circle, the side circles and the logarithmic spiral with its pole.*

Everything is sized from the head radius **R = 17** (on the 108 grid) and φ ≈ 1.618.

| Head | R |
| --- | --- |
| Right shoulder | R·φ<sup>1.5</sup> |
| Curve into the tail | R |
| Left side | R·φ² |
| Hem bumps | R / φ² |
| Eyes (w × h) | R/φ⁴ × R/φ³ |
| Eye spacing | R / φ |
| Tail spiral growth | × φ² per turn |
| Tail thickness | outer ÷ φ |

Two parts are set by eye: the hem guide curve, which has to meet the left side and the tail cleanly, and the leaflets, which grow ×1.2 each.

## The tail

### A true logarithmic spiral

The tail follows r = R·e<sup>bθ</sup>, winding two full turns and growing by φ² each turn. Its inner edge is the outer edge shrunk by φ around the same centre, so the tail thins at a constant rate all the way to the tip. A strict golden spiral (φ⁴ per turn) was tried and left the coil too loose; real fiddleheads vary in how tightly they wind, so the rate was chosen to look right rather than to match a species.

## Sizes

### Reads from 180 px down to 16

180 · 64 · 48 · 32 · 16 px

At 16–32 px the leaflets soften into a slightly textured edge and the inner coil becomes a dot. The ghost-plus-curl silhouette still holds.

## Colour

### Forest ground, pale spirit

| Colour | Hex |
| --- | --- |
| Canopy | `#1D5A43` |
| Understory | `#0B2E22` |
| Spirit | `#F3FBF5` |
| Mist | `#DDF2E4` |
| Frond | `#A8DDBC` |
| Glow | `#9FE3BC` · 28% |

The background is a radial gradient from Canopy to Understory, lit from the upper left. The ghost runs Spirit → Mist → Frond from head to tail, so the tail feels slightly more alive than the body.

## Using the files

### Ready for both stores

| File | Use |
| --- | --- |
| `ferngeist-icon.svg` | Master artwork, 108 × 108 grid |
| `…-foreground.svg` / `…-background.svg` | Android adaptive icon layers |
| `ferngeist-icon-monochrome.svg` | Android 13+ themed icon, notification and single-colour uses |
| `ferngeist-icon-1024.png` | App Store master; square, no transparency, iOS adds the rounded corners |
| `…-512 / -192 / -180 / -48.png` | Play Store listing, web manifest, Apple touch icon, small UI |

- The ghost sits inside Android's 66 dp safe zone, so circle, squircle and rounded-square masks all keep it whole.
- Don't add your own corner rounding or drop shadow; the platforms apply both.
- Keep the ghost pale on a dark ground. On light backgrounds use the full icon, not the bare foreground.

## In this repo

`ferngeist-icon.svg` sits beside this file and is the source the app's drawables are cut from.

| Layer | File |
| --- | --- |
| Background | `app/src/main/res/drawable/ic_launcher_background.xml` |
| Foreground | `app/src/main/res/drawable/ic_launcher_foreground.xml` |
| Monochrome | `app/src/main/res/drawable/ic_launcher_monochrome.xml` |
| Splash | `app/src/main/res/drawable/ic_launcher_splash.xml` and `drawable-night/ic_launcher_splash.xml` |
| Notification | `app/src/main/res/drawable/ic_notification.xml` |
| Store listing | `app/src/main/ic_launcher-playstore.png` (512 px) |

The drawables keep the 108 grid and the φ construction; coordinates are rounded to 0.1 unit to stay under Android lint's vector-path limit. There is no iOS build, so the App Store exports listed above aren't produced.

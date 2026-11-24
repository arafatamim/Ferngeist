# Loading indicators

Vendored from AndroidX Compose Material 3 **1.5.0**:

- [Source archive](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.5.0/material3-android-1.5.0-sources.jar)
- `commonMain/androidx/compose/material3/LoadingIndicator.kt`
- `commonMain/androidx/compose/material3/internal/ShapeUtil.kt` (polygon transform and closed morph path)
- `commonMain/androidx/compose/material3/tokens/LoadingIndicatorTokens.kt` (dimensions and theme roles)

Copyright 2024 The Android Open Source Project. Licensed under Apache-2.0;
see [LICENSE](LICENSE). Original copyright headers are retained in the Kotlin files.

Local changes: moved into core/common's loading package, split API/defaults,
drawing, geometry and animation into separate files, used public theme roles
and dimensions in place of internal tokens, and kept only the closed, unrotated
morph path conversion used by this component. Default property names follow the
repository's lowerCamelCase convention. MaterialShapes remains provided by the
existing Material 3 dependency.

Motion and drawing are fitted to the Material 3 loading-indicator spec video rather than to
upstream, by simulating every frame and maximising silhouette IoU against the video:

- Morphs keep unit filled area and rotate about the normalized polygons' center (0.5, 0.5), as
  the spec does; centroid centering would hold Pentagon still where the spec swings it.
- A 667 ms beat (4667 ms / 7 shapes). The turn leads at the beat start: a 385 ms cubic-bezier
  tween that overshoots about 2% and settles without spinning the outline backwards against the
  4666 ms global rotation. The morph spring (damping 0.7, stiffness 715) starts 84 ms later; a
  radial pop spring (damping 0.49, stiffness 260) is kicked when the morph passes 10%.
- `LoadingIndicatorReference` holds the per-shape size and turn angle, and the shared pop impulse (measured for
  the default seven shapes; custom sequences wrap the tables). The last beat turns 108 degrees,
  so each loop rotates 1008 degrees and closes on SoftBurst's starting orientation, as in the
  video; the indicator starts at that orientation.

Against the video (one steady loop whose orientation matches it): mean silhouette IoU 0.963,
minimum 0.79, area error 2.3% RMS, max-radius error 1.7% RMS. The remaining gap is mostly shape
detail: the spec's SoftBurst does not match MaterialShapes' exactly, and radius errors gather in
the beats into Cookie4Sided and SoftBurst.
Caller-selected shape sequences are preserved. System motion disable skips radial impulses. The
determinate indicator still uses the upstream-style envelope scale.

The dedicated `preview/LoadingIndicatorPreview.kt` shows both variants at 28,
48 and 96 dp in light and dark themes. Use Android Studio interactive preview
to watch the full loop.

Direct loading and contained-loading consumers use this copy. Material 3's
PullToRefreshDefaults.LoadingIndicator remains library-owned.

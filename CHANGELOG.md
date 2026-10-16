# Changelog

Format: [Keep a Changelog](https://keepachangelog.com). Versions: [docs/RELEASING.md](docs/RELEASING.md).

## [Unreleased] — planned as 0.17.0

### Added
- English UI by default; Russian, Spanish, Portuguese (BR), German, French, Italian,
  Polish, Turkish, Ukrainian, Japanese, Chinese (Simplified), Korean.
- Demo mode with a made-up user's calendar (used for screenshots).
- GPL-3.0 license; font licenses (SIL OFL 1.1) bundled in the app.

### Changed
- Sample events now fill the whole visible year around today, not a fixed calendar year.
- New defaults: the wheel goes clockwise and "now" is at the top. Both settings moved
  to the top of the settings panel. Existing installs switch once to the new defaults.
- Short events now sit at the outer edge of the ring by default; long events go inward.
- Paper theme: bigger inner circle.
- Fonts re-subset with Latin Extended (Polish, Turkish, etc.); Comfortaa shipped unmodified.

## [0.16.0] — 2026-10-02
- Home screen widget: the same circle engine rendered to an image. Pinch-and-drag
  preview when configuring, scale presets (year / quarter / month / week), theme and
  background options; the widget shape follows whole months, weeks or days.

## [0.15.0] — 2026-10-02
- Tile seams fixed at the year boundary; outline thickness capped; up to 6 render threads.

## [0.14.0]
- Fixed square holes in tiles when the GPU dropped off-screen layers.

## [0.13.0]
- Tiled rendering like in maps: 512 px tiles per zoom level, recorded in the background,
  rasterized once by the GPU and then only moved.

## [0.12.0]
- Background scene recording, event index by time, frame counter in settings.

## [0.11.0]
- Bundled fonts (Nunito, Comfortaa, Rubik, Unbounded). Short events are rounded bars.
  Paper theme draws a thin stroke for every day.

## [0.10.0]
- Design system: themes Dusk (default), Cartoon, Paper, Plum, Ink, Chalk, Classic,
  plus fine-tuning controls. Ring with a gradient from the centre, glow, grain, volume.
  Labels always read upright. New icon.

## [0.9.0]
- Labels never disappear at deep zoom; zoom up to 1,000,000× (seconds are readable).

## [0.8.0]
- Fractal time labels day → hour → minute → second. Planets and the clock hand on a
  separate layer. "Hand moves" mode. Snap to planets.

## [0.7.0]
- Double-precision camera, the Solar System to scale, minutes and seconds,
  spring bounce, map-style taps, two-finger rotation.

## [0.6.0]
- Fling inertia at any zoom, zoom inertia.

## [0.5.0]
- Camera survives restart, pan inertia, level-of-detail ticks, long events above/below.

## [0.4.0]
- Event titles fade in with zoom.

## [0.3.0]
- Titles everywhere, one text orientation, visible clock hand, transparent system bars.
  (Builds labelled 1.0 and 1.1 before it were a numbering mistake and belong here.)

## [0.2.0]
- Exact event arcs with titles, week lines, vector icon.

## [0.1.0]
- Native Kotlin + Canvas port, zero dependencies.

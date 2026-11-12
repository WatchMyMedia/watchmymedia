# watchmy.media brand

The mark is a film reel: five holes, a play triangle for its hub, and the
film's tail running out along the bottom to the right ("Direction L · Reel" on
the design canvas). It is white with burgundy details and is the whole icon,
with no tile behind it, so it reads on light and dark grounds alike. Where a
platform needs an opaque square (iOS, macOS, Play, Android's adaptive
background, the Apple TV icon) it sits on the near-black ground. In the domain
wordmark the "." of watchmy.media is gold.

The earlier marks are still available: the aperture (a gold play triangle in a
lens ring, concept B) and the play-and-dot (concept A), both on a burgundy
tile. Pass `--style aperture` or `--style play-dot` to `tools/make_sources.py`
(or set its `STYLE`), set `TILE` in `tools/export.py` back to burgundy, and
redraw `BrandMark` in the web app (`web/src/components/BrandMark.tsx`), the
Apple apps (`apple/Kit/Sources/WMMUI/Marks.swift`), the Android app
(`android/core/ui/…/components/Brand.kt`) and the Samsung prototype
(`web/tizen/App.tsx`), which draw the mark in code. Design canvas:
https://claude.ai/artifact/88tQqNarhhnbEJijimnMNG (private until shared).

Everything here is generated from one definition of the geometry in
`tools/make_sources.py`. Edit that, not the exported files.

## Colors

Burgundy and gold. Burgundy is too dark to read on the near-black ground
(1.4:1), so it is always a fill there, never text or a thin line.

| Role | Hex | Use |
|---|---|---|
| Burgundy | `#5A1414` | the reel's rim, holes, play and sprockets; the TV banners; primary buttons; the accent on light (13:1 on `#FAFAFA`) |
| Gold | `#FFB612` | the accent on dark (10.9:1) and on burgundy (7.7:1); the wordmark's "." on dark |
| Ground | `#0F0F10` | dark backgrounds, and behind the reel where an icon must be opaque |
| Mark | `#F5F5F5` | the reel's body, on dark and on light (its burgundy rim edges it on white) |
| Ink | `#0F0F10` | the wordmark on light |
| Muted | `#A3A3A3` on dark, `#525252` on light | "media" in the wordmark |

In the web UI, gold marks progress, selection, focus and badges (with black
text on it), burgundy fills primary buttons and the avatar, and warnings are
orange `#FB923C` so they do not read as the accent.

Type is Inter: Bold for "watchmy" / "WatchMy", SemiBold for "media", tracking
−2%. Logo files carry the text as outlines, so they need no installed font.

## Which file goes where

| Need | File |
|---|---|
| Website or app `<head>` | `export/web/*` and the tags in `export/web/head.html` |
| Social preview (watchmy.media) | `export/web/og-image.png` (1200×630) |
| iOS / iPadOS app | `export/ios/AppIcon.appiconset` (drop into `Assets.xcassets`; light, dark and tinted) |
| Apple TV app | `export/tvos/App Icon & Top Shelf Image.brandassets` (drop into `Assets.xcassets`): the layered icon, Back and Front, at 1280×768 for the App Store and 400×240 @1x/@2x, and the Top Shelf images, 1920×720 and the wide 2320×720 @1x/@2x |
| Android app | `export/android/res` (`make brand` copies it into `android/app/src/main/res`; adaptive, round, themed monochrome, and the Android TV / Google TV launcher banner `drawable-xhdpi/tv_banner.png`, 320×180) |
| Google Play listing | `export/android/play-store-icon-512.png`, `play-feature-graphic-1024x500.png`, and for the Android TV listing `play-tv-banner-1280x720.png` (uploaded by hand) |
| macOS app | `export/macos/AppIcon.icns` (1024 PNG beside it), and the menu bar's template image `MenuBarIcon.png` (36 px, copied into `macos/Resources`) |
| Cast receiver | `export/logo/logo-stacked-dark.svg` and `mark-dark.svg` (copied into `web/receiver/public` as `logo.svg` and `mark.svg`) |
| Windows app | `export/windows/watchmymedia.ico` (16–256 px); `tray-light.ico` and `tray-dark.ico` (16–32 px, for the taskbar theme) |
| Linux desktop | `export/linux/hicolor` (install under `/usr/share/icons/hicolor`) |
| Logos for documents and sites | `export/logo/*.svg` (PNG @2x for the horizontal and app lockups) |

Pick `-dark` files for dark backgrounds and `-light` files for light ones. Use
`logo-horizontal-*` (watchmy.media) for the website and marketing,
`logo-app-*` (WatchMyMedia) inside the app, and the mark alone where space is
tight. The reel's colors are the same on either ground. Below 32 px use the
favicon artwork, which has bigger holes and drops the play and the sprockets,
which blur into the holes at that size. The exporter already does this for
favicons, the Windows .ico and small Linux sizes, and the apps' `BrandMark`
switches below 32 points. One-color files (`mark-mono-*`, the Safari pinned
tab, Android's themed icon, the menu bar icon) cut the holes, play and
sprockets out.

Keep clear space around the mark equal to the reel's rim. Do not recolor the
reel outside the palette above, put it back on a tile, or set the wordmark in
another font.

## Regenerating

```bash
make brand                                # re-export everything from brand/src
make brand-sources INTER_DIR=/path/to/ttf # rebuild brand/src after changing the geometry
```

`make brand` needs `rsvg-convert` and ImageMagick 7 (`brew install librsvg
imagemagick`); the macOS `.icns` also needs `iconutil`, so it is skipped on
other systems. `make brand-sources` also needs `hb-shape` (HarfBuzz) and
fontTools, plus the Inter TTFs from https://github.com/rsms/inter (SIL Open
Font License 1.1): the static ones in `extras/ttf` of the Inter 4.1 release,
which reproduce the checked-in sources exactly (Inter 3 gives slightly
different outlines). The fonts are not checked in. Only their outlines end up
in the logo files. If `make` cannot find fontTools, pass your own `PATH`
(`make brand-sources PATH="$PATH" …`): the Makefile puts Homebrew first.

The web set is copied into `web/public`, so the app serves its favicons and
manifest from its root.
The favicons, social image and dark horizontal logo (as `logo.svg`) are also
copied into `site/`, the watchmy.media website; the install guide
(`site/guide/install.html`) draws the Mac icon inline and is changed by hand.
`make brand` also copies the iOS and Apple TV asset sets into `apple/`, the
Android icons into `android/app`, the menu bar icon into `macos/Resources` and
the receiver's logo and mark into `web/receiver/public`.

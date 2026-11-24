# watchmy.media brand

The mark is a film reel: five holes, a play triangle for its hub, and the
film's tail running out along the bottom to the right. It is white with
burgundy details and is the whole icon, with no tile behind it, so it reads on light and dark grounds alike. Where a
platform needs an opaque square (iOS, macOS, Play, Android's adaptive
background, the Apple TV icon) it sits on the near-black ground. In the domain
wordmark the "." of watchmy.media is gold.

The files in `export/` are generated. Their sources and the tools that export
them are not in this repository, so change a logo by replacing the exported
files, not by editing one by hand. The apps also draw the mark in code:
`BrandMark` in the web app (`web/src/components/BrandMark.tsx`), the Apple apps
(`apple/Kit/Sources/WMMUI/Marks.swift`), the Android app
(`android/core/ui/…/components/Brand.kt`) and the Samsung app
(`web/tv/ui/Icon.tsx`).

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
| Android app | `export/android/res` (copied into `android/app/src/main/res`; adaptive, round, themed monochrome, and the Android TV / Google TV launcher banner `drawable-xhdpi/tv_banner.png`, 320×180) |
| Google Play listing | `export/android/play-store-icon-512.png`, `play-feature-graphic-1024x500.png`, and for the Android TV listing `play-tv-banner-1280x720.png` (uploaded by hand) |
| macOS app | `export/macos/AppIcon.icns` (1024 PNG beside it), and the menu bar's template image `MenuBarIcon.png` (36 px, copied into `macos/Resources`) |
| Cast receiver | `export/logo/logo-stacked-dark.svg` and `mark-dark.svg` (copied into `web/receiver/public` as `logo.svg` and `mark.svg`) |
| Windows app | `export/windows/watchmymedia.ico` (16–256 px); `tray-light.ico` and `tray-dark.ico` (16–32 px, for the taskbar theme) |
| Linux desktop | `export/linux/hicolor` (install under `/usr/share/icons/hicolor`) |
| LG webOS TV app | `export/webos/icon-80.png` and `icon-130.png` (the package's `icon` and `largeIcon`, packed by `pnpm build:webos`), and `icon-400.png` for LG's store |
| Logos for documents and sites | `export/logo/*.svg` (PNG @2x for the horizontal and app lockups) |

Pick `-dark` files for dark backgrounds and `-light` files for light ones. Use
`logo-horizontal-*` (watchmy.media) for the website and marketing,
`logo-app-*` (WatchMyMedia) inside the app, and the mark alone where space is
tight. The reel's colors are the same on either ground. Below 32 px use the
favicon artwork, which has bigger holes and drops the play and the sprockets,
which blur into the holes at that size. The exported files already do this for
favicons, the Windows .ico and small Linux sizes, and the apps' `BrandMark`
switches below 32 points. One-color files (`mark-mono-*`, the Safari pinned
tab, Android's themed icon, the menu bar icon) cut the holes, play and
sprockets out.

Keep clear space around the mark equal to the reel's rim. Do not recolor the
reel outside the palette above, put it back on a tile, or set the wordmark in
another font.

## Where the copies are

The web set is in `web/public`, so the app serves its favicons and manifest
from its root. The iOS and Apple TV asset sets are in `apple/`, the Android
icons in `android/app`, the menu bar icon in `macos/Resources`, and the
receiver's logo and mark in `web/receiver/public`. A fork with its own mark
replaces those copies as well as `export/`.

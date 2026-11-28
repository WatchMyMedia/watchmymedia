# Accessibility: hearing the dialogue and reading the subtitles

WatchMyMedia has four settings that help people hear what's said and read what's on screen:
**Boost dialogue**, **Reduce loud sounds**, **Subtitle size** and **Subtitle timing**. They
belong to each person and are kept on the server, so they're the same on every app that person
signs in to. They need a server on WatchMyMedia 2.3 or later; with an older server, the 2.3 apps
don't show them.

The details of how the sound is changed, and of subtitles and languages in general, are in
[Playback and transcoding](playback.md).

## Where to find them

| App | Boost dialogue, Reduce loud sounds | Subtitle size | Subtitle timing |
|---|---|---|---|
| Web app | Settings › Audio & subtitles, and the player's Audio & subtitles panel | Settings › Audio & subtitles, with a preview | The player's Audio & subtitles panel |
| Android phones and tablets | Settings › Audio & subtitles, and the player's audio and subtitles options | Settings › Audio & subtitles, with a preview | The player's audio and subtitles options |
| Android TV and Google TV | Settings › Audio & subtitles, and the player's Audio & subtitles panel | Settings › Audio & subtitles, with a preview | The player's panel: Left and Right on the timing row |
| iPhone and iPad | Settings › Audio & subtitles, and the player's audio and subtitles options | Settings › Audio & subtitles, with a preview | The player's audio and subtitles options |
| Apple TV | Settings › Audio & subtitles, and the player's Audio & subtitles tab (swipe down while a video plays) | Settings › Audio & subtitles, with a full-screen preview | The Audio & subtitles tab: Left and Right on the timing row |
| Samsung and LG TVs | Settings › Audio and subtitles, and the player's Audio and subtitles panel | Settings › Audio and subtitles, with a preview | The player's panel: Left and Right on the timing row |
| Casting to a TV | The casting controls in the web app, the Android app, and the iPhone and iPad app | Follows the person who cast | The same casting controls |

## Boost dialogue and Reduce loud sounds

- **Boost dialogue** makes voices clearer over music and effects.
- **Reduce loud sounds** brings loud scenes closer to the level of speech, for watching
  quietly.

Each is Off, Medium or High. Changing one while watching picks up at the same moment.

The server applies them when it converts the sound, so they sound the same in every app, when
casting and on Live TV, and they work in app versions from before 2.3 too, once the person has
chosen them in a 2.3 app. While either is on:

- the sound is converted even when the device could have played it as it is;
- surround stays surround where the device takes Dolby Digital Plus;
- lossless sound, Dolby Atmos and passing the film's own sound to a receiver come back once
  both are Off.

Downloads for watching offline keep the film's own sound.

On Apple TV, Apple's own Enhance Dialogue and Reduce Loud Sounds, in Settings › Video and Audio,
work on top of these.

## Subtitle size

Subtitle size is Smaller, Normal, Larger or Largest, for the subtitles each app draws:

| App | How Normal is decided |
|---|---|
| iPhone, iPad and Apple TV | The device's own caption size and style (Accessibility › Subtitles & Captioning in the device's Settings), which the other steps scale |
| Android phones and tablets | The device's own caption size and style when captions are on in Android's settings, which the other steps scale |
| Android TV, and Samsung and LG TVs | A size made for reading across the room |
| Web app and Cast | A size in proportion to the picture, so it looks the same in a window and full screen |

Subtitles with their own styles (ASS) and subtitles that are part of the picture (image
subtitles, which the server draws into the video) keep their own size. Safari's own full screen
on an iPhone, and picture in picture in a browser, show the browser's subtitles, without the
size or timing.

On Android TV and the Samsung and LG app, Subtitle size moved from Playback › On this TV in 2.3.
A TV that had its own size gives it to the person once, unless they have already chosen one.

## Subtitle timing

When a track's subtitles come too early or too late, the player moves them **Earlier** or
**Later** in steps of a tenth of a second, up to a minute either way. Holding the button, or the
remote's Left or Right, keeps moving them. **Reset** puts them back.

- The timing is saved for that person, for that subtitle track of that file, so their other
  devices play it in step.
- An admin can also **Save for everyone**, so everyone who plays that file gets the timing.
  Anyone's own timing still wins for them, and their Reset goes back to the admin's.
- Subtitles that are part of the picture take the timing too: the video picks up again where
  it was a moment after the last press.
- A download watched without a connection keeps a timing change for that play only.

## Subtitles and audio languages

Settings › Audio & subtitles also keeps each person's audio languages and subtitle languages,
and when subtitles start: Off, Forced only, When the audio isn't in my languages, or On. A video
never starts on an audio description or a commentary by itself; pick one in the player. See
[Subtitles and languages](playback.md#subtitles-and-languages).

## Not yet checked

These settings were measured on test material and tried in browsers, emulators and simulators.
They haven't yet been tried on every real TV and phone. The apps haven't been checked
throughout with a screen reader (VoiceOver, TalkBack) or with a keyboard alone. If something
gets in your way, please [open an issue](https://github.com/WatchMyMedia/watchmymedia/issues).

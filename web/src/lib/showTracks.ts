import type { components } from '../api/schema'
import { knownLanguage, languageName } from './format'

type ShowTracks = components['schemas']['ShowTracks']
type ShowTracksInput = components['schemas']['ShowTracksInputBody']
type MediaInfo = components['schemas']['MediaInfo']

// The sound and subtitles a person picked for a show (API level 34): the
// server starts each episode with them, the show's page shows them in Sound
// and Subtitles, and a pick there or in the player keeps them for the show.

/** A choice made before playing, as the title pages hold it: a sound stream index or "", and "", "off" or a subtitle id. */
export type TrackChoice = { audio: string; subtitle: string }

/** Whether two language tags name one language: "en" and "eng", "pt-BR" and "pt-br". */
export function sameLanguage(a?: string | null, b?: string | null): boolean {
  const x = knownLanguage(a)
  const y = knownLanguage(b)
  if (!x || !y) return false
  if (x.toLowerCase() === y.toLowerCase()) return true
  const nx = languageName(x)
  return nx !== x.toUpperCase() && nx === languageName(y)
}

/** Whether anything is remembered: a language for either, or subtitles off. */
export function hasShowTracks(t: ShowTracks | undefined): t is ShowTracks {
  return !!t && (!!t.audioLanguage || !!t.subtitleLanguage || t.subtitlesOff)
}

/**
 * What Sound and Subtitles start on for an episode: the file's tracks in the
 * remembered languages, Off when subtitles were turned off, else Automatic
 * (the account's settings).
 */
export function rememberedChoice(t: ShowTracks | undefined, probe?: Pick<MediaInfo, 'audio' | 'subtitles'>): TrackChoice {
  const choice: TrackChoice = { audio: '', subtitle: '' }
  if (!t || !probe) return choice
  if (t.audioLanguage) {
    const a = probe.audio?.find((x) => sameLanguage(x.language, t.audioLanguage))
    if (a) choice.audio = String(a.index)
  }
  if (t.subtitlesOff) choice.subtitle = 'off'
  else if (t.subtitleLanguage) {
    const subs = (probe.subtitles ?? []).filter((x) => sameLanguage(x.language, t.subtitleLanguage))
    const s = subs.find((x) => !x.forced) ?? subs[0]
    if (s) choice.subtitle = `s${s.index}`
  }
  return choice
}

/**
 * What to keep for the show after a pick on its page: only the part that
 * changed. Automatic forgets that part; Off turns subtitles off; a track
 * keeps its language. Nothing when the pick has no language to keep.
 */
export function pickBody(before: TrackChoice, after: TrackChoice, probe?: Pick<MediaInfo, 'audio' | 'subtitles'>): ShowTracksInput | undefined {
  const body: ShowTracksInput = {}
  if (after.audio !== before.audio) {
    if (after.audio === '') body.audioLanguage = ''
    else {
      const lang = knownLanguage(probe?.audio?.find((a) => String(a.index) === after.audio)?.language)
      if (lang) body.audioLanguage = lang
    }
  }
  if (after.subtitle !== before.subtitle) {
    if (after.subtitle === '') {
      body.subtitleLanguage = ''
      body.subtitlesOff = false
    } else if (after.subtitle === 'off') body.subtitlesOff = true
    else {
      const lang = knownLanguage(probe?.subtitles?.find((s) => `s${s.index}` === after.subtitle)?.language)
      if (lang) body.subtitleLanguage = lang
    }
  }
  return Object.keys(body).length ? body : undefined
}

/** What to keep after a pick in the player: a sound track's language, or a subtitle's, or subtitles off. */
export function playerPickBody(pick: { kind: 'audio' | 'subtitle'; language?: string } | { kind: 'off' }): ShowTracksInput | undefined {
  if (pick.kind === 'off') return { subtitlesOff: true }
  const lang = knownLanguage(pick.language)
  if (!lang) return undefined
  return pick.kind === 'audio' ? { audioLanguage: lang } : { subtitleLanguage: lang }
}

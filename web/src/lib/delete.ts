import type { components } from '../api/schema'
import { formatBytes, plural } from './format'

type Preview = components['schemas']['DeletePreview']
type App = components['schemas']['DeleteApp']
type Options = components['schemas']['DeleteOptions']
type Result = components['schemas']['DeleteResult']

export type DeleteType = 'movie' | 'show' | 'season' | 'episode'

/** The admin's choices in the Delete dialog (PLAN-ARR.md §8.1). */
export type Choices = {
  /** Movies and shows: keep it in the app, unmonitored, rather than remove it. */
  keep: boolean
  /** Movies and shows removed: don't add them again from import lists. */
  exclude: boolean
  /** Seasons: keep getting new episodes of the season. */
  keepMonitored: boolean
  /** The connections ticked to delete from. */
  picked: number[]
}

/** A run of text, or a path shown as code. */
export type Piece = string | { path: string }

export const appName = (kind: string) => (kind === 'radarr' ? 'Radarr' : 'Sonarr')

const whole = (type: DeleteType) => type === 'movie' || type === 'show'

/** The apps that answered: only they can delete. */
export function reachable(p: Preview): App[] {
  return p.apps.filter((a) => !a.error)
}

/** The server's defaults, with every app that answered ticked. */
export function initialChoices(p: Preview): Choices {
  return { keep: p.choices.keep, exclude: p.choices.exclude, keepMonitored: p.choices.keepMonitored, picked: reachable(p).map((a) => a.connectionId) }
}

/** The apps the delete goes to. */
export function chosenApps(p: Preview, c: Choices): App[] {
  return reachable(p).filter((a) => c.picked.includes(a.connectionId))
}

/** "12 files · 48.3 GB" for what the apps delete. */
export function filesLine(apps: App[]): string {
  if (apps.length === 0) return 'Nothing ticked'
  const files = apps.reduce((n, a) => n + a.files, 0)
  if (files === 0) return 'No files'
  return `${plural(files, 'file')} · ${formatBytes(apps.reduce((n, a) => n + a.size, 0))}`
}

/** The request body: only the choices the item has, and the apps the admin saw and ticked. */
export function deleteBody(type: DeleteType, c: Choices): Options {
  const body: Options = { connections: c.picked }
  if (whole(type)) {
    body.keep = c.keep
    body.exclude = !c.keep && c.exclude
  }
  if (type === 'season') body.keepMonitored = c.keepMonitored
  return body
}

/** "Sonarr", "Sonarr and Sonarr 4K", "A, B and C". */
function names(apps: App[]): string {
  const n = apps.map((a) => a.connection)
  return n.length > 1 ? `${n.slice(0, -1).join(', ')} and ${n[n.length - 1]}` : (n[0] ?? '')
}

/** "Sonarr deletes the folder /tv/Harbor Lights.", "… the 10 files in /tv/Harbor Lights/Season 02." */
export function whatGoes(type: DeleteType, apps: App[], keep: boolean): Piece[] {
  if (type === 'episode') return [episodeGoes(apps)]
  const out: Piece[] = []
  apps.forEach((a, i) => {
    if (i > 0) out.push(i === apps.length - 1 ? ' and ' : ', ')
    if (whole(type) && !keep) {
      out.push(`${a.connection} deletes ${i === 0 ? 'the folder ' : ''}`, { path: a.folder })
      return
    }
    if (a.files === 0 || !a.folder) {
      out.push(`${a.connection} has no ${type === 'show' || type === 'season' ? 'files' : 'file'} for it, so it is only unmonitored`)
      return
    }
    const files = type === 'movie' ? 'the file' : a.files === 1 ? 'the file' : `the ${a.files} files`
    out.push(`${a.connection} deletes ${files} in `, { path: a.folder })
  })
  out.push('.')
  return out
}

/** An episode is unmonitored as its file goes, so Sonarr doesn't fetch it again (PLAN-EPISODE-PAGE.md §3). */
function episodeGoes(apps: App[]): string {
  const deleting = apps.filter((a) => a.files > 0)
  const unmonitored = apps.filter((a) => a.files === 0)
  const out: string[] = []
  if (deleting.length === 1) out.push(`${names(deleting)} deletes the file and stops monitoring the episode, so it isn’t downloaded again.`)
  if (deleting.length > 1) out.push(`${names(deleting)} delete their files and stop monitoring the episode, so it isn’t downloaded again.`)
  if (unmonitored.length > 0) out.push(`${names(unmonitored)} ${unmonitored.length === 1 ? 'has' : 'have'} no file for it, so it is only unmonitored there.`)
  return out.join(' ')
}

/** "Harbor.Lights.S03E07.mkv · 4.2 GB": the episode's files in the apps' folders, which go; how many go when none is found. */
export function goingFiles(apps: App[], files: { path: string; relativePath: string; size: number }[]): string[] {
  const inside = (path: string, folder: string) => !!folder && path.startsWith(folder) && /^[\\/]/.test(path.slice(folder.replace(/[\\/]+$/, '').length))
  const going = files.filter((f) => apps.some((a) => a.files > 0 && inside(f.path, a.localFolder)))
  if (going.length === 0) return [filesLine(apps)]
  return going.map((f) => `${f.relativePath.split(/[\\/]/).pop() ?? f.relativePath} · ${formatBytes(f.size)}`)
}

/** Whether the files go to a recycling bin, or for good. */
export function whereTheyGo(type: DeleteType, apps: App[], keep: boolean): Piece[] {
  if (apps.length === 0) return []
  // A removed movie or show goes as its folder; otherwise its files go.
  const folder = whole(type) && !keep
  const one = folder ? apps.length === 1 : apps.reduce((n, a) => n + a.files, 0) === 1
  const [it, goes] = one ? ['It', 'goes'] : ['They', 'go']
  const files = folder || !one ? 'The files are' : 'The file is'
  const emptied = (days?: number) => (days ? `, which is emptied after ${plural(days, 'day')}.` : '.')
  const first = apps[0]
  const same = apps.every((a) => (a.recycleBin ?? '') === (first.recycleBin ?? '') && (a.recycleDays ?? 0) === (first.recycleDays ?? 0))
  if (same && first.recycleBin) {
    const whose = apps.length === 1 ? `${first.connection}’s` : `each ${appName(first.kind)}’s`
    return [`${it} ${goes} to ${whose} recycling bin (`, { path: first.recycleBin }, `)${emptied(first.recycleDays)}`]
  }
  if (same) {
    const who = apps.length === 1 ? `${first.connection} has` : `none of these ${appName(first.kind)}s has`
    return [`${files} deleted for good: ${who} no recycling bin set.`]
  }
  const out: Piece[] = []
  apps.forEach((a, i) => {
    if (i > 0) out.push(' ')
    const them = folder || a.files !== 1 ? 'them' : 'it'
    if (a.recycleBin) out.push(`${a.connection} moves ${them} to its recycling bin (`, { path: a.recycleBin }, `)${emptied(a.recycleDays)}`)
    else out.push(`${a.connection} deletes ${them} for good: it has no recycling bin set.`)
  })
  return out
}

/** The warning when removing the folder takes files the app doesn't list. */
export function extraWarning(type: DeleteType, apps: App[], keep: boolean): string | null {
  const n = apps.reduce((s, a) => s + a.extra, 0)
  if (!whole(type) || keep || n === 0) return null
  const one = apps.length === 1
  const who = one ? apps[0].connection : `the ${appName(apps[0].kind)}s`
  return `The folder${one ? '' : 's'} also hold${one ? 's' : ''} ${plural(n, 'file')} ${who} ${one ? 'doesn’t' : 'don’t'} list. ${n === 1 ? 'It goes' : 'They go'} too.`
}

/** "The file also holds episode 2, which goes too." */
export function otherEpisodes(apps: App[]): string | null {
  const eps = [...new Set(apps.flatMap((a) => a.otherEpisodes ?? []))].sort((a, b) => a - b)
  if (eps.length === 0) return null
  const list = eps.length === 1 ? `episode ${eps[0]}` : `episodes ${eps.slice(0, -1).join(', ')} and ${eps[eps.length - 1]}`
  return `The file also holds ${list}, which ${eps.length === 1 ? 'goes' : 'go'} too.`
}

/** The item's files no app manages, which stay. */
export function keptLine(type: DeleteType, kept: number): string | null {
  if (kept === 0) return null
  return `${plural(kept, 'other file')} of this ${type} ${kept === 1 ? 'isn’t' : 'aren’t'} in an app’s folder, so ${kept === 1 ? 'it stays' : 'they stay'}.`
}

/** "Delete Season 2 of Harbor Lights?", "Delete “The Long Dark”?" */
export function deleteTitle(type: DeleteType, name: string, opts: { season?: number; episode?: string } = {}): string {
  if (type === 'season') return `Delete ${opts.season === 0 ? 'Specials' : `Season ${opts.season}`} of ${name}?`
  if (type === 'episode') return `Delete “${opts.episode}”?`
  return `Delete ${name}?`
}

/** One line per app whose delete failed, and Seerr's when it couldn't be told. */
export function failures(r: Result): string[] {
  const out = r.apps.filter((a) => a.error).map((a) => `${a.connection} couldn’t delete: ${a.error}`)
  if (r.seerr?.error) out.push(`${r.seerr.connection} couldn’t be told: ${r.seerr.error}. It may still show this as available; clear it there.`)
  return out
}

/** What Seerr is told, when it is connected (PLAN-ARR.md §8.1). */
export function seerrLine(type: DeleteType, seerr?: string): string | null {
  if (!seerr) return null
  if (whole(type)) return `${seerr} is told too, so it stops showing the ${type} as available and it can be requested again.`
  return `${seerr} is told too: it clears its record of the show and rebuilds it from Sonarr, which loses its request history for the show.`
}

/** "Quiet Field (2019).mkv · HEVC · E-AC3 · 2160p" for the Replace file dialog. */
export function fileSummary(f: { relativePath: string; videoCodec?: string; audioCodec?: string; height?: number }): string {
  const name = f.relativePath.split('/').pop() ?? f.relativePath
  return [name, f.videoCodec?.toUpperCase(), f.audioCodec?.toUpperCase(), f.height ? `${f.height}p` : ''].filter(Boolean).join(' · ')
}

/**
 * A delete that also takes recordings WatchMyMedia made: they go from the
 * server as well as what the apps delete. The preview's totals hold both, so
 * the recordings' part is what is left after the apps' own.
 */
export function recordingFilesLine(p: { files: number; size: number; apps: { files: number; size: number }[] }): string {
  const files = Math.max(0, p.files - p.apps.reduce((n, a) => n + a.files, 0))
  const size = Math.max(0, p.size - p.apps.reduce((n, a) => n + a.size, 0))
  if (!files) return 'Its recording files are deleted from the server too.'
  return `${files === 1 ? 'The recording file' : `The ${files} recording files`} (${formatBytes(size)}) ${files === 1 ? 'is' : 'are'} deleted from the server too.`
}

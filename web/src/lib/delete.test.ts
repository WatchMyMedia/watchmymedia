import { describe, expect, it } from 'vitest'
import type { components } from '../api/schema'
import {
  chosenApps,
  deleteBody,
  deleteTitle,
  extraWarning,
  failures,
  fileSummary,
  filesLine,
  goingFiles,
  initialChoices,
  keptLine,
  otherEpisodes,
  recordingFilesLine,
  seerrLine,
  whatGoes,
  whereTheyGo,
} from './delete'

type App = components['schemas']['DeleteApp']
type Preview = components['schemas']['DeletePreview']

const app = (over: Partial<App> = {}): App => ({
  connectionId: 1,
  connection: 'Sonarr',
  kind: 'sonarr',
  folder: '/tv/Harbor Lights',
  localFolder: '/Volumes/TV/Harbor Lights',
  files: 12,
  size: 48.3 * 1024 ** 3,
  extra: 0,
  ...over,
})

const preview = (apps: App[], over: Partial<Preview['choices']> = {}): Preview => ({
  managed: apps.length > 0,
  files: apps.reduce((n, a) => n + a.files, 0),
  size: apps.reduce((n, a) => n + a.size, 0),
  apps,
  kept: 0,
  choices: { keep: false, exclude: false, keepMonitored: false, airing: false, ...over },
})

describe('choices', () => {
  it('start from the server’s defaults with every app that answered ticked', () => {
    const p = preview([app(), app({ connectionId: 2, error: 'connection refused' })], { keepMonitored: true })
    const c = initialChoices(p)
    expect(c).toEqual({ keep: false, exclude: false, keepMonitored: true, picked: [1] })
    expect(chosenApps(p, c).map((a) => a.connectionId)).toEqual([1])
  })

  it('send only what the item has, and the apps ticked', () => {
    const c = { keep: false, exclude: true, keepMonitored: true, picked: [2] }
    expect(deleteBody('movie', c)).toEqual({ connections: [2], keep: false, exclude: true })
    // Kept in the app, nothing is excluded.
    expect(deleteBody('show', { ...c, keep: true })).toEqual({ connections: [2], keep: true, exclude: false })
    expect(deleteBody('season', c)).toEqual({ connections: [2], keepMonitored: true })
    expect(deleteBody('episode', c)).toEqual({ connections: [2] })
  })
})

describe('what the dialog says', () => {
  it('counts the files', () => {
    expect(filesLine([app()])).toBe('12 files · 48 GB')
    expect(filesLine([app({ files: 1, size: 8.1 * 1024 ** 3 }), app({ files: 1, size: 48.2 * 1024 ** 3 })])).toBe('2 files · 56 GB')
    expect(filesLine([])).toBe('Nothing ticked')
  })

  it('names the folders', () => {
    expect(whatGoes('show', [app()], false)).toEqual(['Sonarr deletes the folder ', { path: '/tv/Harbor Lights' }, '.'])
    const radarrs = [
      app({ connection: 'Radarr', kind: 'radarr', folder: '/movies/Dead Reckoning Bay (2026)' }),
      app({ connection: 'Radarr 4K', kind: 'radarr', folder: '/movies-4k/Dead Reckoning Bay (2026)' }),
    ]
    expect(whatGoes('movie', radarrs, false)).toEqual([
      'Radarr deletes the folder ',
      { path: '/movies/Dead Reckoning Bay (2026)' },
      ' and ',
      'Radarr 4K deletes ',
      { path: '/movies-4k/Dead Reckoning Bay (2026)' },
      '.',
    ])
    expect(whatGoes('season', [app({ files: 10, folder: '/tv/Harbor Lights/Season 02' })], false)).toEqual([
      'Sonarr deletes the 10 files in ',
      { path: '/tv/Harbor Lights/Season 02' },
      '.',
    ])
    // Kept in Radarr, only the file goes.
    expect(whatGoes('movie', [app({ connection: 'Radarr', files: 1, folder: '/movies/X' })], true)).toEqual([
      'Radarr deletes the file in ',
      { path: '/movies/X' },
      '.',
    ])
    expect(whatGoes('season', [app({ files: 0, folder: '' })], false)).toEqual(['Sonarr has no files for it, so it is only unmonitored', '.'])
  })

  it('says an episode is unmonitored as its file goes', () => {
    expect(whatGoes('episode', [app({ files: 1 })], false)).toEqual(['Sonarr deletes the file and stops monitoring the episode, so it isn’t downloaded again.'])
    expect(whatGoes('episode', [app({ files: 1 }), app({ connectionId: 2, connection: 'Sonarr 4K', files: 1 })], false)).toEqual([
      'Sonarr and Sonarr 4K delete their files and stop monitoring the episode, so it isn’t downloaded again.',
    ])
    expect(whatGoes('episode', [app({ files: 1 }), app({ connectionId: 2, connection: 'Sonarr 4K', files: 0 })], false)).toEqual([
      'Sonarr deletes the file and stops monitoring the episode, so it isn’t downloaded again. Sonarr 4K has no file for it, so it is only unmonitored there.',
    ])
  })

  it('names the episode’s files in the apps’ folders', () => {
    const season = app({ files: 1, size: 4.2 * 1024 ** 3, localFolder: '/Volumes/TV/Harbor Lights/Season 03' })
    const f = (path: string, size = 4.2 * 1024 ** 3) => ({ path, relativePath: path.replace('/Volumes/TV/', ''), size })
    const ours = f('/Volumes/TV/Harbor Lights/Season 03/Harbor.Lights.S03E07.2160p.mkv')
    expect(goingFiles([season], [ours, f('/Volumes/Other/Harbor Lights S03E07.mkv', 1024 ** 3)])).toEqual(['Harbor.Lights.S03E07.2160p.mkv · 4.2 GB'])
    // A folder named alike is not the same folder; a trailing slash is.
    expect(goingFiles([season], [f('/Volumes/TV/Harbor Lights/Season 03 extras/x.mkv')])).toEqual(['1 file · 4.2 GB'])
    expect(goingFiles([{ ...season, localFolder: '/Volumes/TV/Harbor Lights/Season 03/' }], [ours])).toEqual(['Harbor.Lights.S03E07.2160p.mkv · 4.2 GB'])
    expect(goingFiles([{ ...season, localFolder: 'D:\\TV\\Harbor Lights' }], [{ path: 'D:\\TV\\Harbor Lights\\e7.mkv', relativePath: 'Harbor Lights\\e7.mkv', size: 1024 }])).toEqual([
      'e7.mkv · 1.0 KB',
    ])
    // An app without the file deletes nothing of it.
    expect(goingFiles([{ ...season, files: 0, size: 0 }], [ours])).toEqual(['No files'])
  })

  it('says where the files go', () => {
    expect(whereTheyGo('show', [app({ recycleBin: '/data/recycle', recycleDays: 7 })], false)).toEqual([
      'It goes to Sonarr’s recycling bin (',
      { path: '/data/recycle' },
      '), which is emptied after 7 days.',
    ])
    expect(whereTheyGo('season', [app({ recycleBin: '/data/recycle', recycleDays: 7 })], false)[0]).toBe('They go to Sonarr’s recycling bin (')
    expect(whereTheyGo('show', [app()], false)).toEqual(['The files are deleted for good: Sonarr has no recycling bin set.'])
    expect(whereTheyGo('episode', [app({ files: 1 })], false)).toEqual(['The file is deleted for good: Sonarr has no recycling bin set.'])
    expect(whereTheyGo('movie', [app({ files: 1, recycleBin: '/bin', recycleDays: 7 })], true)[0]).toBe('It goes to Sonarr’s recycling bin (')
    const both = [
      app({ connection: 'Radarr', kind: 'radarr', recycleBin: '/data/recycle', recycleDays: 7 }),
      app({ connection: 'Radarr 4K', kind: 'radarr', recycleBin: '/data/recycle', recycleDays: 7 }),
    ]
    expect(whereTheyGo('movie', both, false)[0]).toBe('They go to each Radarr’s recycling bin (')
    expect(whereTheyGo('movie', [both[0], { ...both[1], recycleBin: undefined, recycleDays: undefined }], false)).toEqual([
      'Radarr moves them to its recycling bin (',
      { path: '/data/recycle' },
      '), which is emptied after 7 days.',
      ' ',
      'Radarr 4K deletes them for good: it has no recycling bin set.',
    ])
    // A bin that is never emptied.
    expect(whereTheyGo('movie', [app({ recycleBin: '/bin' })], false)[2]).toBe(').')
  })

  it('warns about files the app doesn’t list only when the folder goes', () => {
    expect(extraWarning('show', [app({ extra: 3 })], false)).toBe('The folder also holds 3 files Sonarr doesn’t list. They go too.')
    expect(extraWarning('movie', [app({ extra: 1, kind: 'radarr' }), app({ kind: 'radarr' })], false)).toBe(
      'The folders also hold 1 file the Radarrs don’t list. It goes too.',
    )
    expect(extraWarning('show', [app({ extra: 3 })], true)).toBeNull()
    expect(extraWarning('season', [app({ extra: 3 })], false)).toBeNull()
    expect(extraWarning('show', [app()], false)).toBeNull()
  })

  it('names the other episodes in a file and the files that stay', () => {
    expect(otherEpisodes([app({ otherEpisodes: [2] })])).toBe('The file also holds episode 2, which goes too.')
    expect(otherEpisodes([app({ otherEpisodes: [3, 2] })])).toBe('The file also holds episodes 2 and 3, which go too.')
    expect(otherEpisodes([app()])).toBeNull()
    expect(keptLine('movie', 1)).toBe('1 other file of this movie isn’t in an app’s folder, so it stays.')
    expect(keptLine('movie', 0)).toBeNull()
  })

  it('titles each kind', () => {
    expect(deleteTitle('show', 'Harbor Lights')).toBe('Delete Harbor Lights?')
    expect(deleteTitle('season', 'Harbor Lights', { season: 2 })).toBe('Delete Season 2 of Harbor Lights?')
    expect(deleteTitle('season', 'Harbor Lights', { season: 0 })).toBe('Delete Specials of Harbor Lights?')
    expect(deleteTitle('episode', 'Harbor Lights', { episode: 'The Long Dark' })).toBe('Delete “The Long Dark”?')
  })

  it('lists the apps that failed', () => {
    expect(
      failures({
        apps: [
          { connectionId: 1, connection: 'Radarr', files: 1, size: 1 },
          { connectionId: 2, connection: 'Radarr 4K', files: 1, size: 1, error: 'the app said: not found' },
        ],
        requests: 0,
      }),
    ).toEqual(['Radarr 4K couldn’t delete: the app said: not found'])
  })

  it('says what Seerr is told, and when it couldn’t be', () => {
    expect(seerrLine('movie', 'Seerr')).toBe('Seerr is told too, so it stops showing the movie as available and it can be requested again.')
    expect(seerrLine('episode', 'Seerr')).toBe(
      'Seerr is told too: it clears its record of the show and rebuilds it from Sonarr, which loses its request history for the show.',
    )
    expect(seerrLine('show')).toBeNull()
    expect(
      failures({ apps: [{ connectionId: 1, connection: 'Sonarr', files: 2, size: 1 }], requests: 0, seerr: { connection: 'Seerr', error: 'connection refused' } }),
    ).toEqual(['Seerr couldn’t be told: connection refused. It may still show this as available; clear it there.'])
    expect(failures({ apps: [], requests: 0, seerr: { connection: 'Seerr' } })).toEqual([])
  })

  it('describes a file to replace', () => {
    expect(fileSummary({ relativePath: 'Ninety Fathoms (2025)/Ninety Fathoms (2025).mkv', videoCodec: 'hevc', audioCodec: 'eac3', height: 2160 })).toBe(
      'Ninety Fathoms (2025).mkv · HEVC · EAC3 · 2160p',
    )
  })
})

describe('recordings in a delete', () => {
  it('says the recording files go from the server as well as what the apps delete', () => {
    expect(recordingFilesLine({ files: 3, size: 3 * 1024 ** 3, apps: [{ files: 1, size: 1024 ** 3 }] })).toBe('The 2 recording files (2.0 GB) are deleted from the server too.')
    expect(recordingFilesLine({ files: 2, size: 1536 * 1024 ** 2, apps: [{ files: 1, size: 1024 ** 3 }] })).toBe('The recording file (512 MB) is deleted from the server too.')
    expect(recordingFilesLine({ files: 1, size: 10, apps: [{ files: 1, size: 10 }] })).toBe('Its recording files are deleted from the server too.')
  })
})

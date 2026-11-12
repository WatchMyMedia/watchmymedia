import { afterEach, describe, expect, it, vi } from 'vitest'
import { liveTVSetupPlan } from './liveTVSettingsRules'
import { addFailedHint, draftBody, draftsToAdd, emptyDrafts, folderCheckCount, folderLayoutWarning, readDrafts, savedScanError, setupStep, sourcesNext, validateSetupSearch, writeDrafts, type FolderDrafts } from './setupRules'

function drafts(movie = '', tv = ''): FolderDrafts {
  const d = emptyDrafts()
  d.movie.path = movie
  d.tv.path = tv
  return d
}

describe('folders step one adds', () => {
  it('adds every card with a folder whose type has no library yet, Movies first', () => {
    expect(draftsToAdd([], drafts('/movies', '/tv'))).toEqual(['movie', 'tv'])
    expect(draftsToAdd(['movie'], drafts('/movies', '/tv'))).toEqual(['tv'])
    expect(draftsToAdd(['movie', 'tv'], drafts('/movies', '/tv'))).toEqual([])
  })

  it('leaves out cards that are empty or only spaces', () => {
    expect(draftsToAdd([], drafts('', '   '))).toEqual([])
  })

  it('trims the folder and falls back to the card name when the name is cleared', () => {
    expect(draftBody('tv', { name: '  ', path: ' /srv/media/TV ' })).toEqual({ type: 'tv', name: 'TV Shows', path: '/srv/media/TV' })
    expect(draftBody('movie', { name: ' Films ', path: '/movies' })).toEqual({ type: 'movie', name: 'Films', path: '/movies' })
  })
})

describe('first-run hints', () => {
  it('warns on season folders without guessing from ordinary movie/show names', () => {
    expect(folderLayoutWarning('tv', ['Season 01', 'Season 2'])).toContain('one show')
    expect(folderLayoutWarning('movie', ['Specials', 'Season 1'])).toContain('TV show folder')
    expect(folderLayoutWarning('tv', ['Harbor Lights', 'Northbound'])).toBeUndefined()
    expect(folderLayoutWarning('movie', ['The Last Orchard (2020)'])).toBeUndefined()
  })
  it('makes a truncated count explicitly a lower bound', () => {
    expect(folderCheckCount(1234, true)).toBe('Readable · at least 1,234 video files')
    expect(folderCheckCount(1)).toBe('Readable · 1 video file')
  })
  it('keeps another failed library visible while a retry is scanning', () => {
    const failed = { lastScanError: 'Drive is disconnected.' }
    expect(savedScanError(failed, true)).toBeUndefined()
    expect(savedScanError({ ...failed, scan: {} })).toBeUndefined()
    expect(savedScanError(failed)).toBe('Drive is disconnected.')
  })
  it('does not resurrect an older saved error after a full successful retry', () => {
    const failed = { lastScanError: 'Drive disconnected.', lastScanErrorAt: '2026-11-04T12:00:00Z' }
    const at = Date.parse('2026-11-04T12:01:00Z')
    expect(savedScanError(failed, false, { finished: { partial: false, failed: 0 }, at })).toBeUndefined()
    expect(savedScanError(failed, false, { finished: { partial: true, failed: 0 }, at })).toBe(failed.lastScanError)
    expect(savedScanError(failed, false, { finished: { partial: false, failed: 1 }, at })).toBe(failed.lastScanError)
    expect(savedScanError(failed, false, { finished: { failed: 0 }, at: at - 120000 })).toBe(failed.lastScanError)
  })
})

describe('step one Next', () => {
  it('stays disabled with nothing typed, nothing added and no tuner', () => {
    expect(sourcesNext([], drafts(), false)).toMatchObject({ toAdd: [], enabled: false, label: 'Next: transcoding' })
  })

  it('adds a typed folder rather than dropping it, even before any library exists', () => {
    expect(sourcesNext([], drafts('', '/tv'), false)).toMatchObject({ toAdd: ['tv'], enabled: true, label: 'Add TV Shows and continue' })
    expect(sourcesNext([], drafts('/movies', '/tv'), false)).toMatchObject({ toAdd: ['movie', 'tv'], label: 'Add folders and continue' })
  })

  it('adds the folder left in one card when the other is already a library', () => {
    const next = sourcesNext(['movie'], drafts('', '/tv'), false)
    expect(next).toMatchObject({ toAdd: ['tv'], enabled: true, label: 'Add TV Shows and continue' })
    expect(next.plan.nextStep).toBe('tmdb')
  })

  it('is plain Next once everything typed is a library', () => {
    expect(sourcesNext(['movie', 'tv'], drafts('/movies', '/tv'), false)).toMatchObject({ toAdd: [], enabled: true, label: 'Next: metadata' })
  })

  it('takes the library path when a folder is typed on a server with a tuner', () => {
    const next = sourcesNext([], drafts('/movies', ''), true)
    expect(next).toMatchObject({ toAdd: ['movie'], enabled: true, label: 'Add Movies and continue' })
    expect(next.plan.steps.map(([s]) => s)).toEqual(['sources', 'tmdb', 'subtitles', 'transcode', 'done'])
    expect(next.plan.destination).toBe('/')
  })

  it('keeps tuner-only setup when no folder is typed', () => {
    const next = sourcesNext([], drafts(), true)
    expect(next).toMatchObject({ toAdd: [], enabled: true, label: 'Next: transcoding' })
    expect(next.plan.destination).toBe('/live-tv')
  })
})

describe('a folder that could not be added', () => {
  it('says what was added and how to carry on', () => {
    expect(addFailedHint(['movie'], 'tv')).toBe('Movies was added. Fix the TV Shows folder, or clear it to carry on without one.')
    expect(addFailedHint([], 'movie')).toBe('Fix the Movies folder, or clear it to carry on without one.')
  })
})

describe('the step in the URL', () => {
  it('accepts known steps and treats the rest as step one', () => {
    expect(validateSetupSearch({ step: 'tmdb' })).toEqual({ step: 'tmdb' })
    expect(validateSetupSearch({ step: 'sources' })).toEqual({ step: undefined })
    expect(validateSetupSearch({ step: 'nope' })).toEqual({ step: undefined })
    expect(validateSetupSearch({})).toEqual({ step: undefined })
  })

  it('shows step one until the server can carry on', () => {
    expect(setupStep('tmdb', liveTVSetupPlan(0, false))).toBe('sources')
    expect(setupStep('tmdb', liveTVSetupPlan(1, false))).toBe('tmdb')
    expect(setupStep(undefined, liveTVSetupPlan(1, false))).toBe('sources')
  })

  it('shows step one for a step the server path leaves out', () => {
    expect(setupStep('subtitles', liveTVSetupPlan(0, true))).toBe('sources')
    expect(setupStep('transcode', liveTVSetupPlan(0, true))).toBe('transcode')
  })
})

describe('drafts kept for the tab', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('round-trips through session storage and clears', () => {
    const store = new Map<string, string>()
    vi.stubGlobal('sessionStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })
    const d = drafts('/movies', '/tv')
    d.tv.name = 'Series'
    writeDrafts(d)
    expect(readDrafts()).toEqual(d)
    writeDrafts(null)
    expect(readDrafts()).toEqual(emptyDrafts())
  })

  it('starts empty when storage is blocked or holds something else', () => {
    vi.stubGlobal('sessionStorage', { getItem: () => { throw new Error('blocked') } })
    expect(readDrafts()).toEqual(emptyDrafts())
    vi.stubGlobal('sessionStorage', { getItem: () => '{"tv":{"path":7}}' })
    expect(readDrafts()).toEqual(emptyDrafts())
  })
})

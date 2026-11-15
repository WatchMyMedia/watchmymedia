import { describe, expect, it } from 'vitest'
import { isSettingsFlow, parentOf, parseSettingsPath, settingsCrumbs, settingsHref, settingsRedirect } from './settingsRoutes'

describe('parseSettingsPath', () => {
  it('opens each tab on its hub, and /settings on Playback', () => {
    expect(parseSettingsPath('/settings')).toMatchObject({ tab: 'playback', kind: 'hub' })
    expect(parseSettingsPath('/settings/plex/')).toMatchObject({ tab: 'plex', kind: 'hub', group: 'server', label: 'Plex' })
    expect(parseSettingsPath('/settings/account')).toMatchObject({ tab: 'account', group: 'you' })
  })

  it('reads every address in §4', () => {
    const cases: [string, string, string, Record<string, string>][] = [
      ['/settings/plex/connect', 'flow', 'connect', {}],
      ['/settings/plex/link/libraries', 'flow', 'link', { step: 'libraries' }],
      ['/settings/plex/import/review', 'flow', 'import', { step: 'review' }],
      ['/settings/plex/undo/42', 'flow', 'undo', { run: '42' }],
      ['/settings/plex/undo/42/confirm', 'flow', 'undo', { run: '42', step: 'confirm' }],
      ['/settings/plex/restore', 'flow', 'restore', {}],
      ['/settings/integrations/add/radarr/folders', 'flow', 'add', { app: 'radarr', step: 'folders' }],
      ['/settings/integrations/7', 'item', 'connection-page', { id: '7' }],
      ['/settings/integrations/7/folders', 'flow', 'folders', { id: '7' }],
      ['/settings/integrations/7/requests', 'flow', 'requests', { id: '7' }],
      ['/settings/integrations/7/webhook', 'flow', 'webhook', { id: '7' }],
      ['/settings/integrations/7/connection', 'flow', 'connection', { id: '7' }],
      ['/settings/libraries/add', 'flow', 'add', {}],
      ['/settings/libraries/3', 'item', 'library', { id: '3' }],
      ['/settings/libraries/3/edit', 'flow', 'edit', { id: '3' }],
      ['/settings/libraries/3/unmatched', 'flow', 'unmatched', { id: '3' }],
      ['/settings/live-tv/tuners/add', 'flow', 'add-tuner', {}],
      ['/settings/live-tv/tuners/abc', 'item', 'tuner', { id: 'abc' }],
      ['/settings/live-tv/tuners/abc/edit', 'flow', 'edit-tuner', { id: 'abc' }],
      ['/settings/live-tv/access', 'flow', 'access', {}],
      ['/settings/live-tv/guide', 'item', 'guide', {}],
      ['/settings/live-tv/guide/setup/pictures', 'flow', 'guide-setup', { step: 'pictures' }],
      ['/settings/live-tv/recordings', 'item', 'recordings', {}],
      ['/settings/live-tv/recordings/setup', 'flow', 'recordings-setup', {}],
      ['/settings/live-tv/channels', 'item', 'channels', {}],
      ['/settings/users/add', 'flow', 'add', {}],
      ['/settings/users/5', 'item', 'person', { id: '5' }],
      ['/settings/security/sign-in-protection', 'flow', 'sign-in-protection', {}],
      ['/settings/transcoding/encoder', 'flow', 'encoder', {}],
      ['/settings/transcoding/upload-limit', 'item', 'upload-limit', {}],
      ['/settings/backups/restore', 'item', 'restore', {}],
      ['/settings/general/tmdb', 'flow', 'tmdb', {}],
      ['/settings/subtitles/opensubtitles', 'flow', 'opensubtitles', {}],
      ['/settings/subtitles/fallback-languages', 'flow', 'fallback-languages', {}],
      ['/settings/account/password', 'flow', 'password', {}],
      ['/settings/account/link', 'flow', 'link', {}],
      ['/settings/account/link/ABCD12', 'flow', 'link', { step: 'ABCD12' }],
    ]
    for (const [path, kind, task, params] of cases) {
      expect(parseSettingsPath(path), path).toMatchObject({ kind, task, params })
    }
  })

  it('keeps anything below a step for the step to read', () => {
    expect(parseSettingsPath('/settings/libraries/3/unmatched/file/99')?.params).toEqual({ id: '3', step: 'file', rest: '99' })
  })

  it('decodes values', () => {
    expect(parseSettingsPath('/settings/live-tv/tuners/10AB%2023')?.params.id).toBe('10AB 23')
  })

  it('names no page for unknown addresses', () => {
    expect(parseSettingsPath('/settings/plex/nothing')).toBeNull()
    expect(parseSettingsPath('/settings/inbox/3')).toBeNull()
    expect(parseSettingsPath('/settings/nope')).toBeNull()
    expect(parseSettingsPath('/movies')).toBeNull()
    expect(parseSettingsPath('/settingsx')).toBeNull()
  })
})

describe('isSettingsFlow', () => {
  it('is true only for tasks', () => {
    expect(isSettingsFlow('/settings/plex/connect')).toBe(true)
    expect(isSettingsFlow('/settings/plex')).toBe(false)
    expect(isSettingsFlow('/settings/libraries/3')).toBe(false)
    expect(isSettingsFlow('/live-tv')).toBe(false)
  })
})

describe('settingsRedirect', () => {
  it('moves the old addresses', () => {
    expect(settingsRedirect('/admin')).toBe('/settings')
    expect(settingsRedirect('/admin/libraries')).toBe('/settings')
    expect(settingsRedirect('/settings/sign-in')).toBe('/settings/security')
  })
  it('sends an address that names no page to its tab, or to Settings', () => {
    expect(settingsRedirect('/settings/plex/nothing')).toBe('/settings/plex')
    expect(settingsRedirect('/settings/inbox/3')).toBe('/settings/inbox')
    expect(settingsRedirect('/settings/nope/1')).toBe('/settings')
  })
  it('leaves pages as they are', () => {
    expect(settingsRedirect('/settings/plex/link/people')).toBeNull()
    expect(settingsRedirect('/settings/plex')).toBeNull()
    expect(settingsRedirect('/movies')).toBeNull()
  })
})

describe('settingsHref and crumbs', () => {
  it('builds encoded addresses and skips empty parts', () => {
    expect(settingsHref('plex')).toBe('/settings/plex')
    expect(settingsHref('live-tv', 'tuners', '10AB 23', undefined, 'edit')).toBe('/settings/live-tv/tuners/10AB%2023/edit')
    expect(settingsHref('libraries', 3, false, '')).toBe('/settings/libraries/3')
  })
  it('names the group, the tab and the parts; Exit goes one level up', () => {
    const crumbs = settingsCrumbs('plex', { label: 'Link people and libraries' })
    expect(crumbs.map((c) => c.label)).toEqual(['Settings', 'Server', 'Plex', 'Link people and libraries'])
    expect(crumbs[1].wideOnly).toBe(true)
    expect(parentOf(crumbs)).toEqual({ label: 'Plex', href: '/settings/plex' })
    expect(settingsCrumbs('account')[1].label).toBe('You')
  })
})

import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { playbackErrorCode, playbackErrorWhen, playbackErrorWords } from './aboutWords'
import { actionLabel, applyAction, pickScreen, rowActions, rowLine, rowNote, rowScreens } from './homeRows'
import { actOn, addable, addTo, languageActions } from './languages'
import { begin, fail, settle } from './saves'
import { groupOfFocus, openingGroup, paneRowId, stepInColumn, visibleGroups } from './settingsGroups'
import { accountLine, deviceLine, followsLine, inboxLine, languagesLine, playbackLine, qualityDescription, requestsLine, rowsLine, SKIP_ROWS, skipExplanation, startSubtitlesLine } from './settingsWords'

type Entry = components['schemas']['RowLayoutEntry']
const entry = (id: string, o: Partial<Entry> = {}): Entry => ({ id, title: id, style: 'poster', kind: 'builtin', hidden: false, shown: true, ...o })

describe('Settings’ groups', () => {
  it('lists Your requests where requests can be made, and the Server group for admins', () => {
    expect(visibleGroups({ isAdmin: false, canRequest: false })).toEqual(['playback', 'languages', 'homeRows', 'notifications', 'account', 'about'])
    expect(visibleGroups({ isAdmin: true, canRequest: true })).toEqual(['playback', 'languages', 'homeRows', 'notifications', 'account', 'requests', 'about', 'inbox', 'server'])
  })

  it('opens on the route’s group, else the one focus was in, else Playback', () => {
    expect(openingGroup('homeRows', 'pane/account/password')).toBe('homeRows')
    expect(openingGroup(undefined, paneRowId('account', 'password'))).toBe('account')
    expect(openingGroup(undefined, 'group/about')).toBe('about')
    expect(groupOfFocus('pane/nonsense/x')).toBeUndefined()
    expect(openingGroup(undefined, 'rail/home')).toBe('playback')
  })

  it('keeps ▲ ▼ in their column, never wrapping', () => {
    expect(stepInColumn(3, 0, -1)).toBe(0)
    expect(stepInColumn(3, 2, 1)).toBe(2)
    expect(stepInColumn(3, 1, 1)).toBe(2)
    expect(stepInColumn(3, -1, 1)).toBe(0)
  })
})

describe('Settings’ words', () => {
  it('says what is chosen in each group', () => {
    expect(playbackLine('Automatic', true)).toBe('Automatic · autoplay on')
    expect(languagesLine(['es', 'en'], 'foreign')).toMatch(/^Spanish, English · for foreign audio$/)
    expect(languagesLine([], 'off')).toBe('File default · off')
    expect(rowsLine(8, 2)).toBe('8 rows · 2 hidden')
    expect(rowsLine(1, 0)).toBe('1 row')
    expect(followsLine(3)).toBe('3 shows followed')
    expect(followsLine(0)).toBe('None')
    expect(accountLine('Sam', 4)).toBe('Sam · 4 devices signed in')
    expect(accountLine('Sam', undefined)).toBe('Sam')
    expect(inboxLine(3)).toBe('3 to look at')
    expect(inboxLine(0)).toBe('Nothing to look at')
  })

  it('counts requests waiting first, then downloading', () => {
    expect(requestsLine([{ status: 'pending' }, { status: 'pending' }, { status: 'approved', download: { state: 'downloading', progress: .5 } }])).toBe('2 waiting')
    expect(requestsLine([{ status: 'approved', download: { state: 'downloading', progress: .5 } }])).toBe('1 downloading')
    expect(requestsLine([{ status: 'available' }])).toBe('1 request')
    expect(requestsLine([])).toBe('None')
  })

  it('words the choices as the boards do', () => {
    expect(qualityDescription('auto')).toBe('Original first, lower when it can’t keep up')
    expect(qualityDescription('720')).toBe('Transcode to fit')
    expect(SKIP_ROWS.map((r) => r.label)).toEqual(['Skip intro', 'Skip recap', 'Skip credits', 'Skip previews', 'Skip ads'])
    expect(skipExplanation('none', 'intro')).toBe('No button. The intro plays.')
    expect(skipExplanation('auto-delay', 'intro')).toBe('Shows the button filling up, then skips.')
    expect(startSubtitlesLine('foreign', ['es', 'en'])).toBe('Full subtitles when the audio isn’t Spanish or English')
  })

  it('names a device by its model and when it was seen', () => {
    expect(deviceLine({ model: 'iPad', kind: 'device', lastSeenAt: new Date(Date.now() - 2 * 3_600_000).toISOString(), current: false })).toBe('iPad · 2 h ago')
    expect(deviceLine({ product: 'WatchMyMedia for Samsung TV', kind: 'device', lastSeenAt: new Date().toISOString(), current: true })).toBe('WatchMyMedia for Samsung TV · now')
  })
})

describe('a setting saved at once', () => {
  it('is Saving… until the server answers, then gone, or failed with what it still is', () => {
    const saving = begin({}, 'autoplay')
    expect(saving.autoplay).toEqual({ state: 'saving' })
    expect(settle(saving, 'autoplay')).toEqual({})
    expect(fail(saving, 'autoplay', 'On', { autoplayNext: false }).autoplay).toEqual({ state: 'failed', still: 'On', retry: { autoplayNext: false } })
  })
})

describe('Home rows on the TV', () => {
  const libraries = [{ id: 1, type: 'movie' as const, name: 'Movies' }, { id: 2, type: 'tv' as const, name: 'TV Shows' }]

  it('arranges Home and each library, and opens on the one a library’s Edit rows asked for', () => {
    const screens = rowScreens(libraries)
    expect(screens.map((s) => s.id)).toEqual(['home', 'library-1', 'library-2'])
    expect(pickScreen(screens, undefined, 'tv').name).toBe('TV Shows')
    expect(pickScreen(screens, 'library-1', 'tv').name).toBe('Movies')
    expect(pickScreen(screens, undefined, undefined).id).toBe('home')
  })

  it('words each row’s line, and why one isn’t shown, in our words', () => {
    const lists = [{ id: 11, entries: 50, inLibrary: 18, source: { kind: 'tmdb' } }]
    expect(rowLine(entry('continue'), lists)).toBe('Built in')
    expect(rowLine(entry('live', { style: 'live' }), lists)).toBe('Built in · On now, or Watch a channel')
    expect(rowLine(entry('list:11', { kind: 'list', listId: 11, owner: 'everyone' }), lists)).toBe('For everyone · 18 of 50 · From TMDB')
    expect(rowLine(entry('list:9', { kind: 'list', listId: 9, owner: 'me' }), lists)).toBe('Your list')
    expect(rowLine(entry('list:501', { kind: 'list', listId: 501, owner: 'me' }), [{ id: 501, entries: 12, inLibrary: 9, source: { kind: 'watchlist' } }])).toBe('Watchlist · 12 titles · 9 in your library')
    const rows = [entry('a'), entry('list:13', { kind: 'list', shown: false, note: 'server words' }), entry('b', { hidden: true, shown: false })]
    expect(rowNote(rows, 1)).toBe('Not shown: none of it is in your library yet')
    expect(rowNote(rows, 2)).toBeUndefined()
    expect(rowNote(rows, 0)).toBeUndefined()
  })

  it('offers Move up and down where a row can go, and acts at once', () => {
    expect(rowActions(0, 3, false)).toEqual(['down', 'hide', 'cancel'])
    expect(rowActions(2, 3, true)).toEqual(['up', 'show', 'cancel'])
    expect(actionLabel('hide', 'Home')).toBe('Hide from Home')
    const rows = [entry('a'), entry('b'), entry('c')]
    expect(applyAction(rows, 'b', 'up')?.map((r) => r.id)).toEqual(['b', 'a', 'c'])
    expect(applyAction(rows, 'c', 'down')).toBeUndefined()
    expect(applyAction(rows, 'a', 'hide')?.[0].hidden).toBe(true)
    expect(applyAction(rows, 'a', 'cancel')).toBeUndefined()
  })
})

describe('the language lists', () => {
  const draft = { audio: ['es', 'en'], subtitles: [], choice: 'foreign' as const, automaticDownload: false }

  it('moves, removes and adds a language, and leaves what changes nothing alone', () => {
    expect(languageActions(0, 2)).toEqual(['down', 'remove', 'cancel'])
    expect(actOn(draft, 'audio', 'en', 'up')?.audio).toEqual(['en', 'es'])
    expect(actOn(draft, 'audio', 'es', 'remove')?.audio).toEqual(['en'])
    expect(actOn(draft, 'audio', 'es', 'up')).toBeUndefined()
    expect(addTo(draft, 'subtitles', 'fr')?.subtitles).toEqual(['fr'])
    expect(addTo(draft, 'audio', 'es')).toBeUndefined()
    expect(addable(['en']).some((l) => l.tag === 'en')).toBe(false)
  })
})

describe('About’s playback errors', () => {
  it('gives each a code and our own words, never the player’s', () => {
    expect(playbackErrorCode({ kind: 'network', status: 503 })).toBe('PB-503')
    expect(playbackErrorCode({ kind: 'format' })).toBe('PB-DEC')
    expect(playbackErrorWords({ media: 'live', kind: 'network', status: 503, channel: '7.1' })).toBe('The server was too busy to start Live TV 7.1')
    expect(playbackErrorWords({ media: 'movie', kind: 'format' })).toBe('This TV couldn’t decode a movie’s video')
    expect(playbackErrorWords({ media: 'episode', kind: 'network' })).toBe('An episode stopped loading')
  })

  it('says when as Today, Yesterday or the weekday', () => {
    const now = new Date(2026, 10, 19, 21, 0).getTime()
    expect(playbackErrorWhen(new Date(2026, 10, 19, 20, 2).getTime(), now, 'en-US')).toBe('Today 8:02 PM')
    expect(playbackErrorWhen(new Date(2026, 10, 18, 21, 40).getTime(), now, 'en-US')).toBe('Yesterday 9:40 PM')
    expect(playbackErrorWhen(new Date(2026, 10, 16, 19, 15).getTime(), now, 'en-US')).toBe('Mon 7:15 PM')
  })
})

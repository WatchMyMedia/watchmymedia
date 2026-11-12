import { describe, expect, it } from 'vitest'
import type { LiveChannel } from '../api/liveTV'
import type { LiveInfo, StateMessage } from '../cast/protocol'
import {
  LIVE_CAST_QUALITIES, behindLive, behindText, castServerUrls, liveBar, liveCastMode, liveLoadData, livePosition, liveRemoteAction, liveRemoteView,
  receiverPlaysLive, remoteCard, remoteChannel, serverCastsLive, settlePending,
} from './liveCast'

const info = (over: Partial<LiveInfo> = {}): LiveInfo => ({ channelId: 'c1', number: '7.1', name: 'LKSD-HD', quality: '1080', paused: false, pauseLeftSeconds: null, reason: null, ...over })
const state = (over: Partial<StateMessage> = {}): StateMessage => ({ type: 'state', v: 1, features: ['live'], phase: 'playing', live: info(), ...over })
const channel = (id: string, over: Partial<LiveChannel> = {}): LiveChannel => ({ id, sourceId: 's', number: id, name: `Channel ${id}`, enabled: true, available: true, ...over })

describe('the gate', () => {
  it('needs the server at API level 14 with capabilities.cast', () => {
    expect(serverCastsLive(14, { cast: true })).toBe(true)
    expect(serverCastsLive(15, { cast: true })).toBe(true)
    expect(serverCastsLive(13, { cast: true })).toBe(false)
    expect(serverCastsLive(14, { cast: false })).toBe(false)
    expect(serverCastsLive(14, {})).toBe(false)
    expect(serverCastsLive(undefined, undefined)).toBe(false)
  })
  it('needs a receiver that lists live, and waits for its first state', () => {
    expect(receiverPlaysLive(undefined)).toBeUndefined()
    expect(receiverPlaysLive(state())).toBe(true)
    expect(receiverPlaysLive(state({ features: undefined }))).toBe(false)
    expect(receiverPlaysLive(state({ features: [] }))).toBe(false)
  })
  it('plays here unless connected to a TV that plays Live TV on a server that casts it', () => {
    expect(liveCastMode({ connected: false, server: true, receiver: state() })).toBe('local')
    expect(liveCastMode({ connected: true, server: false, receiver: state() })).toBe('local')
    expect(liveCastMode({ connected: true, server: true })).toBe('waiting')
    expect(liveCastMode({ connected: true, server: true, waitedOut: true })).toBe('notLive')
    expect(liveCastMode({ connected: true, server: true, receiver: state({ features: [] }) })).toBe('notLive')
    expect(liveCastMode({ connected: true, server: true, receiver: state({ phase: 'idle', live: undefined }) })).toBe('remote')
  })
})

describe('the LOAD', () => {
  it('names the channel and the quality instead of a file', () => {
    const data = liveLoadData({ serverId: 'srv', urls: ['https://a'], token: 'c1.t', tokenExpiresAt: '2026-11-03T12:00:00Z', channelId: 'ch4', quality: '720', deviceName: 'Living Room TV', senderName: 'Chrome on macOS' })
    expect(data).toEqual({ v: 1, server: { id: 'srv', urls: ['https://a'] }, token: 'c1.t', tokenExpiresAt: '2026-11-03T12:00:00Z', live: { channelId: 'ch4', quality: '720' }, device: { name: 'Living Room TV' }, sender: { name: 'Chrome on macOS' } })
    expect(data.fileId).toBeUndefined()
  })
  it('sends Automatic when chosen and 1080 for unknown saved values', () => {
    expect(liveLoadData({ serverId: 's', urls: [], token: 't', channelId: 'c', quality: 'auto', senderName: 'x' }).live?.quality).toBe('auto')
    for (const q of [undefined, null, 'original', '2160']) expect(liveLoadData({ serverId: 's', urls: [], token: 't', channelId: 'c', quality: q, senderName: 'x' }).live?.quality).toBe('1080')
  })
  it('adds this page’s address unless a TV cannot reach it', () => {
    expect(castServerUrls(['https://wmm.example'], 'https://home.example', 'home.example')).toEqual(['https://wmm.example', 'https://home.example'])
    expect(castServerUrls(['https://home.example'], 'https://home.example', 'home.example')).toEqual(['https://home.example'])
    for (const host of ['localhost', '127.0.0.1', 'box.ts.net', '100.100.1.2', 'app.localhost']) expect(castServerUrls(['https://a'], `http://${host}`, host)).toEqual(['https://a'])
    expect(castServerUrls([], 'http://192.168.1.20:27587', '192.168.1.20')).toEqual(['http://192.168.1.20:27587'])
  })
})

describe('settlePending', () => {
  it('clears once the TV is on the channel asked for', () => {
    expect(settlePending('c2', state(), state({ phase: 'loading', live: info({ channelId: 'c2' }) }))).toBeUndefined()
    expect(settlePending('c2', state(), state({ live: info({ channelId: 'c1' }) }))).toBe('c2')
  })
  it('clears when the TV moves somewhere else, or newly fails', () => {
    expect(settlePending('c2', state(), state({ phase: 'loading', live: info({ channelId: 'c3' }) }))).toBeUndefined()
    expect(settlePending('c2', state({ phase: 'loading' }), state({ phase: 'error', live: undefined }))).toBeUndefined()
  })
  it('keeps waiting through the failure the TV already showed', () => {
    const failed = state({ phase: 'error', live: info({ channelId: 'c2', reason: 'tuners_busy' }) })
    expect(settlePending('c2', failed, failed)).toBe('c2')
    expect(settlePending('c2', state({ phase: 'idle', live: undefined }), state({ phase: 'idle', live: undefined }))).toBe('c2')
    expect(settlePending(undefined, state(), state())).toBeUndefined()
  })
})

describe('liveRemoteAction', () => {
  const on = (channelId: string, phase: StateMessage['phase'] = 'playing') => state({ phase, live: info({ channelId }) })
  it('casts the channel the viewer opened (Watch in the guide)', () => {
    expect(liveRemoteAction({ route: 'c1', receiver: state({ phase: 'idle', live: undefined }) })).toEqual({ kind: 'cast' })
    expect(liveRemoteAction({ route: 'c2', receiver: on('c1'), target: 'c1' })).toEqual({ kind: 'cast' })
    // A file on the TV is replaced, as opening another file does.
    expect(liveRemoteAction({ route: 'c1', receiver: state({ live: undefined, fileId: 4 }) })).toEqual({ kind: 'cast' })
  })
  it('waits while the channel is on its way, and adopts it once the TV is on it', () => {
    expect(liveRemoteAction({ route: 'c2', receiver: on('c1'), pending: 'c2', target: 'c2' })).toEqual({ kind: 'none' })
    expect(liveRemoteAction({ route: 'c1', receiver: on('c1') })).toEqual({ kind: 'adopt' })
    expect(liveRemoteAction({ route: 'c1', receiver: on('c1'), target: 'c1' })).toEqual({ kind: 'none' })
    expect(liveRemoteAction({ route: 'c1', receiver: on('c1', 'loading'), target: 'c1' })).toEqual({ kind: 'none' })
  })
  it('follows the TV when it changes channel from the one this page drives', () => {
    expect(liveRemoteAction({ route: 'c1', receiver: on('c2', 'loading'), target: 'c1' })).toEqual({ kind: 'follow', channelId: 'c2' })
    expect(liveRemoteAction({ route: 'c1', receiver: on('c2', 'error'), target: 'c1' })).toEqual({ kind: 'follow', channelId: 'c2' })
    // Not while its own ask for another channel is out.
    expect(liveRemoteAction({ route: 'c1', receiver: on('c2'), target: 'c1', pending: 'c3' })).toEqual({ kind: 'none' })
  })
  it('leaves an ended play alone: the remote says so instead of casting again', () => {
    expect(liveRemoteAction({ route: 'c1', receiver: state({ phase: 'idle', live: undefined }), target: 'c1' })).toEqual({ kind: 'none' })
  })
})

describe('liveRemoteView', () => {
  const base = { connected: true, route: 'c1', target: 'c1' }
  it('shows casting ended once disconnected', () => {
    expect(liveRemoteView({ ...base, connected: false, receiver: state() })).toEqual({ kind: 'ended', channelId: 'c1' })
  })
  it('tunes while an ask is out or the TV is loading, and before the first ask', () => {
    expect(liveRemoteView({ ...base, receiver: state(), pending: 'c2' })).toEqual({ kind: 'tuning', channelId: 'c2' })
    // Try again after a refusal: tuning until the TV answers.
    expect(liveRemoteView({ ...base, receiver: state({ phase: 'error', live: info({ reason: 'tuners_busy' }) }), pending: 'c1' })).toEqual({ kind: 'tuning', channelId: 'c1' })
    expect(liveRemoteView({ ...base, receiver: state({ phase: 'loading' }) })).toEqual({ kind: 'tuning', channelId: 'c1' })
    expect(liveRemoteView({ ...base, target: undefined, receiver: state({ phase: 'idle', live: undefined }) })).toEqual({ kind: 'tuning', channelId: 'c1' })
  })
  it('plays, paused with minutes until the tuner goes back', () => {
    expect(liveRemoteView({ ...base, receiver: state() })).toEqual({ kind: 'playing', channelId: 'c1', paused: false, pauseLeftMinutes: undefined })
    expect(liveRemoteView({ ...base, receiver: state({ live: info({ paused: true, pauseLeftSeconds: 201 }) }) })).toEqual({ kind: 'playing', channelId: 'c1', paused: true, pauseLeftMinutes: 4 })
    expect(liveRemoteView({ ...base, receiver: state({ live: info({ paused: true, pauseLeftSeconds: 5 }) }) })).toMatchObject({ pauseLeftMinutes: 1 })
  })
  it('asks still watching, says an admin stopped it, and words failures', () => {
    expect(liveRemoteView({ ...base, receiver: state({ stillWatching: true }) }).kind).toBe('stillWatching')
    expect(liveRemoteView({ ...base, receiver: state({ phase: 'stopped', live: info({ reason: 'stopped_by_admin' }) }) }).kind).toBe('stopped')
    expect(liveRemoteView({ ...base, receiver: state({ phase: 'error', live: info({ reason: 'tuners_busy' }) }) })).toEqual({ kind: 'failed', channelId: 'c1', reason: 'tuners_busy', message: undefined })
    // An older receiver drops the channel on a refused start; its words still show.
    expect(liveRemoteView({ ...base, receiver: state({ phase: 'error', live: undefined, error: 'All tuners are in use.' }) })).toEqual({ kind: 'failed', channelId: 'c1', reason: undefined, message: 'All tuners are in use.' })
    expect(liveRemoteView({ ...base, receiver: state({ phase: 'idle', live: undefined }), error: 'The TV refused to play' })).toMatchObject({ kind: 'failed', message: 'The TV refused to play' })
  })
  it('shows ended when the TV stopped this page’s channel', () => {
    expect(liveRemoteView({ ...base, receiver: state({ phase: 'idle', live: undefined }) })).toEqual({ kind: 'ended', channelId: 'c1' })
  })
})

describe('remoteCard', () => {
  const words = { number: '7.1', name: 'LKSD-HD', device: 'Living Room TV' }
  it('words the board’s cards', () => {
    expect(remoteCard({ kind: 'failed', channelId: 'c1', reason: 'tuners_busy' }, words)).toMatchObject({ title: 'All tuners are in use', body: 'Living Room TV can’t start 7.1 until a tuner is free.', retry: true, guide: true })
    expect(remoteCard({ kind: 'failed', channelId: 'c1', reason: 'tuners_busy' }, { ...words, recording: true })?.body).toBe('Living Room TV can’t start 7.1 until a tuner is free. A scheduled recording can release a tuner someone is watching on.')
    expect(remoteCard({ kind: 'failed', channelId: 'c1', reason: 'recording' }, { ...words, recording: true })).toMatchObject({ title: 'Stopped for a recording', body: 'This tuner is now recording for your household.' })
    expect(remoteCard({ kind: 'stillWatching', channelId: 'c1' }, words)).toMatchObject({ title: 'Still watching 7.1 on Living Room TV?', keep: true, stop: true })
    expect(remoteCard({ kind: 'stopped', channelId: 'c1' }, { ...words, stopMessage: 'Lights out at ten.' })).toMatchObject({ title: 'Stopped from Activity', message: 'Lights out at ten.', guideFirst: true, stop: true })
    expect(remoteCard({ kind: 'stopped', channelId: 'c1' }, { ...words, stopMessage: '' })?.message).toBeUndefined()
    expect(remoteCard({ kind: 'ended', channelId: 'c1' }, words)).toMatchObject({ title: 'Casting ended', body: '7.1 LKSD-HD stopped on Living Room TV, and its tuner is free.', watchHere: true, guide: true, castIcon: true })
  })
  it('words other failures as the player does, or the TV’s own words', () => {
    expect(remoteCard({ kind: 'failed', channelId: 'c1', reason: 'no_signal' }, words)).toMatchObject({ title: 'No signal on this channel', retry: true })
    expect(remoteCard({ kind: 'failed', channelId: 'c1', reason: 'not_allowed' }, words)).toMatchObject({ title: 'Live TV isn’t available to you', guide: true })
    expect(remoteCard({ kind: 'failed', channelId: 'c1', message: 'This server can’t cast Live TV yet.' }, words)).toMatchObject({ title: 'Living Room TV couldn’t play 7.1', body: 'This server can’t cast Live TV yet.' })
  })
  it('has no card while tuning or playing', () => {
    expect(remoteCard({ kind: 'tuning', channelId: 'c1' }, words)).toBeUndefined()
    expect(remoteCard({ kind: 'playing', channelId: 'c1', paused: false }, words)).toBeUndefined()
  })
})

describe('remoteChannel', () => {
  it('prefers this page’s channel list, for the logo and episode numbers', () => {
    const listed = channel('c1', { logoUrl: '/api/live-tv/art/1' })
    expect(remoteChannel('c1', info(), [listed])).toBe(listed)
  })
  it('falls back to what the TV says', () => {
    const c = remoteChannel('c1', info({ now: { title: 'Valley Bake-Off', episodeTitle: 'Pies', start: '2026-11-03T01:00:00Z', end: '2026-11-03T02:00:00Z' } }), [])
    expect(c).toMatchObject({ id: 'c1', number: '7.1', name: 'LKSD-HD', available: true })
    expect(c.now).toMatchObject({ title: 'Valley Bake-Off', episodeTitle: 'Pies' })
    expect(remoteChannel('c2', info(), undefined)).toMatchObject({ id: 'c2', number: '', name: '' })
  })
})

describe('the live window', () => {
  it('measures and words how far behind live the TV is', () => {
    expect(behindLive({ start: 0, end: 60 }, 18)).toBe(42)
    expect(behindLive({ start: 0, end: 60 }, 61)).toBe(0)
    expect(behindLive(undefined, 10)).toBe(0)
    expect(behindText(42)).toBe('0:42 behind live')
    expect(behindText(65)).toBe('1:05 behind live')
  })
  it('places the bar: full at live, never vanishing', () => {
    expect(livePosition(0, 60)).toBe(100)
    expect(livePosition(42, 60)).toBe(30)
    expect(livePosition(600, 60)).toBe(8)
  })
  it('offers Automatic and the four fixed Live TV qualities with the player’s labels', () => {
    expect(LIVE_CAST_QUALITIES.map((q) => q.label)).toEqual(['Automatic', '1080p · 8 Mbps', '720p · 4 Mbps', '480p · 2 Mbps', '360p · 1 Mbps'])
  })
})

describe('liveBar', () => {
  const now = Date.parse('2026-11-03T01:12:00Z')
  const c = channel('c1', { number: '7.1', name: 'LKSD-HD', now: { id: 'p', title: 'Valley Bake-Off', season: 6, episode: 10, episodeTitle: 'Pies', start: '2026-11-03T01:00:00Z', end: '2026-11-03T02:00:00Z' } })
  const device = 'Living Room TV'
  it('shows the channel, what is on and time left', () => {
    expect(liveBar({ receiver: state(), channel: c, device, now })).toEqual({ title: '7.1 LKSD-HD · Valley Bake-Off', sub: 'S6 E10 · Pies · 48 min left · on Living Room TV', pct: 20, badge: true, ask: false, controls: true })
  })
  it('says tuning, paused and still watching', () => {
    expect(liveBar({ receiver: state({ phase: 'loading' }), channel: c, device, now })).toMatchObject({ sub: 'Tuning on Living Room TV…', badge: false, controls: false })
    expect(liveBar({ receiver: state({ live: info({ paused: true, pauseLeftSeconds: 230 }) }), channel: c, device, now })).toMatchObject({ sub: 'Paused · the tuner goes back in 4 min · on Living Room TV', controls: true })
    expect(liveBar({ receiver: state({ stillWatching: true }), channel: c, device, now })).toMatchObject({ sub: 'Living Room TV asks: still watching?', ask: true, controls: false })
  })
  it('works without listings, and hides once nothing plays', () => {
    expect(liveBar({ receiver: state(), channel: channel('c1', { number: '4.3', name: 'Coast Shop' }), device, now })).toMatchObject({ title: '4.3 Coast Shop', sub: 'on Living Room TV', pct: 0 })
    expect(liveBar({ receiver: state({ phase: 'error' }), channel: c, device, now })).toBeUndefined()
    expect(liveBar({ receiver: state({ live: undefined }), channel: c, device, now })).toBeUndefined()
  })
})

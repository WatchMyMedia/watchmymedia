import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LiveChannel } from '../api/liveTV'
import { filterLiveChannels, guideTimes, LIVE_QUALITIES, liveEdge, liveFailure, liveQuality, liveViewingPrompt, nextLiveChannel, pauseExpired } from './liveTV'

function channel(id: string, changes: Partial<LiveChannel> = {}): LiveChannel {
  return { id, sourceId: 'source', number: id, name: `Channel ${id}`, enabled: true, available: true, ...changes }
}

afterEach(() => { vi.unstubAllEnvs() })

describe('Live TV quality', () => {
  it('offers Automatic and the fixed quality ceilings', () => {
    expect(LIVE_QUALITIES).toEqual(['auto', '1080', '720', '480', '360'])
    for (const quality of LIVE_QUALITIES) expect(liveQuality(quality)).toBe(quality)
  })

  it.each([null, 'original', '2160', '', '720p', 'unknown'])('maps the saved preference %s to the fixed 1080p default', (quality) => {
    expect(liveQuality(quality)).toBe('1080')
  })
})

describe('Live TV channel navigation', () => {
  const channels = [channel('2.1'), channel('2.2', { enabled: false }), channel('4.1'), channel('5.1', { available: false }), channel('10.1')]

  it('preserves the server order, skips hidden/unavailable channels and wraps in both directions', () => {
    expect(nextLiveChannel(channels, '2.1', 1)?.id).toBe('4.1')
    expect(nextLiveChannel(channels, '10.1', 1)?.id).toBe('2.1')
    expect(nextLiveChannel(channels, '2.1', -1)?.id).toBe('10.1')
    expect(nextLiveChannel(channels, '10.1', -1)?.id).toBe('4.1')
  })

  it('starts at the first or last available channel when the current channel disappeared', () => {
    expect(nextLiveChannel(channels, 'missing', 1)?.id).toBe('2.1')
    expect(nextLiveChannel(channels, 'missing', -1)?.id).toBe('10.1')
  })

  it('finds the next playable neighbour when the current channel became unavailable or hidden', () => {
    expect(nextLiveChannel(channels, '5.1', 1)?.id).toBe('10.1')
    expect(nextLiveChannel(channels, '5.1', -1)?.id).toBe('4.1')
    expect(nextLiveChannel(channels, '2.2', -1)?.id).toBe('2.1')
  })

  it('handles an empty lineup and a single playable channel', () => {
    expect(nextLiveChannel([], 'missing', 1)).toBeUndefined()
    expect(nextLiveChannel([channel('one', { enabled: false })], 'one', -1)).toBeUndefined()
    expect(nextLiveChannel([channel('one')], 'one', 1)?.id).toBe('one')
    expect(nextLiveChannel([channel('one')], 'one', -1)?.id).toBe('one')
  })

  it('searches names and string channel numbers, trims input and leaves availability visible', () => {
    const list = [channel('2.1', { name: 'Local News' }), channel('2.10', { name: 'Nature', available: false }), channel('10.1', { name: 'Arts' })]
    expect(filterLiveChannels(list, ' NEWS ')).toEqual([list[0]])
    expect(filterLiveChannels(list, '2.1')).toEqual([list[0], list[1]])
    expect(filterLiveChannels(list, 'nature')).toEqual([list[1]])
    expect(filterLiveChannels(list, '   ')).toEqual(list)
    expect(filterLiveChannels(list, 'missing')).toEqual([])
  })
})

describe('Live TV inactivity and live edge', () => {
  it('asks after four hours and releases after one further minute', () => {
    const input = 1_000
    const fourHours = 4 * 60 * 60_000
    expect(liveViewingPrompt(input, input - 1)).toBe('none')
    expect(liveViewingPrompt(input, input + fourHours - 1)).toBe('none')
    expect(liveViewingPrompt(input, input + fourHours)).toBe('ask')
    expect(liveViewingPrompt(input, input + fourHours + 59_999)).toBe('ask')
    expect(liveViewingPrompt(input, input + fourHours + 60_000)).toBe('expired')
    expect(liveViewingPrompt(input + fourHours, input + fourHours + 60_000)).toBe('none')
  })

  it('returns to a playable position three seconds behind live without seeking before the window', () => {
    expect(liveEdge(120, 180)).toBe(177)
    expect(liveEdge(120, 122)).toBe(120)
    expect(liveEdge(120, 120)).toBe(120)
  })

  it('detects a pause rolling out of the window, preserving boundary positions still in it', () => {
    expect(pauseExpired(119.9, 120, 180)).toBe(true)
    expect(pauseExpired(120, 120, 180)).toBe(false)
    expect(pauseExpired(150, 120, 180)).toBe(false)
    expect(pauseExpired(180, 120, 180)).toBe(false)
    expect(pauseExpired(180.1, 120, 180)).toBe(true)
    expect(pauseExpired(150, 151, 211)).toBe(true)
  })
})

describe('Live TV time labels and failures', () => {
  it('starts at the local half hour and advances by real half hours across date boundaries', () => {
    const now = new Date(2026, 10, 1, 23, 47, 59, 500)
    const times = guideTimes(now.getTime())
    expect(times).toHaveLength(4)
    expect(times[0]).toEqual(new Date(2026, 10, 1, 23, 30))
    expect(times.map((time) => time.getTime() - times[0].getTime())).toEqual([0, 1_800_000, 3_600_000, 5_400_000])
    expect(guideTimes(new Date(2026, 10, 1, 10, 29).getTime())[0].getMinutes()).toBe(0)
  })

  it('gives distinct capacity, signal and pause recovery messages', () => {
    expect(liveFailure('tuners_busy').title).toBe('All tuners are in use')
    expect(liveFailure('server_busy').title).toBe('The server is busy')
    expect(liveFailure('no_signal').title).toBe('No signal on this channel')
    expect(liveFailure('pause_expired').detail).toContain('needs a free tuner')
    expect(liveFailure('inactive').detail).toContain('without input')
    expect(liveFailure('access_revoked')).toEqual(liveFailure('channel_disabled'))
    expect(liveFailure('future_reason').detail).toBe('future_reason')
    expect(liveFailure().detail).toBe('Try again, or choose another channel.')
  })

  it('offers the recording from the start only where it can be watched', () => {
    expect(liveFailure('recording').detail).toBe('This tuner is now recording for your household. You can watch that recording from the start.')
    expect(liveFailure('recording', { watchRecording: false }).detail).toBe('This tuner is now recording for your household.')
  })

  it('keeps the repeated fall-back hour and skips the spring-forward gap', () => {
    vi.stubEnv('TZ', 'America/New_York')
    expect(guideTimes(Date.parse('2026-11-01T01:45:00-05:00'))[0].toISOString()).toBe('2026-11-01T06:30:00.000Z')
    const spring = guideTimes(Date.parse('2026-03-08T01:45:00-05:00'))
    expect(spring.map((time) => time.getHours())).toEqual([1, 3, 3, 4])
  })
})

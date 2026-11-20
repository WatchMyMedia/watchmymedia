import { describe, expect, it } from 'vitest'
import type { Action } from '../../app/keys'
import { areaOf, backAction, overlayOf, RELEASE_AFTER_MS, routeKey, SeekHold, seekKey, type KeyContext, type Overlay } from './keys'

const base = { adminStop: undefined, phase: 'playing' as const, askStillWatching: false, next: undefined }
const card = { episode: { fileId: 2, episodeId: 20, showId: 3, showTitle: 'Harbor Lights', seasonNumber: 3, episodeNumber: 8, title: 'Ebb' }, heading: 'S03 E08 · Ebb' }

describe('the overlay over the video', () => {
  it('puts an administrator’s stop or an error first, then Still watching, then a panel, then the Next card', () => {
    expect(overlayOf(base, false)).toBe('none')
    expect(overlayOf({ ...base, next: card }, false)).toBe('next')
    expect(overlayOf({ ...base, next: card }, true)).toBe('panel')
    expect(overlayOf({ ...base, next: card, askStillWatching: true }, true)).toBe('still')
    expect(overlayOf({ ...base, askStillWatching: true, phase: 'error' }, true)).toBe('message')
    expect(overlayOf({ ...base, adminStop: { message: 'Bedtime' } }, true)).toBe('message')
  })
})

describe('the remote', () => {
  const at = (overlay: Overlay, o: Partial<KeyContext> = {}): KeyContext => ({ overlay, controls: true, area: 'scrubber', hasNext: false, hasPrevious: false, ...o })
  const kind = (action: Action, c: KeyContext) => routeKey(action, c).kind

  it('seeks with ◀ ▶ and ⏪ ⏩, except that ◀ ▶ move along the button row', () => {
    expect(routeKey('left', at('none'))).toEqual({ kind: 'seek', direction: -1 })
    expect(routeKey('right', at('none', { controls: false, area: 'root' }))).toEqual({ kind: 'seek', direction: 1 })
    expect(routeKey('right', at('none', { area: 'skip' }))).toEqual({ kind: 'seek', direction: 1 })
    expect(kind('left', at('none', { area: 'buttons' }))).toBe('pass')
    expect(routeKey('rewind', at('none', { area: 'buttons' }))).toEqual({ kind: 'seek', direction: -1 })
    expect(routeKey('fastForward', at('next'))).toEqual({ kind: 'seek', direction: 1 })
    expect(kind('left', at('next'))).toBe('pass')
    expect(kind('rewind', at('panel'))).toBe('swallow')
  })

  it('shows the controls with ▲ ▼ only while they are hidden, and moves focus once they show', () => {
    expect(kind('up', at('none', { controls: false, area: 'root' }))).toBe('show')
    expect(kind('down', at('none', { controls: false, area: 'skip' }))).toBe('show')
    expect(kind('down', at('none'))).toBe('pass')
    expect(kind('up', at('next'))).toBe('pass')
  })

  it('plays or pauses with OK unless a control has focus, and with Play/Pause under the card and a panel', () => {
    expect(kind('ok', at('none', { controls: false, area: 'root' }))).toBe('toggle')
    expect(kind('ok', at('none'))).toBe('toggle')
    expect(kind('ok', at('none', { area: 'buttons' }))).toBe('pass')
    expect(kind('ok', at('none', { area: 'skip' }))).toBe('pass')
    expect(kind('ok', at('next'))).toBe('pass')
    expect(kind('playPause', at('panel'))).toBe('toggle')
    expect(kind('playPause', at('next'))).toBe('toggle')
    expect(kind('play', at('none'))).toBe('play')
    expect(kind('pause', at('none'))).toBe('pause')
  })

  it('answers Still watching with Play/Pause, and leaves with Stop; an administrator’s stop keeps only its button', () => {
    expect(kind('playPause', at('still'))).toBe('keepWatching')
    expect(kind('right', at('still'))).toBe('pass')
    expect(kind('fastForward', at('still'))).toBe('swallow')
    expect(kind('stop', at('still'))).toBe('leave')
    expect(kind('ok', at('message'))).toBe('pass')
    expect(kind('playPause', at('message'))).toBe('swallow')
    expect(kind('stop', at('message'))).toBe('leave')
  })

  it('plays the next or previous episode only where there is one, and keeps channel and number keys', () => {
    expect(kind('next', at('none', { hasNext: true }))).toBe('next')
    expect(kind('next', at('next', { hasNext: true }))).toBe('next')
    expect(kind('next', at('none'))).toBe('swallow')
    expect(kind('next', at('panel', { hasNext: true }))).toBe('swallow')
    expect(kind('previous', at('none', { hasPrevious: true }))).toBe('previous')
    expect(kind('previous', at('none'))).toBe('swallow')
    expect(kind('stop', at('none'))).toBe('leave')
    expect(kind('info', at('none', { controls: false }))).toBe('show')
    for (const action of ['channelUp', 'digit', 'guide', 'minus'] as Action[]) expect(kind(action, at('none'))).toBe('swallow')
  })

  it('tells where focus is from its id', () => {
    expect(areaOf(undefined)).toBe('root')
    expect(areaOf('skip')).toBe('skip')
    expect(areaOf('scrubber')).toBe('scrubber')
    expect(areaOf('audio')).toBe('buttons')
    expect(areaOf('back')).toBe('buttons')
  })
})

describe('Return', () => {
  it('closes what is open, then hides the controls, then leaves', () => {
    expect(backAction('message', true, false)).toBe('leave')
    expect(backAction('still', true, false)).toBe('keepWatching')
    expect(backAction('panel', true, false)).toBe('closePanel')
    expect(backAction('next', false, false)).toBe('watchCredits')
    expect(backAction('none', true, false)).toBe('hideControls')
    expect(backAction('none', false, false)).toBe('leave')
  })

  it('drops a seek still being held instead', () => {
    expect(backAction('none', true, true)).toBe('cancelScrub')
    expect(backAction('next', false, true)).toBe('cancelScrub')
  })
})

describe('a held seek key', () => {
  it('begins on the first press, steps while held however the TV marks repeats, and applies when it comes up', () => {
    const hold = new SeekHold()
    expect(hold.down('right', 1, 0)).toBe('begin')
    expect(hold.down('right', 1, 50)).toBe('step')
    expect(hold.holding).toBe(true)
    expect(hold.up('left')).toBe(false)
    expect(hold.up('right')).toBe(true)
    expect(hold.holding).toBe(false)
    expect(hold.up('right')).toBe(false)
    expect(hold.down('right', 1, 100)).toBe('begin')
  })

  it('begins again for the other way or another key, and ends on a blur', () => {
    const hold = new SeekHold()
    hold.down('right', 1, 0)
    expect(hold.down('left', -1, 10)).toBe('begin')
    expect(hold.down('rewind', -1, 20)).toBe('begin')
    expect(hold.up(undefined)).toBe(true)
  })

  it('counts as let go after a quiet spell when no keyup comes, and Return drops it', () => {
    const hold = new SeekHold()
    hold.down('fastForward', 1, 0)
    expect(hold.quiet(RELEASE_AFTER_MS - 1)).toBe(false)
    expect(hold.quiet(RELEASE_AFTER_MS)).toBe(true)
    expect(hold.quiet(RELEASE_AFTER_MS * 2)).toBe(false)
    hold.down('left', -1, 0)
    hold.cancel()
    expect(hold.up('left')).toBe(false)
  })

  it('knows the seek keys', () => {
    expect(['left', 'right', 'rewind', 'fastForward', 'up', 'ok'].map((a) => seekKey(a as Action))).toEqual(['left', 'right', 'rewind', 'fastForward', undefined, undefined])
  })
})

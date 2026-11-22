import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TOAST_MS, currentToast, dismissToast, flushToast, showToast, undoToast } from './toast'

// One message at a time, ended by its time, by the next one, by Undo or by
// leaving the page; each ends with exactly one of done or undo.

let ended: string[]
const show = (text: string) => showToast({ text, undo: () => ended.push(`undo ${text}`), done: () => ended.push(`done ${text}`) })

beforeEach(() => {
  vi.useFakeTimers()
  ended = []
})
afterEach(() => {
  flushToast()
  vi.useRealTimers()
})

describe('a message', () => {
  it('shows until its time is up, then is done', () => {
    show('Saved to Watchlist')
    vi.advanceTimersByTime(TOAST_MS - 1)
    expect(currentToast()?.text).toBe('Saved to Watchlist')
    vi.advanceTimersByTime(1)
    expect(currentToast()).toBeUndefined()
    expect(ended).toEqual(['done Saved to Watchlist'])
  })

  it('runs only its undo when Undo is pressed, and nothing when its time would have ended', () => {
    const id = show('Removed from Watchlist')
    undoToast(id)
    vi.advanceTimersByTime(TOAST_MS)
    expect(ended).toEqual(['undo Removed from Watchlist'])
  })

  it('is done when the next one takes its place, which then has its own full time', () => {
    show('Removed from Watchlist')
    vi.advanceTimersByTime(TOAST_MS - 1000)
    show('Saved to Halloween')
    expect(ended).toEqual(['done Removed from Watchlist'])
    vi.advanceTimersByTime(TOAST_MS - 1)
    expect(currentToast()?.text).toBe('Saved to Halloween')
  })

  it.each([
    ['closed', (id: number) => dismissToast(id)],
    ['flushed as the page goes', () => flushToast()],
  ])('is done when %s', (_case, end) => {
    end(show('Removed from Halloween'))
    expect(ended).toEqual(['done Removed from Halloween'])
  })

  it('ignores Undo and close for one already gone', () => {
    const id = show('Saved to Watchlist')
    vi.advanceTimersByTime(TOAST_MS)
    undoToast(id)
    dismissToast(id)
    expect(ended).toEqual(['done Saved to Watchlist'])
  })
})

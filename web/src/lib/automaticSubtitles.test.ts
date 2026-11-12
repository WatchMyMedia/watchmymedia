import { describe, expect, it, vi } from 'vitest'
import { AutomaticSubtitles } from './automaticSubtitles'
import { changeAuthScope } from './authScope'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}

describe('automatic subtitle ownership', () => {
  it('ignores a failed search after a manual pick rather than showing its late error', async () => {
    let reject!: (error: Error) => void
    const promise = new Promise<{ track: string }>((_resolve, no) => { reject = no })
    const request = vi.fn((_file: number, _play: string, cancel?: boolean) => cancel ? Promise.resolve({}) : promise)
    const work = new AutomaticSubtitles(4, request)
    work.setPlay('play-one')
    const first = work.begin('play-one')
    work.choose('s2')
    reject(new Error('provider timed out'))
    await expect(first).resolves.toBeUndefined()
  })
  it('coalesces repeated frames/reloads into one attempt for the active play', async () => {
    const response = deferred<{ track: string }>()
    const request = vi.fn(() => response.promise)
    const work = new AutomaticSubtitles(4, request)
    work.setPlay('play-one')
    const first = work.begin('play-one')
    await expect(work.begin('play-one')).resolves.toBeUndefined()
    response.resolve({ track: 'd1' })
    await expect(first).resolves.toEqual({ track: 'd1' })
    expect(request).toHaveBeenCalledTimes(1)
  })

  it('cancels after explicit Off and rejects a result already in flight', async () => {
    const response = deferred<{ track: string }>()
    const request = vi.fn((_file: number, _play: string, cancel?: boolean) => cancel ? Promise.resolve({}) : response.promise)
    const work = new AutomaticSubtitles(4, request)
    work.setPlay('play-one')
    const first = work.begin('play-one')
    work.choose('')
    work.choose('s2')
    response.resolve({ track: 'd1' })
    await expect(first).resolves.toBeUndefined()
    expect(request.mock.calls).toEqual([[4, 'play-one'], [4, 'play-one', true]])
    await expect(work.begin('play-one')).resolves.toBeUndefined()
  })

  it('discards replaced/unmounted play results and cancels outstanding work', async () => {
    const response = deferred<{ track: string }>()
    const request = vi.fn((_file: number, _play: string, cancel?: boolean) => cancel ? Promise.resolve({}) : response.promise)
    const work = new AutomaticSubtitles(4, request)
    work.setPlay('play-one')
    const first = work.begin('play-one')
    work.setPlay('play-two')
    work.close()
    response.resolve({ track: 'd1' })
    await expect(first).resolves.toBeUndefined()
    expect(request).toHaveBeenCalledWith(4, 'play-one', true)
  })

  it('never sends a delayed cancellation using another sign-in or returns its result', async () => {
    const response = deferred<{ track: string }>()
    const request = vi.fn(() => response.promise)
    const work = new AutomaticSubtitles(4, request)
    work.setPlay('play-one')
    const first = work.begin('play-one')
    changeAuthScope()
    work.close()
    response.resolve({ track: 'd1' })
    await expect(first).resolves.toBeUndefined()
    await expect(work.begin('play-two')).resolves.toBeUndefined()
    expect(request).toHaveBeenCalledTimes(1)
  })
})

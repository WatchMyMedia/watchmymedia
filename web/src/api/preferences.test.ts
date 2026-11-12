import { MutationObserver, QueryClient, onlineManager } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'
import { preferenceMutationOptions, preferencesQuery } from './queries'
import { authScope, changeAuthScope } from '../lib/authScope'

vi.mock('./client', () => ({ api: { PUT: vi.fn() }, unwrap: <T,>(r: { data: T }) => r.data }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
const preferences = (intro: string) => ({ skip: { intro }, autoplayNext: true, subtitles: { languages: ['es'], mode: 'on' as const, automaticDownload: false } })

afterEach(() => { onlineManager.setOnline(true); vi.clearAllMocks() })

describe('preference writes across settings routes', () => {
  it('does not let a delayed subtitle reply undo a later playback change', async () => {
    const client = new QueryClient()
    const started = deferred<void>()
    const firstReply = deferred<{ data: ReturnType<typeof preferences> }>()
    vi.mocked(api.PUT).mockImplementationOnce(() => { started.resolve(); return firstReply.promise as never })
      .mockResolvedValueOnce({ data: preferences('auto') } as never)
    const subtitles = new MutationObserver(client, preferenceMutationOptions(client))
    const playback = new MutationObserver(client, preferenceMutationOptions(client))
    const first = subtitles.mutate({ body: { subtitles: { languages: ['es'] } }, scope: authScope() })
    await started.promise
    const second = playback.mutate({ body: { skip: { intro: 'auto' } }, scope: authScope() })
    expect(playback.getCurrentResult().isPaused).toBe(true)
    expect(api.PUT).toHaveBeenCalledTimes(1)
    firstReply.resolve({ data: preferences('ask') })
    await Promise.all([first, second])
    expect(client.getQueryData(preferencesQuery.queryKey)).toEqual(preferences('auto'))
    client.clear()
  })

  it('refuses an old queued payload before dispatch under another sign-in', async () => {
    const client = new QueryClient()
    onlineManager.setOnline(false)
    const update = new MutationObserver(client, preferenceMutationOptions(client))
    const pending = update.mutate({ body: { subtitles: { mode: 'on' } }, scope: authScope() })
    const refused = expect(pending).rejects.toThrow('sign-in changed')
    changeAuthScope()
    onlineManager.setOnline(true)
    await client.resumePausedMutations()
    await refused
    expect(api.PUT).not.toHaveBeenCalled()
    client.clear()
  })
})

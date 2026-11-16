import { MutationObserver, QueryClient } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from './schema'

// Stop notifying pressed twice quickly, with the server refusing: each
// refusal puts back its own row and says so for its own show
// (PLAN-NEW-EPISODES.md §7.2). The API is a stand-in that answers when told.

type Follow = components['schemas']['Follow']
type ShowDetail = components['schemas']['ShowDetail']

const answers = new Map<number, (ok: boolean) => void>()
vi.mock('./client', () => ({
  api: {
    DELETE: (_path: string, o: { params: { path: { tmdbId: number } } }) =>
      new Promise((resolve) => {
        answers.set(o.params.path.tmdbId, (ok) =>
          resolve(ok ? { data: undefined, response: new Response(null, { status: 204 }) } : { error: { detail: 'refused' }, response: new Response(null, { status: 500 }) }))
      }),
  },
  unwrap: (res: { data?: unknown; error?: unknown; response: Response }) => {
    if (res.error !== undefined) throw new Error('refused')
    return res.data
  },
}))

const { applyFollowing, followsQuery, removeFollowOptions, undoFollowing } = await import('./follows')

const follow = (tmdbId: number, title: string, days: number): Follow => ({ tmdbId, title, followedAt: new Date(Date.UTC(2026, 10, 16 - days)).toISOString() })
const list = [follow(1, 'The Sleuth Files', 1), follow(2, 'Night Ferry', 2), follow(3, 'Copperline', 3)]
const titles = (qc: QueryClient) => qc.getQueryData<{ follows: Follow[] }>(followsQuery.queryKey)?.follows.map((f) => f.title)
const flush = () => new Promise((r) => setTimeout(r, 0))

let qc: QueryClient
beforeEach(() => {
  answers.clear()
  qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  qc.setQueryData(followsQuery.queryKey, { follows: list })
})

describe('Stop notifying, twice quickly', () => {
  it('says each refusal on its own row, not only the latest', async () => {
    const failed: number[] = []
    const observer = new MutationObserver(qc, removeFollowOptions(qc, { onFailed: (id) => failed.push(id) }))
    void observer.mutate(1).catch(() => {})
    void observer.mutate(2).catch(() => {})
    await flush()
    expect(titles(qc)).toEqual(['Copperline'])
    answers.get(1)!(false)
    answers.get(2)!(false)
    await flush()
    expect(failed.sort()).toEqual([1, 2])
    // Each put back its own row, in the server's order.
    expect(titles(qc)).toEqual(['The Sleuth Files', 'Night Ferry', 'Copperline'])
  })

  it('keeps the first back when only it was refused, and the second gone', async () => {
    const failed: number[] = []
    const observer = new MutationObserver(qc, removeFollowOptions(qc, { onFailed: (id) => failed.push(id) }))
    void observer.mutate(1).catch(() => {})
    void observer.mutate(2).catch(() => {})
    await flush()
    answers.get(1)!(false)
    await flush()
    answers.get(2)!(true)
    await flush()
    expect(failed).toEqual([1])
    expect(titles(qc)?.slice(0, 2)).toEqual(['The Sleuth Files', 'Copperline'])
  })

  it('doesn’t take back a row the first refusal returned when the second is refused', async () => {
    const one = await applyFollowing(qc, 1, false)
    const two = await applyFollowing(qc, 2, false)
    undoFollowing(qc, one)
    undoFollowing(qc, two)
    expect(titles(qc)).toEqual(['The Sleuth Files', 'Night Ferry', 'Copperline'])
  })
})

describe('Notify me refused', () => {
  it('puts back the bell on the show’s pages with that TMDB id, and nothing else', async () => {
    const show = { id: 7, tmdbId: 1, title: 'The Sleuth Files', following: false } as ShowDetail
    const other = { id: 8, tmdbId: 2, title: 'Night Ferry', following: true } as ShowDetail
    qc.setQueryData(['show', 7], show)
    qc.setQueryData(['show', 8], other)
    const undo = await applyFollowing(qc, 1, true)
    expect(qc.getQueryData<ShowDetail>(['show', 7])?.following).toBe(true)
    undoFollowing(qc, undo)
    expect(qc.getQueryData<ShowDetail>(['show', 7])?.following).toBe(false)
    expect(qc.getQueryData<ShowDetail>(['show', 8])?.following).toBe(true)
    expect(titles(qc)).toEqual(['The Sleuth Files', 'Night Ferry', 'Copperline'])
  })
})

import { expect, it } from 'vitest'
import { castClient, castServerKey } from './server'

it('queued reports and renewal retain their original receiver account', async () => {
  const sent: Request[] = []
  const fetcher: typeof fetch = async (input) => {
    sent.push(input as Request)
    return new Response('{}', { headers: { 'Content-Type': 'application/json' } })
  }
  const a = { token: 'c1.viewer-a' }
  const old = castClient('https://first.example', a, fetcher)
  const next = castClient('https://second.example', { token: 'c1.viewer-b' }, fetcher)
  await next.GET('/api/me/preferences')
  a.token = 'c1.renewed-a'
  await old.GET('/api/me/preferences')
  expect(sent.map((r) => [new URL(r.url).origin, r.headers.get('Authorization')])).toEqual([
    ['https://second.example', 'Bearer c1.viewer-b'],
    ['https://first.example', 'Bearer c1.renewed-a'],
  ])
})

it('does not reuse an endpoint based on a public server id', () => {
  expect(castServerKey({ id: 'same', urls: ['https://first.example'] })).not.toBe(
    castServerKey({ id: 'same', urls: ['https://second.example'] }))
})

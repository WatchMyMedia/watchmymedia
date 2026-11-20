import createClient from 'openapi-fetch'
import { describe, expect, it } from 'vitest'
import { ApiError, unwrap } from './client'
import type { paths } from './schema'

// A client like the app's, answering every request with the reply given, so
// unwrap sees what openapi-fetch really makes of it.
const answering = (reply: () => Response) => createClient<paths>({ baseUrl: 'http://server.test', fetch: async () => reply() })
const json = (status: number, body: unknown, type = 'application/json') =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': type } })

describe('unwrap', () => {
  it('gives the body of a 200', async () => {
    const res = await answering(() => json(200, { name: 'Server', version: '1.7.0' })).GET('/api/server')
    expect(unwrap(res)).toEqual({ name: 'Server', version: '1.7.0' })
  })

  it('gives the body of a 202 that has one, as a library scan does', async () => {
    const res = await answering(() => json(202, { queued: true })).POST('/api/libraries/{id}/scan', { params: { path: { id: 1 } } })
    expect(unwrap(res)).toEqual({ queued: true })
  })

  // Go sends Content-Length: 0 for an empty reply; without it openapi-fetch reads the text.
  it.each([
    ['with Content-Length: 0', { 'Content-Length': '0' }],
    ['without a Content-Length', {}],
  ])('accepts a 202 with no body %s, as Search now answers', async (_, headers: Record<string, string>) => {
    const res = await answering(() => new Response(null, { status: 202, headers }))
      .POST('/api/shows/{id}/search', { params: { path: { id: 1 } }, body: { season: 2 } })
    expect(unwrap(res)).toBeUndefined()
  })

  it('accepts a 204', async () => {
    const res = await answering(() => new Response(null, { status: 204 }))
      .POST('/api/shows/{id}/monitor', { params: { path: { id: 1 } }, body: { season: 2, monitored: true } })
    expect(unwrap(res)).toBeUndefined()
  })

  it('throws an ApiError with the problem’s detail, type and retry delay', async () => {
    const res = await answering(() => new Response(
      JSON.stringify({ type: 'https://watchmy.media/problems/sonarr', title: 'Bad Gateway', status: 502, detail: 'Sonarr didn’t answer' }),
      { status: 502, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '5' } },
    )).POST('/api/shows/{id}/search', { params: { path: { id: 1 } }, body: { season: 2 } })
    let thrown: unknown
    try { unwrap(res) } catch (err) { thrown = err }
    expect(thrown).toBeInstanceOf(ApiError)
    expect(thrown).toMatchObject({ status: 502, message: 'Sonarr didn’t answer', type: 'https://watchmy.media/problems/sonarr', retryAfter: 5 })
  })

  it('throws on a failure with no body', async () => {
    for (const headers of [{ 'Content-Length': '0' }, {}] as Record<string, string>[]) {
      const res = await answering(() => new Response(null, { status: 503, headers })).POST('/api/libraries/{id}/scan', { params: { path: { id: 1 } } })
      expect(() => unwrap(res)).toThrow(new ApiError(res.response, undefined).message)
    }
  })
})

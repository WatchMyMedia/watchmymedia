// A fictional WatchMyMedia server for the browser preview and for tests:
// routes by method and path, with bearer sign-in like the real one. Each
// feature registers its own routes (fakes/<area>.ts, listed in index.ts).

export type FakeRequest = {
  method: string
  url: URL
  /** The path's `:name` parts. */
  params: Record<string, string>
  /** The JSON body, when there is one. */
  body: unknown
  request: Request
  /** The signed-in user's session token, when the request carried a valid one. */
  token?: string
}

/** Return a Response, or anything else to send it as JSON. */
export type FakeHandler = (req: FakeRequest) => unknown

type Route = { method: string; pattern: RegExp; names: string[]; handler: FakeHandler; open: boolean }

export function json(body: unknown, status = 200): Response {
  return Response.json(body, { status })
}

/** A problem reply, as the server sends (application/problem+json). */
export function problem(status: number, detail: string): Response {
  return new Response(JSON.stringify({ status, title: 'Error', detail }), { status, headers: { 'Content-Type': 'application/problem+json' } })
}

export class FakeServer {
  private routes: Route[] = []
  private tokens = new Set<string>()
  private issued = 0

  constructor(readonly origin: string) {}

  /**
   * Answers `method pattern` (e.g. `GET /api/libraries/:id/items`). Routes
   * need a signed-in token unless `open` (as /api/server and pairing are).
   */
  route(method: string, pattern: string, handler: FakeHandler, { open = false } = {}) {
    const names: string[] = []
    const source = pattern.replace(/:([a-zA-Z]+)/g, (_, name: string) => { names.push(name); return '([^/]+)' })
    this.routes.push({ method, pattern: new RegExp(`^${source}$`), names, handler, open })
  }

  /** A new session token, as an approved code or a password sign-in gives. */
  issueToken(): string {
    const token = `fake-session-${++this.issued}`
    this.tokens.add(token)
    return token
  }

  /** Takes a token as signed in: one this TV kept from a sign-in before the preview started. */
  trust(token: string) { this.tokens.add(token) }

  revokeToken(token: string) { this.tokens.delete(token) }

  /** Every token stops working: "signed out from another device". */
  revokeAll() { this.tokens.clear() }

  async handle(request: Request): Promise<Response> {
    const url = new URL(request.url)
    const bearer = request.headers.get('Authorization')?.replace(/^Bearer /, '')
    const token = bearer && this.tokens.has(bearer) ? bearer : undefined
    const media = url.searchParams.get('access_token')
    for (const r of this.routes) {
      if (r.method !== request.method) continue
      const m = r.pattern.exec(url.pathname)
      if (!m) continue
      if (!r.open && !token && !(media && request.method === 'GET')) return problem(401, 'sign in required')
      const params = Object.fromEntries(r.names.map((n, i) => [n, decodeURIComponent(m[i + 1])]))
      let body: unknown
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        const text = await request.text()
        try { body = text ? JSON.parse(text) : undefined } catch { body = undefined }
      }
      // A moment's latency, so loading states show as they would.
      await new Promise((resolve) => setTimeout(resolve, 120))
      const reply = await r.handler({ method: request.method, url, params, body, request, token })
      return reply instanceof Response ? reply : reply === undefined ? new Response(null, { status: 204 }) : json(reply)
    }
    return problem(404, `no fake for ${request.method} ${url.pathname}`)
  }
}

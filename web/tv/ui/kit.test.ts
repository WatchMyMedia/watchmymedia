import { describe, expect, it } from 'vitest'
import { ApiError } from '../../src/api/client'
import { AddressError } from '../session/address'
import { formatCode } from '../session/pairing'
import { problemWords, signInWords } from './problemWords'
import { qrPath } from './qr'
import { railBadge } from './railBadge'

const apiError = (status: number, detail = 'secret path /data/media and token abc') =>
  new ApiError(new Response(null, { status }), { detail })

describe('problem wording', () => {
  it('words failures by kind and status, never with the server’s text', () => {
    expect(problemWords(new TypeError('Failed to fetch')).title).toBe('Can’t reach the server')
    expect(problemWords(new DOMException('x', 'AbortError')).title).toBe('The server took too long')
    expect(problemWords(new AddressError()).title).toBe('That isn’t a server address')
    const words = [401, 403, 404, 409, 429, 500, 502, 503, 418].map((s) => problemWords(apiError(s)))
    expect(words.map((w) => w.title)).toEqual(['Signed out', 'Not allowed', 'Not found', 'Already done', 'Too many tries',
      'The server had a problem', 'The server had a problem', 'The server is busy', 'Something went wrong'])
    for (const w of words) expect(JSON.stringify(w)).not.toMatch(/secret|token abc|\/data/)
    expect(problemWords({ status: 404, message: 'https://x/?access_token=1' }).body).not.toContain('access_token')
  })

  it('says what a password sign-in failure means', () => {
    expect(signInWords(apiError(401))).toContain('don’t match')
    expect(signInWords(apiError(429))).toContain('Too many tries')
    expect(signInWords(apiError(403))).toContain('Use a code instead')
  })
})

describe('sign-in code and QR', () => {
  it('shows a code as ABC-234', () => {
    expect(formatCode('KX7P4M')).toBe('KX7-P4M')
    expect(formatCode('ABCD')).toBe('ABCD')
  })

  it('draws a QR code of whole modules for the link page', () => {
    const qr = qrPath('http://192.168.1.20:27587/link?code=KX7P4M')!
    expect(qr.size).toBe(29)
    expect(qr.path).toMatch(/^M\d+ \d+h\d+v1h-\d+z/)
    // The three finder patterns: the top-left 7×7 square starts with a run of seven.
    expect(qr.path.startsWith('M0 0h7v1h-7z')).toBe(true)
  })
})

describe('the rail', () => {
  it('counts the Inbox for admins only', () => {
    expect(railBadge({ inbox: 3, manage: true }, true)).toBe(3)
    expect(railBadge({ inbox: 3, manage: true }, false)).toBeUndefined()
    expect(railBadge({ inbox: 0, manage: true }, true)).toBeUndefined()
    expect(railBadge(undefined, true)).toBeUndefined()
  })
})

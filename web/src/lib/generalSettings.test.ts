import { describe, expect, it } from 'vitest'
import { generalLede, generalNextStep, removeKeyWords, serverNameError, tmdbDoneLine, tmdbSaveMessage, tmdbTestMessage } from './generalSettings'

const words = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.')
const refusal = (status: number, message: string) => Object.assign(new Error(message), { status })

describe('General', () => {
  it('makes Add a TMDB key the Next step while there is none', () => {
    expect(generalNextStep({ tmdbConfigured: false })).toBe('tmdb')
    expect(generalNextStep({ tmdbConfigured: true })).toBeNull()
    expect(generalNextStep(undefined)).toBeNull()
  })

  it('leads with the version, libraries and live updates', () => {
    expect(generalLede('1.4.4', 2, true)).toBe('Version 1.4.4 · 2 libraries · events connected')
    expect(generalLede(undefined, 1, false)).toBe('1 library · events reconnecting')
  })

  it('checks the server name’s length', () => {
    expect(serverNameError('Living room server')).toBeUndefined()
    expect(serverNameError('x'.repeat(64))).toBe('Up to 63 characters.')
  })
})

describe('Add a TMDB key', () => {
  it('words Test’s answer, which saves nothing', () => {
    expect(tmdbTestMessage({ ok: true, message: 'TMDB accepted the key.' })).toEqual({ tone: 'ok', title: 'TMDB accepted this key', body: 'Nothing is saved yet.' })
    expect(tmdbTestMessage({ ok: false, message: 'tmdb: invalid API key' }).title).toBe('TMDB didn’t accept this key')
    expect(tmdbTestMessage({ ok: false, message: 'dial tcp: i/o timeout' }).title).toBe('Couldn’t reach TMDB')
  })

  it('words Save’s refusals', () => {
    expect(tmdbSaveMessage(refusal(422, 'TMDB rejected the key: tmdb: invalid API key'), words).title).toBe('TMDB didn’t accept this key')
    expect(tmdbSaveMessage(refusal(503, 'couldn’t reach TMDB'), words)).toEqual({ tone: 'err', title: 'Couldn’t reach TMDB, so the key wasn’t saved', body: 'Check the server’s internet connection and save again.' })
    expect(tmdbSaveMessage(refusal(500, 'disk I/O error'), words).body).toBe('disk I/O error')
  })
})

describe('Remove the TMDB key', () => {
  it('says what stops and stays', () => {
    const w = removeKeyWords(false)
    expect(w.stops).toEqual(['Matching new files, trailers, TMDB lists and looking things up to request.'])
    expect(w.stays).toEqual(['Posters and details already fetched.'])
    expect(w.config).toBeUndefined()
  })

  it('says the key in config.yaml comes back at the next restart', () => {
    expect(removeKeyWords(true).config?.title).toBe('config.yaml also has a TMDB key')
    expect(tmdbDoneLine('removed', true)).toContain('from the next restart')
    expect(tmdbDoneLine('saved')).toBe('The key is saved. Every library is being matched again; Libraries shows progress.')
  })
})

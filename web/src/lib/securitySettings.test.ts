import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { TURNSTILE_STEPS, keysRefusal, remoteAdminRefusal, resolveTurnstileStep, turnOffRefusal, whereYouAre } from './securitySettings'

const refused = (status: number, detail: string) => new ApiError(new Response('', { status }), { detail })
const fresh = { hasKeys: false, enabled: false, gotKeys: false, tried: false }

describe('Turn on sign-in protection', () => {
  it('asks for the password away from home on Enter keys only', () => {
    expect(TURNSTILE_STEPS.filter((s) => s.guarded).map((s) => s.key)).toEqual(['keys'])
  })
  it('starts at Get keys, and Enter keys once the admin has them', () => {
    expect(resolveTurnstileStep(undefined, fresh).key).toBe('get')
    expect(resolveTurnstileStep('keys', fresh).key).toBe('get')
    expect(resolveTurnstileStep('keys', { ...fresh, gotKeys: true }).key).toBe('keys')
  })
  it('opens Try it only once protection is on, and On only once the check passed', () => {
    expect(resolveTurnstileStep('try', { ...fresh, gotKeys: true }).key).toBe('keys')
    expect(resolveTurnstileStep('on', { ...fresh, hasKeys: true, enabled: true }).key).toBe('try')
    expect(resolveTurnstileStep('on', { ...fresh, hasKeys: true, enabled: true, tried: true }).key).toBe('on')
  })
  it('opens Try it after the reload that follows saving the keys', () => {
    // Saved keys stand in for "I have my keys", which the reload forgets.
    expect(resolveTurnstileStep('try', { ...fresh, hasKeys: true, enabled: true }).key).toBe('try')
    expect(resolveTurnstileStep(undefined, { ...fresh, hasKeys: true, enabled: true }).key).toBe('try')
  })
  it('lets Change keys reopen Enter keys while protection is on', () => {
    expect(resolveTurnstileStep('keys', { ...fresh, hasKeys: true, enabled: true }).key).toBe('keys')
  })
})

describe('refusals', () => {
  it('words a secret Cloudflare refused, under the secret', () => {
    expect(keysRefusal(refused(422, 'the secret key was refused by Cloudflare; copy it again from the Turnstile widget’s settings'))).toEqual({
      field: 'secret',
      title: 'Cloudflare didn’t accept the secret key',
      text: 'Nothing was saved, and sign-in works as before. Copy the secret key again from the widget’s page. It starts with 0x.',
    })
  })
  it('asks for a missing secret', () => {
    expect(keysRefusal(refused(422, 'enter the secret key too'))).toEqual({ field: 'secret', text: 'Enter the secret key too.' })
  })
  it('words keys that aren’t keys, Cloudflare out of reach, and keys from config', () => {
    expect(keysRefusal(refused(422, 'that does not look like a Turnstile key; copy …')).title).toBe('Those don’t look like Turnstile keys')
    expect(keysRefusal(refused(503, 'could not reach Cloudflare to check the secret key; try again in a minute'))).toEqual({
      title: 'Couldn’t reach Cloudflare to check the keys',
      text: 'Nothing was saved. Check the server’s internet connection and try again.',
    })
    expect(keysRefusal(refused(503, 'Turnstile is not available')).title).toBe('Sign-in protection isn’t available on this server')
    expect(keysRefusal(refused(409, 'the Turnstile keys are set in config.yaml')).title).toBe('Set in config.yaml')
    expect(keysRefusal(refused(500, '')).text).toMatch(/nothing was saved/i)
  })
  it('words turning protection off and the remote admin switch', () => {
    expect(turnOffRefusal(refused(409, 'set in config'))).toMatch(/config.yaml/)
    expect(remoteAdminRefusal(refused(403, 'turn remote admin on from your home network or tailnet'))).toBe('It can only be turned on from your home network or tailnet.')
    expect(remoteAdminRefusal(refused(409, 'remote admin is set by network.remote_admin'))).toMatch(/^It’s set by network.remote_admin/)
  })
})

describe('Where you are now', () => {
  it('says home plainly', () => {
    expect(whereYouAre({ away: false, minutes: 0 }).where).toBe('at home')
  })
  it('says how long a confirmed password still lasts away from home', () => {
    expect(whereYouAre({ away: true, minutes: 6 })).toEqual({
      where: 'away from home',
      text: 'connected over HTTPS. Server settings are open, and the riskier ones ask for your password. You confirmed it 4 minutes ago, so you won’t be asked again for 6 minutes.',
    })
    expect(whereYouAre({ away: true, minutes: 10 }).text).toMatch(/confirmed it just now, so you won’t be asked again for 10 minutes/)
    expect(whereYouAre({ away: true, minutes: 1 }).text).toMatch(/9 minutes ago, so you won’t be asked again for 1 minute\./)
    expect(whereYouAre({ away: true, minutes: 0 }).text).toMatch(/asked the next time/)
  })
})

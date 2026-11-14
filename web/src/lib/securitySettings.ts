// The rules of Settings › Security (PLAN-ADMIN-SETTINGS.md §5.6): where this
// browser is, the steps of Turn on sign-in protection, and the words for
// every refusal the server can give.

import { ApiError } from '../api/client'
import { errorText } from '../components/settings/hooks'
import { resolveStep, type FlowStep } from './flowSteps'

export const TURNSTILE_STEPS: readonly FlowStep[] = [
  { key: 'get', label: 'Get keys' },
  { key: 'keys', label: 'Enter keys', guarded: true },
  { key: 'try', label: 'Try it' },
  { key: 'on', label: 'On' },
]

export type TurnstileFlowState = {
  /** Keys are saved (from an earlier visit, or this one). */
  hasKeys: boolean
  /** Protection is on now. */
  enabled: boolean
  /** "I have my keys" was pressed in this visit. */
  gotKeys: boolean
  /** The check turned green and "It works, finish" was pressed. */
  tried: boolean
}

/**
 * The step a sign-in protection address opens. Get keys is done once keys
 * are saved or the admin said they have them; Enter keys once protection is
 * on; Try it once the check passed. A later step whose earlier ones aren't
 * done opens the first that isn't.
 */
export function resolveTurnstileStep(requested: string | undefined, s: TurnstileFlowState): FlowStep {
  return resolveStep(TURNSTILE_STEPS, requested, (key) => {
    if (key === 'get') return s.hasKeys || s.gotKeys
    if (key === 'keys') return s.enabled
    if (key === 'try') return s.enabled && s.tried
    return false
  })
}

export type KeysRefusal = { field?: 'site' | 'secret'; title?: string; text: string }

/**
 * Saving the keys was refused. Nothing is saved in any of these, so each
 * says that sign-in works as before.
 */
export function keysRefusal(error: unknown): KeysRefusal {
  if (error instanceof ApiError) {
    const m = error.message
    if (error.status === 422 && /secret key was refused/i.test(m))
      return { field: 'secret', title: 'Cloudflare didn’t accept the secret key', text: 'Nothing was saved, and sign-in works as before. Copy the secret key again from the widget’s page. It starts with 0x.' }
    if (error.status === 422 && /enter the secret key/i.test(m)) return { field: 'secret', text: 'Enter the secret key too.' }
    if (error.status === 422 && /look like a turnstile key/i.test(m))
      return { title: 'Those don’t look like Turnstile keys', text: 'Nothing was saved, and sign-in works as before. Copy the site key and the secret key from the widget’s page in Cloudflare. Both start with 0x.' }
    if (error.status === 503 && /reach cloudflare/i.test(m))
      return { title: 'Couldn’t reach Cloudflare to check the keys', text: 'Nothing was saved. Check the server’s internet connection and try again.' }
    if (error.status === 503) return { title: 'Sign-in protection isn’t available on this server', text: 'Nothing was saved.' }
    if (error.status === 409) return { title: 'Set in config.yaml', text: 'The keys come from auth.turnstile in config.yaml or the environment. Change them there and restart the server.' }
  }
  return { title: 'The keys weren’t saved', text: errorText(error, 'Something went wrong, and nothing was saved. Sign-in works as before. Try again.') }
}

/** Turning protection off was refused. */
export function turnOffRefusal(error: unknown): string {
  if (error instanceof ApiError && error.status === 409) return 'The keys come from config.yaml or the environment, so turn it off there and restart the server.'
  return errorText(error, 'Something went wrong, and protection is still on. Try again.')
}

/** The remote admin switch was refused. */
export function remoteAdminRefusal(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return 'It can only be turned on from your home network or tailnet.'
    if (error.status === 409) return 'It’s set by network.remote_admin in config.yaml. Change it there and restart the server.'
  }
  return errorText(error)
}

/**
 * "Where you are now": away or at home, and how long a confirmed password
 * still lasts. `minutes` is what is left of the ten minutes, 0 for none.
 */
export function whereYouAre({ away, minutes, grantMinutes = 10 }: { away: boolean; minutes: number; grantMinutes?: number }) {
  if (!away) {
    return {
      where: 'at home',
      text: 'on your home network or tailnet. Server settings are open, and nothing here asks for your password again.',
    }
  }
  const ago = Math.max(0, grantMinutes - minutes)
  const grant = minutes > 0
    ? ` You confirmed it ${ago === 0 ? 'just now' : `${ago} ${ago === 1 ? 'minute' : 'minutes'} ago`}, so you won’t be asked again for ${minutes} ${minutes === 1 ? 'minute' : 'minutes'}.`
    : ' You’ll be asked the next time you make one of those changes.'
  return {
    where: 'away from home',
    text: `connected over HTTPS. Server settings are open, and the riskier ones ask for your password.${grant}`,
  }
}

import { ApiError } from './client'
import { authScope } from '../lib/authScope'

export const CONFIRM_PASSWORD_TYPE = 'https://watchmy.media/problems/confirm-password'

/** Cancellation must leave the action's own form open, without an error message. */
export class ConfirmationCancelled extends Error {
  constructor() { super(''); this.name = 'ConfirmationCancelled' }
}

type Prompt = (action: string) => Promise<void>
let prompt: Prompt | undefined

export function registerPasswordPrompt(handler: Prompt) {
  prompt = handler
  return () => { if (prompt === handler) prompt = undefined }
}

export function visibleConfirmationError(error: unknown) {
  return !!error && !(error instanceof ConfirmationCancelled)
}

/** Retry only the server's explicit confirmation refusal, once, in the same
 * auth scope. An unrelated 403 or a second refusal remains an ordinary error. */
export async function withConfirm<T>(call: () => Promise<T>, action: string, ask: Prompt = requestPasswordConfirmation): Promise<T> {
  const scope = authScope()
  try { return await call() } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 403 || error.type !== CONFIRM_PASSWORD_TYPE) throw error
    if (scope !== authScope()) throw new ConfirmationCancelled()
    await ask(action)
    if (scope !== authScope()) throw new ConfirmationCancelled()
    return call()
  }
}

function requestPasswordConfirmation(action: string) {
  if (!prompt) return Promise.reject(new ConfirmationCancelled())
  return prompt(action)
}

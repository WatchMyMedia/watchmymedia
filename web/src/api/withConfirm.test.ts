import { describe, expect, it, vi } from 'vitest'
import { ApiError } from './client'
import { CONFIRM_PASSWORD_TYPE, ConfirmationCancelled, withConfirm } from './withConfirm'
import { changeAuthScope } from '../lib/authScope'

const refusal = () => new ApiError(new Response('', { status: 403 }), { type: CONFIRM_PASSWORD_TYPE, detail: 'enter your password' })

describe('withConfirm', () => {
  it('continues without asking when the request succeeds', async () => {
    const ask = vi.fn()
    expect(await withConfirm(async () => 'saved', 'save', ask)).toBe('saved')
    expect(ask).not.toHaveBeenCalled()
  })
  it('asks on the typed refusal and retries the same call once', async () => {
    const call = vi.fn().mockRejectedValueOnce(refusal()).mockResolvedValueOnce('saved')
    const ask = vi.fn().mockResolvedValue(undefined)
    expect(await withConfirm(call, 'add an admin', ask)).toBe('saved')
    expect(call).toHaveBeenCalledTimes(2)
    expect(ask).toHaveBeenCalledExactlyOnceWith('add an admin')
  })
  it('does not retry any other failure or a second refusal', async () => {
    for (const error of [new Error('offline'), new ApiError(new Response('', { status: 403 }), { detail: 'admin only' })]) {
      const ask = vi.fn()
      await expect(withConfirm(async () => { throw error }, 'save', ask)).rejects.toBe(error)
      expect(ask).not.toHaveBeenCalled()
    }
    const call = vi.fn().mockRejectedValue(refusal()), ask = vi.fn().mockResolvedValue(undefined)
    await expect(withConfirm(call, 'save', ask)).rejects.toMatchObject({ type: CONFIRM_PASSWORD_TYPE })
    expect(call).toHaveBeenCalledTimes(2)
    expect(ask).toHaveBeenCalledTimes(1)
  })
  it('leaves a cancelled action unexecuted', async () => {
    const call = vi.fn().mockRejectedValue(refusal())
    await expect(withConfirm(call, 'save', async () => { throw new ConfirmationCancelled() })).rejects.toBeInstanceOf(ConfirmationCancelled)
    expect(call).toHaveBeenCalledTimes(1)
  })
  it('never replays an action after the account changes', async () => {
    const call = vi.fn().mockRejectedValue(refusal())
    await expect(withConfirm(call, 'save', async () => { changeAuthScope() })).rejects.toBeInstanceOf(ConfirmationCancelled)
    expect(call).toHaveBeenCalledTimes(1)
    const ask = vi.fn()
    await expect(withConfirm(async () => { changeAuthScope(); throw refusal() }, 'save', ask)).rejects.toBeInstanceOf(ConfirmationCancelled)
    expect(ask).not.toHaveBeenCalled()
  })
})

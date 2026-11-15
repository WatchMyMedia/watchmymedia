import { useEffect, useState, type ReactNode } from 'react'
import { useBlocker } from '@tanstack/react-router'
import { Dialog } from '../../../components/Dialog'
import { Button, LoadingRows, Message } from '../../../components/settings/Frame'
import { errorText } from '../../../components/settings/hooks'
import { shouldAskToLeave } from '../../../lib/drafts'
import { clearFlash, peekFlash } from './data'

/**
 * The leave check for a task whose choices are held across its steps: moving
 * between the task's own steps (addresses under `within`) never asks; leaving
 * the task with something unsaved does (board m-leave). The shared LeaveCheck
 * asks on any change of address, which a stepped draft can't use.
 */
export function StayCheck({ when, within, what }: { when: boolean; within?: string; what: ReactNode }) {
  const blocker = useBlocker({
    shouldBlockFn: ({ current, next }) => shouldAskToLeave(when, current.pathname, next.pathname) && !(within && next.pathname.startsWith(within)),
    enableBeforeUnload: when,
    withResolver: true,
  })
  if (blocker.status !== 'blocked') return null
  return (
    <Dialog title="Leave this step?" onClose={() => blocker.reset()} width="max-w-md">
      <div className="px-6 pt-2 pb-6">
        <p className="text-sm text-neutral-300">{what}</p>
        <div className="mt-5 flex justify-end gap-3 border-t border-line pt-4">
          <Button onClick={() => blocker.reset()} className="flex-1 sm:flex-none">Keep editing</Button>
          <Button variant="primary" onClick={() => blocker.proceed()} className="flex-1 sm:flex-none">Leave</Button>
        </div>
      </div>
    </Dialog>
  )
}

/** The banner a one-step task leaves on the page it returns to, shown once. */
export function FlashBanner({ page }: { page: string }) {
  const [flash] = useState(() => peekFlash(page))
  useEffect(() => clearFlash(page), [page])
  if (!flash) return null
  return <Message tone="ok" title={flash.title}>{flash.body}</Message>
}

/** A page's data couldn't load: what happened, and Try again. */
export function LoadFailed({ error, retry, what = 'Live TV settings' }: { error: unknown; retry: () => void; what?: string }) {
  return (
    <Message tone="err" title={`Couldn’t load ${what}`} actions={<Button small onClick={retry}>Try again</Button>}>
      {errorText(error)}
    </Message>
  )
}

/** Until the data is here, the page keeps its shape (m-states). */
export function Loading({ rows = 3 }: { rows?: number }) {
  return <LoadingRows rows={rows} />
}

import { Message } from '../../../components/settings/Frame'
import { useAway } from '../../../components/settings/hooks'
import type { Problem } from '../../../lib/integrationsSettings'
import { useFlash } from './shared'

export function ProblemMessage({ problem, focus = true, className = '' }: { problem: Problem | null; focus?: boolean; className?: string }) {
  if (!problem) return null
  return (
    <Message tone="err" title={problem.title} focus={focus} className={className}>
      <p>{problem.body}</p>
      {problem.detail && <p className="mt-1 text-[12.5px] break-words text-neutral-400 [overflow-wrap:anywhere]">The app said: {problem.detail}</p>}
    </Message>
  )
}

/** The line beside Test, away from home. */
export function TestCaption() {
  const { away, confirmed } = useAway()
  if (!away || confirmed) return null
  return <span className="text-[12.5px] text-amber-300">Away from home, Test asks for your password too.</span>
}

export function FlashBanner({ href }: { href: string }) {
  const f = useFlash(href)
  if (!f) return null
  return <Message tone="ok" title={f.title}>{f.body}</Message>
}

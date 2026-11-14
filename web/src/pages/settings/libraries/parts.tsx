import type { ReactNode } from 'react'
import { HrefLink, Message } from '../../../components/settings/Frame'
import { settingsHref } from '../../../lib/settingsRoutes'
import type { Refusal } from '../../../lib/librariesSettings'

const look = {
  primary: 'btn-primary font-semibold',
  secondary: 'border border-line-2 text-neutral-100 hover:bg-surface-2',
}

/** A link drawn as a button, for actions that open another page. */
export function LinkButton({ href, children, variant = 'secondary', small, className = '' }: { href: string; children: ReactNode; variant?: keyof typeof look; small?: boolean; className?: string }) {
  return (
    <HrefLink href={href} className={`inline-flex items-center justify-center gap-2 rounded-md px-3.5 text-sm ${small ? 'min-h-[36px]' : 'min-h-[44px]'} ${look[variant]} ${className}`}>
      {children}
    </HrefLink>
  )
}

const TMDB_HREF = settingsHref('general', 'tmdb')

/** Said wherever matching would run without a TMDB key. */
export function MatchingOff({ short }: { short?: boolean }) {
  if (short) {
    return (
      <Message tone="info" title="Match is off">
        There’s no TMDB key. <HrefLink href={TMDB_HREF}>Add one in General</HrefLink>.
      </Message>
    )
  }
  return (
    <Message tone="warn" title="Matching is off: there’s no TMDB key">
      Files play, without posters or details. <HrefLink href={TMDB_HREF}>Add a key in General</HrefLink> and every library is matched.
    </Message>
  )
}

export function RefusalMessage({ refusal, actions, focus }: { refusal: Refusal; actions?: ReactNode; focus?: boolean }) {
  return <Message tone="err" title={refusal.title} actions={actions} focus={focus}>{refusal.body}</Message>
}

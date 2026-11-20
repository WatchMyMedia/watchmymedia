import type { ReactNode } from 'react'
import { useNav } from '../app/hooks'
import { Button } from '../ui/Button'

/**
 * A screen still to be built: what it is, who replaces it, and a way back.
 * Each feature replaces its placeholders in its own folder under screens/.
 */
export function Placeholder({ kicker, title, children, full }: { kicker: string; title: string; children?: ReactNode; full?: boolean }) {
  const nav = useNav()
  return <div className={`tv-placeholder${full ? ' tv-placeholder--full' : ''}`}>
    <p className="tv-kicker">{kicker}</p>
    <h1 className="tv-h1">{title}</h1>
    {children && <div className="tv-lede">{children}</div>}
    <div className="tv-actions"><Button id="placeholder/back" autoFocus onPress={() => nav.back()}>Back</Button></div>
  </div>
}

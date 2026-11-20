import { useEffect, useEffectEvent } from 'react'
import { actionFor, type Action } from './keys'

/**
 * Calls `handler` with each remote key that comes up while mounted (and
 * `enabled`), and with undefined when the window loses focus, as if every
 * key came up. For what is done when a held key is let go (a held seek
 * applies on release); the Input sends keydowns only, and OK's own keyup.
 */
export function useKeyUp(handler: (action: Action | undefined) => void, enabled = true) {
  const onUp = useEffectEvent(handler)
  useEffect(() => {
    if (!enabled) return
    const up = (event: KeyboardEvent) => { const input = actionFor(event); if (input) onUp(input.action) }
    const blur = () => onUp(undefined)
    document.addEventListener('keyup', up)
    window.addEventListener('blur', blur)
    return () => {
      document.removeEventListener('keyup', up)
      window.removeEventListener('blur', blur)
    }
  }, [enabled])
}

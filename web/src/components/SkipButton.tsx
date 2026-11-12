import { useEffect, useRef, useState } from 'react'

/**
 * "Skip Intro" and friends. Mount it keyed by segment so each segment gets
 * a fresh timer: it shows for 8 seconds, then hides until the player's
 * controls are showing again (as Jellyfin does). With `autoAfterMs` it fills
 * up over that time and skips by itself, unless clicked first.
 */
export function SkipButton({ label, controlsVisible, autoAfterMs, onSkip }: { label: string; controlsVisible: boolean; autoAfterMs?: number; onSkip: () => void }) {
  const [fresh, setFresh] = useState(true)
  const skip = useRef(onSkip)
  useEffect(() => {
    skip.current = onSkip
  }, [onSkip])
  useEffect(() => {
    const id = window.setTimeout(() => setFresh(false), 8000)
    return () => window.clearTimeout(id)
  }, [])
  useEffect(() => {
    if (!autoAfterMs) return
    const id = window.setTimeout(() => skip.current(), autoAfterMs)
    return () => window.clearTimeout(id)
  }, [autoAfterMs])
  if (!fresh && !controlsVisible && !autoAfterMs) return null
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation()
        onSkip()
      }}
      className="absolute bottom-28 right-4 z-10 min-h-[44px] overflow-hidden rounded-md border border-white/30 bg-neutral-950/80 px-5 text-sm font-semibold text-white shadow-xl backdrop-blur hover:bg-white hover:text-neutral-950"
    >
      {autoAfterMs ? (
        <span className="skip-fill absolute inset-y-0 left-0 bg-brand" style={{ animationDuration: `${autoAfterMs}ms` }} aria-hidden="true" />
      ) : null}
      <span className="relative">{label}</span>
    </button>
  )
}

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
      className="absolute bottom-[150px] right-4 z-10 inline-flex min-h-12 items-center gap-2 overflow-hidden rounded-[10px] border border-white/50 bg-ground/55 px-[22px] text-[15px] font-semibold text-white shadow-xl backdrop-blur hover:bg-white hover:text-ground md:bottom-[178px] md:right-12"
    >
      {autoAfterMs ? (
        <span className="skip-fill absolute inset-y-0 left-0 bg-brand" style={{ animationDuration: `${autoAfterMs}ms` }} aria-hidden="true" />
      ) : null}
      <span className="relative">{label}</span>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="relative">
        <path d="m6 6 6 6-6 6M13 6l6 6-6 6" />
      </svg>
    </button>
  )
}

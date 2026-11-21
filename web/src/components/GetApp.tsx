import { useEffect, useId, useRef, useState } from 'react'
import appStoreBadge from '../assets/badges/download-on-the-app-store.svg'
import googlePlayBadge from '../assets/badges/get-it-on-google-play.svg'
import { APP_STORE_URL, GOOGLE_PLAY_URL, appPromptDismissed, dismissAppPrompt, storeName, storeURL, type DeviceApp } from '../lib/getApp'

const NEW_TAB = ' (opens in a new tab)'

/**
 * The bar's Get the app button and its panel: Apple's App Store badge above
 * Google Play's, the stores' own artwork, unchanged, at the same height with
 * a quarter of it clear around each. The list leaves room for another store's
 * badge under them. Escape, a click outside or tabbing past the last badge
 * closes it; Escape puts focus back on the button.
 */
export function GetAppMenu() {
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const panel = useId()

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      setOpen(false)
      button.current?.focus()
    }
    const onPointer = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('pointerdown', onPointer)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('pointerdown', onPointer)
    }
  }, [open])

  return (
    <div
      ref={root}
      className="relative shrink-0"
      onBlur={(e) => {
        if (e.relatedTarget && !root.current?.contains(e.relatedTarget)) setOpen(false)
      }}
    >
      {/* Below lg the phone alone, named by the hidden words. */}
      <button
        ref={button}
        type="button"
        aria-expanded={open}
        aria-controls={panel}
        title="Get the app"
        onClick={() => setOpen((o) => !o)}
        className={`flex h-9 w-9 items-center justify-center gap-2 rounded-md border border-line-2 text-sm font-medium hover:text-neutral-100 lg:w-auto lg:pl-3 lg:pr-2.5 ${open ? 'bg-surface-2 text-neutral-100' : 'bg-surface text-neutral-300'}`}
      >
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0">
          <rect x="6.5" y="2.5" width="11" height="19" rx="2.5" />
          <path d="M11 18h2" />
        </svg>
        <span className="sr-only whitespace-nowrap lg:not-sr-only">Get the app</span>
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={`hidden shrink-0 text-neutral-400 lg:block ${open ? 'rotate-180' : ''}`}>
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>
      <div id={panel} hidden={!open} className="absolute right-0 top-11 z-40 w-64 rounded-xl bg-neutral-100 px-5 pb-4 pt-6 shadow-[0_12px_32px_rgba(0,0,0,.5)]">
        <ul className="flex flex-col items-center gap-4">
          <li>
            <StoreBadge href={APP_STORE_URL} src={appStoreBadge} width={144} alt="Download on the App Store" />
          </li>
          <li>
            <StoreBadge href={GOOGLE_PLAY_URL} src={googlePlayBadge} width={162} alt="Get it on Google Play" />
          </li>
        </ul>
        <p className="mt-5 border-t border-neutral-300 pt-3 text-[11px] leading-snug text-neutral-600">
          Apple and the Apple logo are trademarks of Apple Inc., registered in the U.S. and other countries. App Store is a service mark of Apple Inc.
          Google Play and the Google Play logo are trademarks of Google LLC.
        </p>
      </div>
    </div>
  )
}

function StoreBadge({ href, src, width, alt }: { href: string; src: string; width: number; alt: string }) {
  return (
    // Gold doesn't show on the light panel, so its focus ring is dark.
    <a href={href} target="_blank" rel="noopener" className="block rounded-md focus-visible:outline-2! focus-visible:outline-offset-2! focus-visible:outline-ground!">
      <img src={src} width={width} height={48} alt={alt} className="block h-12 w-auto" />
      <span className="sr-only">{NEW_TAB}</span>
    </a>
  )
}

/**
 * The device's own app, above the bar on phones and tablets, until Not now or
 * Get; the browser remembers either. Safari on an iPhone or iPad shows
 * Apple's banner instead (see appPrompt).
 */
export function AppPrompt({ app }: { app: DeviceApp }) {
  const [shown, setShown] = useState(() => !appPromptDismissed())
  if (!shown) return null
  const name = `WatchMyMedia for ${app.device}`
  const close = () => {
    dismissAppPrompt()
    setShown(false)
  }
  // The link opens the store first; the prompt goes once the click is over.
  const follow = () => {
    dismissAppPrompt()
    setTimeout(() => setShown(false))
  }
  return (
    <aside aria-label={`The ${app.device} app`} className="border-b border-line bg-surface">
      <div className="flex items-center gap-2.5 py-2.5 pl-1 pr-4 md:pl-9 md:pr-12">
        <button type="button" onClick={close} aria-label="Not now" className="grid h-11 w-10 shrink-0 place-items-center text-neutral-400 hover:text-neutral-100">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6 6 18" />
          </svg>
        </button>
        <img src="/apple-touch-icon.png" width={44} height={44} alt="" className="h-11 w-11 shrink-0 rounded-[10px]" />
        <p className="flex min-w-0 flex-1 flex-col gap-0.5 leading-tight">
          <span className="text-[15px] font-semibold text-neutral-100">{name}</span>
          <span className="text-[13px] text-neutral-400">Free on {storeName(app)}</span>
        </p>
        <a href={storeURL(app)} target="_blank" rel="noopener" onClick={follow} className="inline-flex min-h-10 shrink-0 items-center rounded-full bg-neutral-100 px-[18px] text-sm font-semibold text-ground">
          Get<span className="sr-only">{` ${name} on ${storeName(app)}${NEW_TAB}`}</span>
        </a>
      </div>
    </aside>
  )
}

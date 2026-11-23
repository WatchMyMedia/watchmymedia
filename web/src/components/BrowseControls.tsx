import type { ReactNode } from 'react'
import { ChevronDownIcon, GridIcon, ListIcon } from './icons'
import type { BrowseView } from '../lib/browsePrefs'

// The library page's controls: chips, labelled selects, Grid or List, the
// poster size and the A–Z rail.

const selClass =
  'relative inline-flex min-h-[34px] items-center gap-2 whitespace-nowrap rounded-lg border border-line-2 bg-surface pl-3 pr-2.5 text-[13px] text-neutral-300 hover:border-line-3 has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-accent max-md:min-h-10'

/** A choice drawn as the board's select: a muted label, the value in bold and a chevron, over a native select. */
export function BrowseSelect<T extends string>({
  label,
  name,
  value,
  options,
  onChange,
  className = '',
}: {
  label: string
  /** The accessible name, when it says more than the label. */
  name?: string
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
  className?: string
}) {
  const shown = options.find((o) => o.value === value)?.label ?? ''
  return (
    <label className={`${selClass} ${className}`}>
      <span className="text-neutral-400">{label}</span>
      <b aria-hidden="true" className="font-semibold text-neutral-100">
        {shown}
      </b>
      <ChevronDownIcon size={14} className="text-neutral-400" />
      <select
        aria-label={name ?? label}
        value={value}
        onChange={(e) => onChange(e.target.value as T)}
        className="absolute inset-0 cursor-pointer appearance-none rounded-lg opacity-0"
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

/** One-of chips: the pressed one is the filter in use. */
export function BrowseChips<T extends string>({ label, options, value, onChange }: { label: string; options: { value: T; label: string; count?: number }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap items-center gap-2">
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={value === o.value} onClick={() => onChange(o.value)} className="chip max-md:min-h-10">
          {o.label}
          {o.count !== undefined && <span className="n">{o.count.toLocaleString()}</span>}
        </button>
      ))}
    </div>
  )
}

/** Grid or List. */
export function BrowseViewToggle({ value, onChange }: { value: BrowseView; onChange: (v: BrowseView) => void }) {
  const item = (v: BrowseView, label: string, icon: ReactNode) => (
    <button type="button" aria-pressed={value === v} onClick={() => onChange(v)}>
      {icon}
      {label}
    </button>
  )
  return (
    <span role="group" aria-label="Show as" className="seg">
      {item('grid', 'Grid', <GridIcon size={15} />)}
      {item('list', 'List', <ListIcon size={15} />)}
    </span>
  )
}

/** The poster size, 1 to 5. */
export function BrowsePosterSize({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  return (
    <label className={`${selClass} cursor-default max-md:hidden`}>
      <span className="text-neutral-400">Poster size</span>
      <input type="range" min={1} max={5} step={1} value={value} aria-valuetext={`${value} of 5`} onChange={(e) => onChange(Number(e.target.value))} className="w-[84px] cursor-pointer accent-accent focus-visible:outline-none" />
    </label>
  )
}

const LETTERS = ['#', ...'ABCDEFGHIJKLMNOPQRSTUVWXYZ']

/** A–Z at the side of the grid, from md up: a letter with titles jumps to its first. */
export function BrowseAZRail({ available, current, onJump }: { available: ReadonlySet<string>; current?: string; onJump: (letter: string) => void }) {
  return (
    <nav aria-label="Jump to a letter" className="sticky top-[76px] hidden w-7 shrink-0 flex-col items-center gap-px text-[11.5px] font-semibold md:flex">
      {LETTERS.map((l) =>
        available.has(l) ? (
          <button
            key={l}
            type="button"
            aria-label={l === '#' ? 'Jump to titles starting with a number' : `Jump to ${l}`}
            aria-current={current === l ? 'true' : undefined}
            onClick={() => onJump(l)}
            className={`grid h-6 w-7 place-items-center rounded-md ${current === l ? 'bg-accent text-ground' : 'text-neutral-300 hover:bg-surface-2 hover:text-neutral-100'}`}
          >
            {l}
          </button>
        ) : (
          <span key={l} aria-hidden="true" className="grid h-6 w-7 place-items-center rounded-md text-dim opacity-55">
            {l}
          </span>
        ),
      )}
    </nav>
  )
}

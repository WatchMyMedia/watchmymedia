import { useState } from 'react'
import { EMPTY, RAMP } from './palette'
import { hourLabel, weekday } from './format'

// Monday first.
const ORDER = [1, 2, 3, 4, 5, 6, 0]

/**
 * Weekday × hour. More is brighter gold; an empty hour is a neutral tile.
 * Each cell shows its value on hover, or with the arrow keys once focused.
 */
export function Heatmap({ values, max, format, label }: { values: number[][]; max: number; format: (v: number) => string; label: string }) {
  const [active, setActive] = useState<[number, number] | null>(null)
  const color = (v: number) => (v <= 0 || max <= 0 ? EMPTY : RAMP[Math.min(RAMP.length - 1, Math.floor((v / max) * RAMP.length - 1e-9))])
  const move = (dr: number, dc: number) =>
    setActive((a) => {
      const [r, c] = a ?? [0, 20]
      return [Math.min(6, Math.max(0, r + dr)), Math.min(23, Math.max(0, c + dc))]
    })
  const cur = active ? values[ORDER[active[0]]]?.[active[1]] ?? 0 : 0
  return (
    <div className="space-y-3">
      <div
        className="relative outline-none focus-visible:ring-2 focus-visible:ring-accent"
        tabIndex={0}
        role="group"
        aria-label={`${label}. Use the arrow keys to read each hour.`}
        onFocus={() => setActive((a) => a ?? [0, 20])}
        onBlur={() => setActive(null)}
        onPointerLeave={() => setActive(null)}
        onKeyDown={(e) => {
          const d = ({ ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] } as Record<string, [number, number]>)[e.key]
          if (d) {
            e.preventDefault()
            move(...d)
          }
        }}
      >
        <div className="grid grid-cols-[2.75rem_repeat(24,minmax(0,1fr))] gap-[2px]" aria-hidden="true">
          {ORDER.map((d, r) => (
            <div key={d} className="contents">
              <span className="pr-1 text-left text-[11.5px] leading-none text-neutral-400 [align-self:center]">{weekday(d)}</span>
              {values[d].map((v, h) => (
                <span
                  key={h}
                  onPointerMove={() => setActive([r, h])}
                  className={`h-5 rounded-[3px] ${active?.[0] === r && active?.[1] === h ? 'ring-2 ring-neutral-100' : ''}`}
                  style={{ background: color(v) }}
                />
              ))}
            </div>
          ))}
          <span />
          {/* A label every three hours, each as wide as its three cells. */}
          {Array.from({ length: 8 }, (_, i) => (
            <span key={i} className="col-span-3 whitespace-nowrap pt-1.5 text-[11px] leading-none text-neutral-400">
              {hourLabel(i * 3)}
            </span>
          ))}
        </div>
        {active && (
          <div
            role="status"
            className="pointer-events-none absolute z-10 rounded-md border border-line-2 bg-ground/95 px-3 py-2 text-xs shadow-xl"
            style={{ top: `${((active[0] + 1) / 8) * 100}%`, ...(active[1] > 14 ? { right: `${((23 - active[1]) / 24) * 90 + 4}%` } : { left: `${(active[1] / 24) * 90 + 12}%` }) }}
          >
            <p className="text-neutral-400">
              {weekday(ORDER[active[0]], 'long')}, {hourLabel(active[1])}
            </p>
            <p className="font-semibold tabular-nums text-neutral-100">{format(cur)}</p>
          </div>
        )}
      </div>
      <div className="flex items-center gap-1.5 text-xs text-neutral-400">
        Less
        <span className="inline-block h-3 w-4 rounded-[3px]" style={{ background: EMPTY }} />
        {RAMP.map((c) => (
          <span key={c} className="inline-block h-3 w-4 rounded-[3px]" style={{ background: c }} />
        ))}
        More
      </div>
    </div>
  )
}

import { useEffect, useRef, useState } from 'react'
import { AXIS, GRID, MUTED } from './palette'
import { compact } from './format'

export type ColumnSeries = { key: string; label: string; color: string; values: number[] }

/** A clean step for about four gridlines: 1, 2, 2.5 or 5 times a power of ten. */
function niceStep(max: number): number {
  if (max <= 0) return 1
  const raw = max / 4
  const p = 10 ** Math.floor(Math.log10(raw))
  for (const m of [1, 2, 2.5, 5, 10]) if (raw <= m * p) return m * p
  return 10 * p
}

/**
 * Columns over time, stacked by series (or one series alone). Thin marks with
 * a 4px rounded data-end and a 2px surface gap between stacked segments; the
 * band under the pointer (or the arrow keys, once focused) shows every
 * series' value in a tooltip.
 */
export function StackedColumns({
  buckets,
  tick,
  title,
  series,
  format,
  height = 180,
  label,
}: {
  buckets: string[]
  /** Short axis label for a bucket. */
  tick: (b: string) => string
  /** Full name of a bucket, for the tooltip. */
  title: (b: string) => string
  series: ColumnSeries[]
  format: (v: number) => string
  height?: number
  /** Name for screen readers. */
  label: string
}) {
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(640)
  const [active, setActive] = useState<number | null>(null)
  useEffect(() => {
    const el = box.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setWidth(Math.max(200, Math.floor(e.contentRect.width))))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const n = buckets.length
  const totals = buckets.map((_, i) => series.reduce((s, x) => s + (x.values[i] ?? 0), 0))
  const max = Math.max(0, ...totals)
  const step = niceStep(max)
  const top = Math.max(step, Math.ceil(max / step) * step)
  const ticks: number[] = []
  for (let v = 0; v <= top + step / 2; v += step) ticks.push(v)

  const m = { l: 40, r: 8, t: 18, b: 24 }
  const plotW = width - m.l - m.r
  const plotH = height
  const band = n ? plotW / n : plotW
  const colW = Math.min(24, Math.max(1, Math.min(band * 0.72, band - 2)))
  const y = (v: number) => m.t + plotH - (v / top) * plotH
  const every = Math.max(1, Math.ceil(64 / band))
  const single = series.length === 1
  const peak = single && max > 0 ? totals.indexOf(max) : -1

  const move = (d: number) => setActive((a) => Math.min(n - 1, Math.max(0, (a ?? n - 1) + d)))

  return (
    <div className="space-y-2">
      {!single && (
        <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
          {series.map((s) => (
            <li key={s.key} className="flex items-center gap-1.5 text-neutral-300">
              <span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ background: s.color }} />
              {s.label}
              <span className="text-neutral-500">{format(s.values.reduce((a, b) => a + b, 0))}</span>
            </li>
          ))}
        </ul>
      )}
      <div
        ref={box}
        className="relative outline-none focus-visible:ring-2 focus-visible:ring-accent"
        tabIndex={0}
        role="group"
        aria-label={`${label}. Use the left and right arrow keys to read each column.`}
        onFocus={() => setActive((a) => a ?? n - 1)}
        onBlur={() => setActive(null)}
        onKeyDown={(e) => {
          const d = { ArrowLeft: -1, ArrowRight: 1, Home: -n, End: n }[e.key]
          if (d) {
            e.preventDefault()
            move(d)
          }
        }}
        onPointerLeave={() => setActive(null)}
      >
        <svg width={width} height={m.t + plotH + m.b} className="block" aria-hidden="true">
          {ticks.map((v) => (
            <g key={v}>
              <line x1={m.l} x2={width - m.r} y1={y(v)} y2={y(v)} stroke={v === 0 ? AXIS : GRID} strokeWidth={1} shapeRendering="crispEdges" />
              <text x={m.l - 8} y={y(v)} dy="0.32em" textAnchor="end" fontSize={11} fill={MUTED} className="tabular-nums">
                {compact(v)}
              </text>
            </g>
          ))}
          {active !== null && <rect x={m.l + active * band} y={m.t} width={band} height={plotH} fill="white" opacity={0.05} />}
          {buckets.map((b, i) => {
            const x = m.l + i * band + (band - colW) / 2
            let base = 0
            const segs: React.ReactNode[] = []
            const last = series.map((s) => (s.values[i] ?? 0) > 0).lastIndexOf(true)
            series.forEach((s, k) => {
              const v = s.values[i] ?? 0
              if (v <= 0) return
              const y0 = y(base)
              const y1 = y(base + v)
              // The gap is cut from the segment above, so stacks keep their height.
              const bottom = base > 0 ? y0 - 2 : y0
              base += v
              const h = bottom - y1
              if (h < 0.5) return
              if (k === last) {
                const r = Math.min(4, colW / 2, h)
                segs.push(
                  <path
                    key={s.key}
                    d={`M${x},${bottom} L${x},${y1 + r} Q${x},${y1} ${x + r},${y1} L${x + colW - r},${y1} Q${x + colW},${y1} ${x + colW},${y1 + r} L${x + colW},${bottom} Z`}
                    fill={s.color}
                  />,
                )
              } else segs.push(<rect key={s.key} x={x} y={y1} width={colW} height={h} fill={s.color} />)
            })
            const show = (n - 1 - i) % every === 0
            return (
              <g key={b}>
                {segs}
                {i === peak && (
                  <text x={x + colW / 2} y={y(max) - 5} textAnchor="middle" fontSize={11} fill="#c3c2b7">
                    {format(max)}
                  </text>
                )}
                {show && (
                  <text
                    x={i === 0 && every > 1 ? m.l : i === n - 1 && n > 1 ? width - m.r : x + colW / 2}
                    y={m.t + plotH + 16}
                    textAnchor={i === 0 && every > 1 ? 'start' : i === n - 1 && n > 1 ? 'end' : 'middle'}
                    fontSize={11}
                    fill={MUTED}
                  >
                    {tick(b)}
                  </text>
                )}
                <rect x={m.l + i * band} y={m.t} width={band} height={plotH + m.b} fill="transparent" onPointerMove={() => setActive(i)} />
              </g>
            )
          })}
        </svg>
        {active !== null && n > 0 && (
          <Tooltip
            left={m.l + (active + 0.5) * band}
            width={width}
            title={title(buckets[active])}
            rows={series.map((s) => ({ color: s.color, label: s.label, value: format(s.values[active] ?? 0) }))}
            total={single ? undefined : format(totals[active])}
          />
        )}
      </div>
    </div>
  )
}

function Tooltip({ left, width, title, rows, total }: { left: number; width: number; title: string; rows: { color: string; label: string; value: string }[]; total?: string }) {
  const flip = left > width - 180
  return (
    <div
      className="pointer-events-none absolute top-1 z-10 min-w-36 rounded-md border border-line-2 bg-ground/95 px-3 py-2 text-xs shadow-xl"
      style={flip ? { right: width - left + 12 } : { left: left + 12 }}
      role="status"
    >
      <p className="mb-1 text-neutral-400">{title}</p>
      {rows.map((r) => (
        <p key={r.label} className="flex items-center gap-2">
          <span className="inline-block h-0.5 w-3 rounded" style={{ background: r.color }} />
          <span className="font-semibold tabular-nums text-neutral-100">{r.value}</span>
          <span className="text-neutral-400">{r.label}</span>
        </p>
      ))}
      {total && (
        <p className="mt-1 border-t border-line-2 pt-1 text-neutral-400">
          <span className="font-semibold tabular-nums text-neutral-100">{total}</span> in all
        </p>
      )}
    </div>
  )
}

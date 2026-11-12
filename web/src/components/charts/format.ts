// 1 January 2023 was a Sunday: day d of the week is 1 + d January.
export const weekday = (d: number, style: 'short' | 'long' = 'short') => new Date(2023, 0, 1 + d).toLocaleDateString(undefined, { weekday: style })

export const hourLabel = (h: number) => new Date(2000, 0, 1, h).toLocaleTimeString(undefined, { hour: 'numeric' })

/** 0, 950, 1.2K, 12K. */
export function compact(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(n >= 10_000 ? 0 : 1).replace(/\.0$/, '')}K` : String(Math.round(n * 10) / 10)
}

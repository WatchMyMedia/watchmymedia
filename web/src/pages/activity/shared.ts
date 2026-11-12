import type { HistoryFilters } from '../../api/activity'
import { useAuth } from '../../authContext'

/** True for an admin, who sees everyone's activity from any network. */
export function useActivityAdmin(): boolean {
  const auth = useAuth()
  return auth.user?.isAdmin ?? false
}

/** True for an admin on an admin network, who can also stop plays, delete history and revoke downloads. */
export function useActivityManage(): boolean {
  const auth = useAuth()
  return (auth.user?.isAdmin ?? false) && auth.adminAllowed
}

/** History filters as the URL holds them; days is a preset counted back from today. */
export type HistorySearch = HistoryFilters & { days?: number }

export const num = (v: unknown) => (typeof v === 'number' ? v : typeof v === 'string' && v !== '' && !Number.isNaN(Number(v)) ? Number(v) : undefined)
export const str = (v: unknown) => (typeof v === 'string' && v !== '' ? v : undefined)
export const oneOf = <T extends string>(v: unknown, xs: readonly T[]) => (xs.includes(v as T) ? (v as T) : undefined)

export function validateHistorySearch(s: Record<string, unknown>): HistorySearch {
  return {
    q: str(s.q),
    user: num(s.user),
    device: num(s.device),
    type: oneOf(s.type, ['movie', 'episode', 'file'] as const),
    mode: oneOf(s.mode, ['direct', 'remux', 'audio', 'transcode'] as const),
    network: oneOf(s.network, ['local', 'tailnet', 'remote'] as const),
    from: str(s.from),
    to: str(s.to),
    days: num(s.days),
    completed: oneOf(s.completed, ['true', 'false'] as const),
    movie: num(s.movie),
    show: num(s.show),
    episode: num(s.episode),
    grouped: s.grouped === false || s.grouped === 'false' ? false : undefined,
  }
}

/** A local calendar day, offset from today. */
export function day(offset: number): string {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function toFilters(s: HistorySearch): HistoryFilters {
  const { days, ...rest } = s
  return days ? { ...rest, from: day(-(days - 1)), to: undefined } : rest
}

/** "21:04" today, "Mon 21:04" this week, else "28 Oct 2026, 21:04". */
export function when(iso: string): string {
  const d = new Date(iso)
  const now = new Date()
  const time = d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  if (d.toDateString() === now.toDateString()) return `Today ${time}`
  if (now.getTime() - d.getTime() < 6 * 86_400_000) return `${d.toLocaleDateString(undefined, { weekday: 'short' })} ${time}`
  return `${d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: d.getFullYear() === now.getFullYear() ? undefined : 'numeric' })}, ${time}`
}

export type StatsSearch = { days?: number; by?: 'time'; user?: number }

export function validateStatsSearch(s: Record<string, unknown>): StatsSearch {
  const days = Number(s.days)
  const user = Number(s.user)
  return {
    days: [7, 30, 90, 365, 0].includes(days) && s.days !== undefined && s.days !== '' ? days : undefined,
    by: s.by === 'time' ? 'time' : undefined,
    user: Number.isFinite(user) && user > 0 ? user : undefined,
  }
}


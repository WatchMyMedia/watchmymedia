export function formatBytes(n: number): string {
  if (!n) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(Math.floor(Math.log(n) / Math.log(1024)), units.length - 1)
  const v = n / 1024 ** i
  return `${v >= 10 || i === 0 ? v.toFixed(0) : v.toFixed(1)} ${units[i]}`
}

export function formatDuration(ms: number): string {
  if (!ms) return ''
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`
}

/** "2 h 16 min" from minutes. */
export function formatRuntime(min: number): string {
  if (!min) return ''
  const h = Math.floor(min / 60)
  const m = min % 60
  return h > 0 ? `${h} h${m ? ` ${m} min` : ''}` : `${m} min`
}

export function relativeTime(iso: string | undefined | null): string {
  if (!iso) return 'never'
  const diff = Date.now() - new Date(iso).getTime()
  if (diff < 0) {
    const d = Math.round(-diff / 86_400_000)
    return d <= 1 ? 'tomorrow' : `in ${d} days`
  }
  const s = Math.round(diff / 1000)
  if (s < 60) return 'just now'
  const m = Math.round(s / 60)
  if (m < 60) return `${m} min ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h} h ago`
  const d = Math.round(h / 24)
  if (d === 1) return 'yesterday'
  if (d < 30) return `${d} days ago`
  const mo = Math.round(d / 30)
  if (mo < 12) return `${mo} mo ago`
  return `${Math.round(d / 365)} y ago`
}

/** "1 season", "3 seasons". */
export function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? '' : 's'}`
}

export function formatDate(iso: string | undefined): string {
  if (!iso) return ''
  // A bare date ("2024-10-29", as TMDB air dates are) is a calendar day, not
  // UTC midnight, which would show as the day before west of Greenwich.
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  const d = day ? new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3])) : new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

export function resolutionLabel(width?: number, height?: number): string {
  if (!width || !height) return ''
  if (height >= 2000 || width >= 3800) return '4K'
  if (height >= 1000 || width >= 1900) return '1080p'
  if (height >= 700 || width >= 1200) return '720p'
  return 'SD'
}

export function episodeCode(season?: number | null, episodes?: number[] | null): string {
  if (season == null || !episodes?.length) return ''
  const s = `S${String(season).padStart(2, '0')}`
  const eps = episodes.map((e) => `E${String(e).padStart(2, '0')}`)
  return eps.length > 1 ? `${s}${eps[0]}-${eps[eps.length - 1]}` : `${s}${eps[0]}`
}

export function seCode(season: number, episode: number): string {
  return `S${String(season).padStart(2, '0')}E${String(episode).padStart(2, '0')}`
}

/** File extension in upper case ("MKV"), which reads better than ffprobe's container family ("matroska", "mov"). */
export function fileType(relativePath: string): string {
  const ext = relativePath.split('.').pop() ?? ''
  return ext.length <= 5 ? ext.toUpperCase() : ''
}

const languageNames = (() => {
  try {
    return new Intl.DisplayNames(undefined, { type: 'language' })
  } catch {
    return null
  }
})()

/** "English", "Portuguese (Brazil)" from en, pt-br or eng; the code itself when unknown. */
export function languageName(code?: string): string {
  if (!code) return 'Unknown language'
  try {
    return languageNames?.of(code) ?? code.toUpperCase()
  } catch {
    return code.toUpperCase()
  }
}

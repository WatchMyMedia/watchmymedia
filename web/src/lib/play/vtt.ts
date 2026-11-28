/**
 * A cue, ready to draw: plain lines (WebVTT's tags taken out, entities
 * read), and whether the file placed it at the top of the picture.
 */
export type Cue = { id: number; startMs: number; endMs: number; lines: string[]; top: boolean }

const TIME = /^(?:(\d+):)?([0-5]\d):([0-5]\d)[.,](\d{3})$/

/** "01:02:03.456" or "02:03.456" in milliseconds; undefined when it isn't a timestamp. */
export function vttTime(text: string): number | undefined {
  const m = TIME.exec(text.trim())
  if (!m) return undefined
  return ((Number(m[1] ?? 0) * 60 + Number(m[2])) * 60 + Number(m[3])) * 1000 + Number(m[4])
}

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', nbsp: '\u00a0', lrm: '\u200e', rlm: '\u200f', quot: '"', apos: '\'' }

/** A cue's text without its markup: <i>, <b>, <c.yellow>, <v Narrator>, <ruby> and inline timestamps. */
export function cueText(text: string): string {
  return text
    .replace(/<[^>]*>/g, '')
    .replace(/&(#\d+|#x[0-9a-f]+|[a-z]+);/gi, (whole, name: string) => {
      if (name[0] === '#') {
        const code = name[1] === 'x' || name[1] === 'X' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10)
        return Number.isFinite(code) && code > 0 && code < 0x110000 ? String.fromCodePoint(code) : whole
      }
      return ENTITIES[name.toLowerCase()] ?? whole
    })
}

/** Whether the cue's settings put it in the top half: line:0 to line:4, or a percentage under 50. */
function placedAtTop(settings: string): boolean {
  const line = /(?:^|\s)line:(-?\d+(?:\.\d+)?)(%)?/.exec(settings)
  if (!line) return false
  const value = Number(line[1])
  return line[2] ? value < 50 : value >= 0 && value < 5
}

/**
 * The cues of a WebVTT file, by start time. NOTE, STYLE and REGION blocks
 * and cues that can't be read are skipped, never fatal: a bad line loses
 * only its cue.
 */
export function parseVtt(text: string): Cue[] {
  const cues: Cue[] = []
  const blocks = text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n').split(/\n{2,}/)
  for (const block of blocks) {
    const lines = block.split('\n')
    const at = lines.findIndex((line) => line.includes('-->'))
    if (at < 0 || at > 1) continue
    const [from, rest = ''] = lines[at].split('-->')
    const [to, ...settings] = rest.trim().split(/\s+/)
    const startMs = vttTime(from)
    const endMs = vttTime(to ?? '')
    if (startMs === undefined || endMs === undefined || endMs <= startMs) continue
    const body = lines.slice(at + 1).map(cueText).filter((line) => line.trim() !== '')
    if (!body.length) continue
    cues.push({ id: cues.length, startMs, endMs, lines: body, top: placedAtTop(settings.join(' ')) })
  }
  cues.sort((a, b) => a.startMs - b.startMs || a.id - b.id)
  return cues.map((cue, id) => ({ ...cue, id }))
}

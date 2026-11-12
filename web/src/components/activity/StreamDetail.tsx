import type { MediaSide } from '../../api/activity'
import { MODE_LABELS, NETWORK_LABELS, formatRate } from '../../api/activity'
import { resolutionLabel } from '../../lib/format'

const CHANNELS: Record<number, string> = { 1: 'Mono', 2: 'Stereo', 6: '5.1', 8: '7.1' }

function video(m: MediaSide): string {
  return [m.videoCodec?.toUpperCase(), resolutionLabel(m.width, m.height) || (m.height ? `${m.height}p` : ''), m.hdr?.toUpperCase(), m.bitrate ? formatRate(m.bitrate) : '']
    .filter(Boolean)
    .join(' · ')
}

function audio(m: MediaSide): string {
  return [m.audioCodec?.toUpperCase(), m.audioChannels ? (CHANNELS[m.audioChannels] ?? `${m.audioChannels} ch`) : '', m.audioLanguage?.toUpperCase()].filter(Boolean).join(' · ')
}

function decided(kind: string | undefined, text: string): string {
  if (kind === 'copy') return text ? `${text} (copied)` : 'Copied'
  return text
}

/** Source → stream, row by row: what the file is and what the client got. */
export function StreamDetail({
  source,
  stream,
  reasons,
  extra = [],
}: {
  source: MediaSide
  stream: MediaSide
  reasons: string[]
  extra?: [string, string][]
}) {
  const subtitle = stream.subtitleMode === 'burn' ? `${source.subtitle ?? ''} burned in`.trim() : stream.subtitleMode === 'text' ? source.subtitle || 'On' : 'Off'
  const rows: [string, string, string][] = [
    ['Container', source.container?.toUpperCase() ?? '', stream.container === 'hls' ? 'HLS (fMP4)' : (stream.container?.toUpperCase() ?? '')],
    ['Video', video(source), decided(stream.videoDecision, stream.videoDecision === 'copy' ? '' : video(stream))],
    ['Audio', audio(source), decided(stream.audioDecision, stream.audioDecision === 'copy' ? '' : audio(stream))],
    ['Subtitles', source.subtitle ?? '', subtitle],
  ]
  return (
    <div className="space-y-3 text-sm">
      <table className="w-full table-fixed border-collapse">
        <thead>
          <tr className="text-left text-xs uppercase tracking-wider text-neutral-500">
            <th className="w-24 pb-1 font-medium" />
            <th className="pb-1 font-medium">Source</th>
            <th className="pb-1 font-medium">Stream</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([k, a, b]) => (
            <tr key={k} className="align-top">
              <th scope="row" className="py-1 pr-2 text-left font-normal text-neutral-500">
                {k}
              </th>
              <td className="break-words py-1 pr-2 text-neutral-300">{a || '—'}</td>
              <td className="break-words py-1 text-neutral-100">{b || '—'}</td>
            </tr>
          ))}
          {extra.map(([k, v]) => (
            <tr key={k} className="align-top">
              <th scope="row" className="py-1 pr-2 text-left font-normal text-neutral-500">
                {k}
              </th>
              <td colSpan={2} className="break-words py-1 text-neutral-200">
                {v}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {reasons.length > 0 && (
        <div>
          <p className="text-xs uppercase tracking-wider text-neutral-500">Why not direct play</p>
          <ul className="mt-1 list-disc space-y-0.5 pl-5 text-neutral-300">
            {reasons.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

const MODE_TONES: Record<string, string> = {
  direct: 'border-emerald-700/60 text-emerald-300',
  remux: 'border-sky-700/60 text-sky-300',
  audio: 'border-amber-700/60 text-amber-300',
  transcode: 'border-warn/60 text-warn',
}

/** How a play streams; the reasons a cheaper mode did not work show on hover. */
export function ModeChip({ mode, reasons = [] }: { mode: string; reasons?: string[] }) {
  return (
    <span title={reasons.join('; ') || undefined} className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium ${MODE_TONES[mode] ?? 'border-line-2 text-neutral-300'}`}>
      {MODE_LABELS[mode] ?? mode}
    </span>
  )
}

export function NetworkChip({ network }: { network: string }) {
  return (
    <span className="inline-flex items-center rounded-full border border-line-2 px-2 py-0.5 text-xs text-neutral-300">{NETWORK_LABELS[network] ?? network}</span>
  )
}

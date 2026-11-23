import type { ReactNode } from 'react'
import type { MediaSide } from '../../api/activity'
import { MODE_LABELS, NETWORK_LABELS } from '../../api/activity'
import { languageName, resolutionLabel } from '../../lib/format'

const CHANNELS: Record<number, string> = { 1: 'Mono', 2: 'Stereo', 6: '5.1', 8: '7.1' }

function video(m: MediaSide): string {
  return [m.videoCodec?.toUpperCase(), resolutionLabel(m.width, m.height) || (m.height ? `${m.height}p` : ''), m.hdr?.toUpperCase()].filter(Boolean).join(' · ')
}

function audio(m: MediaSide): string {
  return [m.audioCodec?.toUpperCase(), m.audioChannels ? (CHANNELS[m.audioChannels] ?? `${m.audioChannels} ch`) : '', m.audioLanguage ? languageName(m.audioLanguage) : ''].filter(Boolean).join(' · ')
}

function sent(kind: string | undefined, text: string): string {
  return kind === 'copy' ? 'As it is' : text
}

/** The file beside what was sent, part by part: "HEVC · 4K · HDR10" and "As it is". */
export function StreamTable({ source, stream }: { source: MediaSide; stream: MediaSide }) {
  const subtitles = stream.subtitleMode === 'burn' ? 'Drawn into the picture' : stream.subtitleMode === 'text' ? 'Drawn by the player' : 'Off'
  const rows: [string, string, string][] = [
    ['Container', source.container?.toUpperCase() ?? '', stream.container === 'hls' ? 'HLS · fMP4' : stream.container && stream.container === source.container ? 'As it is' : (stream.container?.toUpperCase() ?? '')],
    ['Video', video(source), sent(stream.videoDecision, video(stream))],
    ['Sound', audio(source), sent(stream.audioDecision, audio(stream))],
  ]
  if (source.subtitle || stream.subtitleMode) rows.push(['Subtitles', source.subtitle ?? '', subtitles])
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-[13px]">
        <thead>
          <tr>
            <th scope="col" className="pb-1.5 pr-2.5 text-left">
              <span className="sr-only">Part</span>
            </th>
            <th scope="col" className="pb-1.5 pr-2.5 text-left text-[11.5px] font-semibold uppercase tracking-[.04em] text-dim">
              In the file
            </th>
            <th scope="col" className="pb-1.5 pr-2.5 text-left text-[11.5px] font-semibold uppercase tracking-[.04em] text-dim">
              Sent
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([k, a, b]) => (
            <tr key={k} className="border-t border-line align-top">
              <th scope="row" className="whitespace-nowrap py-1.5 pr-2.5 text-left font-normal text-neutral-400">
                {k}
              </th>
              <td className="break-words py-1.5 pr-2.5 text-neutral-300">{a || '—'}</td>
              <td className="break-words py-1.5 pr-2.5 text-neutral-300">{b || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Label and value pairs under the stream's table: the rate, the encoder, the address. */
export function StreamFacts({ facts }: { facts: [string, ReactNode, string?][] }) {
  if (!facts.length) return null
  return (
    <dl className="dl mt-3.5 text-[13px]">
      {facts.map(([k, v, cls]) => (
        <div key={k} className="contents">
          <dt>{k}</dt>
          <dd className={`break-words ${cls ?? ''}`}>{v}</dd>
        </div>
      ))}
    </dl>
  )
}

/** The server's reasons, as it gave them, for the curious. */
export function StreamReasons({ reasons }: { reasons: string[] }) {
  if (!reasons.length) return null
  return (
    <div className="mt-3.5 text-[13px]">
      <p className="text-[11.5px] font-semibold uppercase tracking-[.04em] text-dim">Why not the original</p>
      <ul className="mt-1 list-disc space-y-0.5 pl-5 text-neutral-300">
        {reasons.map((r) => (
          <li key={r}>{r}</li>
        ))}
      </ul>
    </div>
  )
}

/** How a play streams, with the reasons a cheaper mode did not work on hover. */
export function ModeChip({ mode, reasons = [] }: { mode: string; reasons?: string[] }) {
  return (
    <span title={reasons.join('; ') || undefined} className={`pill ${mode === 'transcode' ? 'pill-warn' : ''}`}>
      {MODE_LABELS[mode] ?? mode}
    </span>
  )
}

export function NetworkChip({ network }: { network: string }) {
  return <span className="pill pill-line">{NETWORK_LABELS[network] ?? network}</span>
}

/** A person's initial on a burgundy disc. */
export function Avatar({ name, size = 'sm' }: { name: string; size?: 'sm' | 'md' | 'lg' | 'xl' }) {
  const box = { sm: 'size-[26px] text-[11.5px]', md: 'size-9 text-sm', lg: 'size-14 text-xl', xl: 'size-16 text-2xl' }[size]
  return (
    <span aria-hidden="true" className={`inline-grid shrink-0 place-items-center rounded-full bg-brand font-semibold text-white ring-1 ring-inset ring-white/20 ${box}`}>
      {name.charAt(0).toUpperCase()}
    </span>
  )
}

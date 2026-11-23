import type { ReactNode } from 'react'
import type { components } from '../api/schema'
import { knownLanguage, languageName } from '../lib/format'
import { filesSummary, planWords, sizeAndRate, soundLabel, soundOptions, subtitleOptions, versionName, versionTags, type FileFacts } from '../lib/titlePage'
import { RecordingProvenance } from './liveTV/RecordingProvenance'
import { ChoiceSelect } from './TitleParts'

type MediaInfo = components['schemas']['MediaInfo']
type PlaybackPlan = components['schemas']['PlaybackPlan']
type MediaFile = FileFacts & { id: number; recording?: components['schemas']['LiveTVProvenance'] }

/** What was chosen before playing: a sound stream index, and "" (your settings), "off" or a track id. */
export type TrackChoice = { audio: string; subtitle: string }

/**
 * Sound and Subtitles, chosen before playing, in plain words. Left alone,
 * the server picks by the person's language settings, as it always has; a
 * single sound track has nothing to choose.
 */
export function TrackChoices({ probe, choice, onChange }: { probe?: Pick<MediaInfo, 'audio' | 'subtitles'>; choice: TrackChoice; onChange: (c: TrackChoice) => void }) {
  const sound = soundOptions(probe)
  const subs = subtitleOptions(probe)
  return (
    <>
      {sound.length > 1 && (
        <ChoiceSelect
          label="Sound"
          value={choice.audio}
          onChange={(audio) => onChange({ ...choice, audio })}
          options={[{ value: '', label: 'Automatic' }, ...sound.map((s) => ({ value: String(s.index), label: s.label }))]}
        />
      )}
      {subs.length > 0 && (
        <ChoiceSelect
          label="Subtitles"
          value={choice.subtitle}
          onChange={(subtitle) => onChange({ ...choice, subtitle })}
          options={[{ value: '', label: 'Automatic' }, { value: 'off', label: 'Off' }, ...subs.map((s) => ({ value: s.id, label: s.image ? `${s.label} (converts the video)` : s.label }))]}
        />
      )}
    </>
  )
}

/** A file's tracks, sound beside subtitles, for File details. */
function Tracks({ probe }: { probe?: Pick<MediaInfo, 'audio' | 'subtitles'> }) {
  if (!probe) return null
  const sound = soundOptions(probe)
  const subs = subtitleOptions(probe)
  return (
    <div className="grid grid-cols-[repeat(auto-fit,minmax(min(320px,100%),1fr))] gap-x-8 gap-y-5">
      <section aria-label="Sound">
        <h3 className="mb-1.5 text-sm font-semibold">Sound</h3>
        {sound.length ? sound.map((s) => <Track key={s.index} name={s.label} detail={s.detail} badge={s.default ? <span className="pill">Default</span> : null} />) : <p className="text-[13.5px] text-neutral-400">No sound track</p>}
      </section>
      <section aria-label="Subtitles">
        <h3 className="mb-1.5 text-sm font-semibold">Subtitles</h3>
        {subs.length ? (
          subs.map((s) => <Track key={s.id} name={s.label} detail={s.detail} badge={s.image ? <span className="pill pill-warn">Turning it on converts the video</span> : null} />)
        ) : (
          <p className="text-[13.5px] text-neutral-400">None in the file</p>
        )}
      </section>
    </div>
  )
}

function Track({ name, detail, badge }: { name: string; detail: string; badge: ReactNode }) {
  return (
    <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1 border-t border-line py-[9px] text-[13.5px] first-of-type:border-t-0">
      <b className="font-semibold">{name}</b>
      <span className="text-neutral-400">{detail}</span>
      {badge && <span className="ml-auto">{badge}</span>}
    </div>
  )
}

/**
 * A movie's File details: closed until asked for. Each version with how it
 * plays here, its tags, size and rate (and, for admins, where it is), Play
 * this; then the chosen version's sound and subtitles.
 */
export function MovieFileDetails({
  files,
  selectedId,
  plans,
  admin,
  open,
  onToggle,
  playThis,
}: {
  files: MediaFile[]
  selectedId?: number
  plans: Map<number, PlaybackPlan>
  admin: boolean
  open: boolean
  onToggle: (open: boolean) => void
  playThis: (file: MediaFile) => ReactNode
}) {
  if (!files.length) return null
  const selected = files.find((f) => f.id === selectedId) ?? files[0]
  return (
    <details id="file-details" className="tech scroll-mt-20" open={open} onToggle={(e) => onToggle(e.currentTarget.open)}>
      <summary>
        File details<span className="hint truncate max-sm:hidden">{filesSummary(files)}</span>
      </summary>
      <div className="tech-body flex flex-col gap-[22px]">
        <section aria-label="Versions" className="flex flex-col gap-2.5">
          <h3 className="text-sm font-semibold">{files.length === 1 ? 'Version' : 'Versions'}</h3>
          {files.map((f, i) => {
            const words = planWords(plans.get(f.id), f)
            return (
              <div key={f.id} className={`flex flex-wrap items-center gap-x-4 gap-y-2.5 rounded-[10px] border bg-ground px-4 py-3.5 ${f.id === selected.id && files.length > 1 ? 'border-accent' : 'border-line'}`}>
                <span className="min-w-0 flex-[1_1_260px]">
                  <span className="flex flex-wrap items-center gap-2 font-semibold">
                    {versionName(f)}
                    {i === 0 && files.length > 1 && <span className="pill pill-gold">Plays by default</span>}
                  </span>
                  {words && <span className="mt-[3px] block text-[13px] text-neutral-400">{words}</span>}
                  {admin && <span className="mt-1.5 block font-mono text-xs [overflow-wrap:anywhere] text-dim">{f.relativePath}</span>}
                  <RecordingProvenance recording={f.recording} />
                </span>
                <span className="flex flex-wrap gap-1.5">
                  {versionTags(f).map((t) => (
                    <span key={t} className="tag">
                      {t}
                    </span>
                  ))}
                </span>
                <span className="whitespace-nowrap text-[13px] text-neutral-400">{sizeAndRate(f)}</span>
                {files.length > 1 && playThis(f)}
              </div>
            )
          })}
        </section>
        <Tracks probe={selected.probe} />
      </div>
    </details>
  )
}

/** An episode's File details, closed: its tags, how it plays here, where it is (admins), and what is in it. */
export function EpisodeFileDetails({ file, plan, admin, probe }: { file: MediaFile; plan?: PlaybackPlan; admin: boolean; probe?: Pick<MediaInfo, 'audio' | 'subtitles' | 'bitRate'> }) {
  const facts = { ...file, probe: probe ?? file.probe }
  const audio = probe?.audio.find((a) => a.default) ?? probe?.audio[0]
  const hint = [versionName(file), audio ? soundLabel(audio).split(' · ').slice(1).join(' · ') : '', sizeAndRate(facts).split(' · ')[0]].filter(Boolean)
  const words = planWords(plan, file)
  const subs = [...new Set((probe?.subtitles ?? []).filter((s) => knownLanguage(s.language)).map((s) => languageName(s.language)))]
  const sound = soundOptions(probe).map((s) => s.label)
  return (
    <details className="tech bg-ground/55">
      <summary className="min-h-11!">
        File details<span className="hint truncate">{hint.join(' · ')}</span>
      </summary>
      <div className="tech-body flex flex-col gap-2.5">
        <p className="flex flex-wrap items-center gap-1.5">
          {versionTags(facts).map((t) => (
            <span key={t} className="tag">
              {t}
            </span>
          ))}
          <span className="text-[13px] text-neutral-400">· {sizeAndRate(facts)}</span>
        </p>
        {words && <p className="text-[13px] text-neutral-400">{words}.</p>}
        {admin && <p className="font-mono text-xs [overflow-wrap:anywhere] text-dim">{file.relativePath}</p>}
        {(subs.length > 0 || sound.length > 0) && (
          <p className="text-[13px] text-neutral-400">
            {[subs.length ? `Subtitles in the file: ${subs.join(', ')}` : '', sound.length ? `Sound: ${sound.join(', ')}` : ''].filter(Boolean).join(' · ')}
          </p>
        )}
      </div>
    </details>
  )
}

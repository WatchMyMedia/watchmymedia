import { useQuery } from '@tanstack/react-query'
import { preferencesQuery, useUpdatePreferences } from '../api/queries'
import { CheckRow, Panel, Row, textInput } from './SettingsPanel'
import { useGuideExtras } from './liveTV/useGuideExtras'
import { LEAD_OPTIONS, leadMinutes } from '../lib/liveReminders'

const KINDS: { kind: string; label: string; hint: string }[] = [
  { kind: 'intro', label: 'Intro', hint: 'Opening titles and theme' },
  { kind: 'recap', label: 'Recap', hint: '"Previously on…"' },
  { kind: 'credits', label: 'Credits', hint: 'End credits; the Next Episode card takes over on the last block' },
  { kind: 'preview', label: 'Preview', hint: '"Next time on…"' },
  { kind: 'commercial', label: 'Ads', hint: 'Commercial breaks left in recordings' },
]

const MODES: { value: string; label: string }[] = [
  { value: 'none', label: 'Do nothing' },
  { value: 'ask', label: 'Show a Skip button' },
  { value: 'auto-delay', label: 'Skip after 3 seconds' },
  { value: 'auto', label: 'Skip right away' },
]

const GRID = 'grid grid-cols-[minmax(0,1.5fr)_repeat(4,minmax(0,1fr))] items-center gap-x-2 px-5'

/** Per-user playback settings: how each kind of skippable segment is handled, and autoplay. */
export function PlaybackSection() {
  const prefs = useQuery(preferencesQuery)
  // Reminders come with listings at API level 15, for someone who may watch Live TV.
  const reminders = useGuideExtras()
  const update = useUpdatePreferences()
  const p = prefs.data
  const skip = (kind: string, mode: string) => update.mutate({ skip: { [kind]: mode } })
  return (
    <div className="space-y-6">
      {prefs.isError && <p className="text-sm text-red-400">{prefs.error.message}</p>}
      {p && (
        <>
          <Panel title="Skipping" description="Pick what happens when each part of an episode starts.">
            {/* Wider screens show every choice at once; phones get a menu per part. */}
            <div className="hidden sm:block">
              <div className={`${GRID} border-t border-line py-2.5 text-xs font-medium text-neutral-400`}>
                <span>Part</span>
                {MODES.map((m) => (
                  <span key={m.value} className="text-center">
                    {m.label}
                  </span>
                ))}
              </div>
              {KINDS.map((k) => (
                <div key={k.kind} role="radiogroup" aria-labelledby={`skip-${k.kind}`} className={`${GRID} border-t border-line py-2`}>
                  <div className="py-1.5 pr-4">
                    <div id={`skip-${k.kind}`} className="font-medium">
                      {k.label}
                    </div>
                    <div className="mt-0.5 text-sm text-neutral-400">{k.hint}</div>
                  </div>
                  {MODES.map((m) => (
                    <label key={m.value} className="grid min-h-[44px] cursor-pointer place-items-center">
                      <input
                        type="radio"
                        name={`skip-${k.kind}`}
                        value={m.value}
                        checked={(p.skip[k.kind] ?? 'none') === m.value}
                        disabled={update.isPending}
                        onChange={() => skip(k.kind, m.value)}
                        className="h-[18px] w-[18px] cursor-pointer accent-accent"
                      />
                      <span className="sr-only">{m.label}</span>
                    </label>
                  ))}
                </div>
              ))}
            </div>
            <div className="border-t border-line sm:hidden">
              {KINDS.map((k) => (
                <Row key={k.kind} label={k.label} hint={k.hint} htmlFor={`skip-select-${k.kind}`}>
                  <select
                    id={`skip-select-${k.kind}`}
                    value={p.skip[k.kind] ?? 'none'}
                    disabled={update.isPending}
                    onChange={(e) => skip(k.kind, e.target.value)}
                    className={`${textInput} w-full`}
                  >
                    {MODES.map((m) => (
                      <option key={m.value} value={m.value}>
                        {m.label}
                      </option>
                    ))}
                  </select>
                </Row>
              ))}
            </div>
          </Panel>
          <Panel title="Next episode">
            <CheckRow
              label="Play the next episode by itself"
              hint="The Next Episode card counts down 10 seconds from the credits. Off, it waits for you."
              checked={p.autoplayNext}
              disabled={update.isPending}
              onChange={(on) => update.mutate({ autoplayNext: on })}
            />
          </Panel>
          {/* Last on the page (the LiveReminder board's settings state). */}
          {reminders && (
            <Panel title="Live TV reminders" description="When a programme you asked to be reminded of is about to start, on every device you use.">
              <Row label="Remind me" htmlFor="live-reminder-lead" hint="Reminders show while WatchMyMedia is open in a tab. A reminder set on another device reaches this browser the next time WatchMyMedia opens here.">
                <select
                  id="live-reminder-lead"
                  value={leadMinutes(p.liveTVReminderMinutes)}
                  disabled={update.isPending}
                  onChange={(e) => update.mutate({ liveTVReminderMinutes: Number(e.target.value) })}
                  className={`${textInput} w-full sm:w-64`}
                >
                  {LEAD_OPTIONS.map((o) => (
                    <option key={o.minutes} value={o.minutes}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </Row>
            </Panel>
          )}
        </>
      )}
      {update.isError && <p className="text-sm text-red-400">{update.error.message}</p>}
    </div>
  )
}

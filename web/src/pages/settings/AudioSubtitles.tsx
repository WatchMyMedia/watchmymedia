import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { settingsQuery, useUpdateSettings } from '../../api/settings'
import { PageHeader, Panel, Row, primaryButton, textInput } from '../../components/SettingsPanel'
import { OpenSubtitlesSection } from '../../components/SettingsSections'
import { ServerOnly } from './SettingsLayout'
import { useSettingsAccess } from './shared'
import { LanguagePreferencesPanel } from '../../components/LanguagePreferencesPanel'

/** Settings › Audio & subtitles (PLAN-LANGUAGES.md §5.6): the person's audio and subtitle
 * languages and how subtitles start; for admins, the OpenSubtitles account downloads count
 * against and who may download. The route stays /settings/subtitles, so links keep working. */
export function AudioSubtitlesSettings() {
  const { admin, adminLimit } = useSettingsAccess()
  return (
    <>
      <PageHeader title="Audio & subtitles">
        Which audio and subtitles a video starts with. Your choices follow your account on every device.
      </PageHeader>
      <LanguagePreferencesPanel />
      {(admin || adminLimit) && <div className="mt-10 space-y-4"><h2 className="text-lg font-semibold">Server subtitle service</h2><p className="text-sm text-neutral-400">The shared OpenSubtitles account and who can download. Only admins can change these.</p><ServerOnly><SubtitlesSection /></ServerOnly></div>}
    </>
  )
}

function SubtitlesSection() {
  const settings = useQuery(settingsQuery)
  const update = useUpdateSettings()
  const s = settings.data?.subtitles
  const [languages, setLanguages] = useState<string | null>(null)
  if (!s) return null
  if (!s.available) return <OpenSubtitlesSection />
  return (
    <div className="space-y-6">
      <OpenSubtitlesSection />

      <Panel>
        <Row label="Languages" hint="Searched in this order, as two-letter codes separated by commas (pt-br and zh-tw for regional variants)." htmlFor="subtitle-languages">
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              if (languages === null) return
              update.mutate({ subtitles: { languages: languages.split(',') } }, { onSuccess: () => setLanguages(null) })
            }}
          >
            <input
              id="subtitle-languages"
              value={languages ?? s.languages.join(', ')}
              onChange={(e) => setLanguages(e.target.value)}
              className={`${textInput} w-48 font-mono`}
              autoComplete="off"
            />
            <button type="submit" disabled={languages === null || update.isPending} className={primaryButton}>
              Save
            </button>
          </form>
        </Row>
        <Row label="Who can download" hint="Everyone on this server can pick an online subtitle, or only admins can. Searching is free either way." htmlFor="subtitle-downloads">
          <select
            id="subtitle-downloads"
            value={s.downloads}
            onChange={(e) => update.mutate({ subtitles: { downloads: e.target.value as 'everyone' | 'admins' } })}
            className={`${textInput} px-2`}
          >
            <option value="everyone">Everyone</option>
            <option value="admins">Admins only</option>
          </select>
        </Row>
      </Panel>
      <p className="max-w-2xl text-sm text-neutral-400">
        Subtitles provided by{' '}
        <a href="https://opensubtitles.com" target="_blank" rel="noreferrer" className="text-neutral-200 underline">
          OpenSubtitles.com
        </a>
        . Downloads are kept on this server and included in its backups, so picking one again costs nothing.
      </p>
    </div>
  )
}

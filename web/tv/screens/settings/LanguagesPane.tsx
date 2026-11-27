import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useFocus, useSession } from '../../app/hooks'
import { from35 } from '../../session/features'
import { SizeSection, SoundSection } from './SoundAndSize'
import { preferencesQuery } from '../../../src/api/queries'
import { languageName } from '../../../src/lib/format'
import { automaticAvailable, draftOf, emptySubtitleText, languagePatch, MAX_LANGUAGES, subtitleChoices, type LanguageDraft } from '../../../src/lib/languagePreferences'
import { automaticReason } from '../../../src/lib/youSettings'
import { AccountNotLoaded, ChoicePanel } from './Panels'
import { usePreferenceSaves } from './saves'
import type { PaneProps } from './SettingsScreen'
import { paneRowId } from './settingsGroups'
import { PaneHeading, PaneNote, SettingRow } from './SettingRow'
import { choiceOf, startSubtitlesLine } from './settingsWords'
import { actOn, addable, addTo, languageActionLabel, languageActions, LIST_TITLES, type LanguageListKind } from './languages'

type Panel = { kind: 'language'; list: LanguageListKind; tag: string } | { kind: 'add'; list: LanguageListKind } | { kind: 'start' }

const id = (row: string) => paneRowId('languages', row)

/**
 * Settings › Audio & subtitles:
 * the account's audio languages, its sound (from API level 35), how
 * subtitles start, its subtitle languages and size (from 35) and Automatic
 * download, each change saved at once.
 */
export function LanguagesPane({ first }: PaneProps) {
  const focus = useFocus()
  const session = useSession()
  const level35 = from35(session.server.apiLevel)
  const prefs = useQuery(preferencesQuery)
  const saves = usePreferenceSaves()
  const [panel, setPanel] = useState<Panel | null>(null)
  const saved = prefs.data && draftOf(prefs.data)
  const close = () => setPanel(null)
  const commit = (key: string, next: LanguageDraft | undefined, still: string) => {
    const patch = saved && next && languagePatch(saved, next)
    if (patch) saves.save(key, patch, still)
  }

  const list = (kind: LanguageListKind, langs: readonly string[]) => {
    const failed = saves.status(kind)
    return <>
      {langs.map((tag, i) => <SettingRow key={tag} id={id(`${kind}/${tag}`)} label={`${i + 1}. ${languageName(tag)}`} value="Options" chevron
        onPress={() => setPanel({ kind: 'language', list: kind, tag })}/>)}
      <SettingRow id={id(`${kind}/add`)} label="Add language" detail={langs.length >= MAX_LANGUAGES ? `You can save up to ${MAX_LANGUAGES}. Remove one first.` : undefined}
        chevron disabled={langs.length >= MAX_LANGUAGES} onPress={() => setPanel({ kind: 'add', list: kind })}/>
      {failed?.state === 'saving' && <PaneNote>Saving…</PaneNote>}
      {failed?.state === 'failed' && <p role="alert" className="settings-note" style={{ color: 'var(--error-text)' }}>Couldn’t save, so your {LIST_TITLES[kind].toLowerCase()} are as they were. Check your connection and try again.</p>}
    </>
  }

  if (prefs.isPending) return <PaneNote>Loading…</PaneNote>
  if (!saved) return <AccountNotLoaded id={id('not-loaded')} error={prefs.error} retrying={prefs.isFetching} onRetry={() => void prefs.refetch()}/>
  const choice = choiceOf(saved.choice)
  const automatic = automaticAvailable(saved)
  const panelList = panel && panel.kind !== 'start' ? saved[panel.list] : []
  return <>
    <PaneHeading>Audio languages · your account</PaneHeading>
    <PaneNote>Videos start in the first of these languages they have. If they have none, they play the file’s default.</PaneNote>
    {list('audio', saved.audio)}
    {level35 && prefs.data && <SoundSection prefs={prefs.data} saves={saves}/>}

    <PaneHeading>Start subtitles</PaneHeading>
    <SettingRow id={id('start')} label="When a video starts" detail={startSubtitlesLine(saved.choice, saved.audio)} value={choice.short} chevron autoFocus={first}
      save={saves.status('start')} onPress={saves.press('start', () => setPanel({ kind: 'start' }))}/>

    <PaneHeading>Subtitle languages</PaneHeading>
    {!saved.subtitles.length && <PaneNote>{emptySubtitleText(saved)}</PaneNote>}
    {list('subtitles', saved.subtitles)}
    {level35 && prefs.data && <SizeSection prefs={prefs.data} saves={saves}/>}

    <PaneHeading>Automatic download</PaneHeading>
    <SettingRow id={id('automatic')} label="Automatic download" stacked toggle={automatic && saved.automaticDownload} disabled={!automatic} save={saves.status('automatic')}
      detail={automatic ? 'One exact-file match from OpenSubtitles.com when no local track matches' : automaticReason(saved) ?? undefined}
      onPress={saves.press('automatic', () => commit('automatic', { ...saved, automaticDownload: !saved.automaticDownload }, saved.automaticDownload ? 'On' : 'Off'))}/>

    {panel?.kind === 'start' && <ChoicePanel idPrefix="choice/start" title="Start subtitles" context="Audio & subtitles · your account" onClose={close}
      choices={subtitleChoices.map((c) => ({ value: c.id, label: c.label, detail: c.line }))} value={saved.choice}
      onChoose={(v) => commit('start', { ...saved, choice: v }, choice.short)} note="A track you choose in the player always wins."/>}
    {panel?.kind === 'language' && (() => {
      const index = panelList.indexOf(panel.tag)
      if (index < 0) return null
      return <ChoicePanel idPrefix="choice/language" title={languageName(panel.tag)} context={`${LIST_TITLES[panel.list]} · ${index + 1} of ${panelList.length}`} onClose={close}
        choices={languageActions(index, panelList.length).map((a) => ({ value: a, label: languageActionLabel(a) }))}
        onChoose={(a) => {
          // A removed language's row goes once it saves: focus moves to the next one, else the list's Add language.
          if (a === 'remove') {
            const next = panelList[index + 1] ?? panelList[index - 1]
            if (!(next && focus.focusId(id(`${panel.list}/${next}`)))) focus.focusId(id(`${panel.list}/add`))
          }
          commit(panel.list, actOn(saved, panel.list, panel.tag, a), '')
        }}/>
    })()}
    {panel?.kind === 'add' && <ChoicePanel idPrefix="choice/add" title="Add language" context={LIST_TITLES[panel.list]} onClose={close}
      choices={addable(panelList).map((l) => ({ value: l.tag, label: l.name }))} onChoose={(tag) => commit(panel.list, addTo(saved, panel.list, tag), '')}/>}
  </>
}

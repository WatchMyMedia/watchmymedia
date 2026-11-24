import { useEffect, useState, type CSSProperties } from 'react'
import { useQuery } from '@tanstack/react-query'
import { requestableQuery, requestOptionsQuery, useCreateRequest } from '../../../src/api/requests'
import type { components } from '../../../src/api/schema'
import { useFocus, useMediaUrls } from '../../app/hooks'
import { Button, KeyHints } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { Sheet } from '../../ui/Overlays'
import { problemWords } from '../../ui/problemWords'
import { placeholderTone } from '../../ui/tones'
import { approvalNote, chosen, initialPicks, metaLine, requestLabel, seasonChoices, sentWords, standing, toggle } from './requestForm'
import './requests.css'
import { BACK } from '../../app/keys'

type CreatedRequest = components['schemas']['CreatedRequest']

export type RequestTarget = {
  kind: 'movie' | 'tv'
  tmdbId: number
  title: string
  /** For a show: the seasons asked for; none asks for the whole show. */
  seasons?: number[]
}

/**
 * The request dialog, over
 * the screen that opens it: search's Not in your library, a show's missing
 * seasons and episodes, a list's missing titles. The poster, title and
 * overview with Request and Cancel on the left, and a show's seasons to
 * tick on the right (the missing ones start ticked, or the ones `target`
 * names). Focus starts on Request; Return closes it. Once asked for, it says
 * so and Done closes it with `requested`.
 */
export function RequestDialog({ target, onClose }: { target: RequestTarget; onClose: (requested: boolean) => void }) {
  const lookup = useQuery(requestableQuery(target.kind, target.tmdbId))
  const options = useQuery(requestOptionsQuery)
  const create = useCreateRequest()
  const media = useMediaUrls()
  const focus = useFocus()
  const [picked, setPicked] = useState<number[] | null>(null)
  const [sent, setSent] = useState<CreatedRequest | null>(null)
  const item = lookup.data
  const isShow = target.kind === 'tv'
  const choices = seasonChoices(item?.seasons)
  const ticked = picked ?? initialPicks(choices, target.seasons)
  const count = isShow ? chosen(ticked, choices).length : 1
  const stand = item ? standing(item) : 'ask'
  const canSend = !!item && !create.isPending && stand === 'ask' && count > 0
  const note = approvalNote(options.data)
  const failed = create.isError ? problemWords(create.error) : undefined
  const poster = media.imageUrl(item?.posterPath, 'w342')
  const tone = placeholderTone(target.title)

  // Once it is asked for, the buttons give way to Done: focus goes there.
  useEffect(() => { if (sent) focus.focusId('request/done') }, [sent, focus])

  const send = () => {
    if (!canSend) return
    create.mutate({ mediaType: target.kind, tmdbId: target.tmdbId, seasons: isShow ? chosen(ticked, choices) : undefined }, { onSuccess: setSent })
  }
  const words = sent && sentWords(sent, options.data?.backend)

  return <Sheet label={`Request ${target.title}`} onClose={() => onClose(!!sent)}>
    <div className="request-hero" aria-hidden="true" style={{ '--ph-a': tone[0] } as CSSProperties}/>
    <div className="request-poster" style={{ '--ph-a': tone[0], '--ph-b': tone[1] } as CSSProperties}>
      {poster && <img src={poster} alt="" decoding="async"/>}
    </div>
    <section className="request-main">
      <p className="tv-kicker">Request</p>
      <h1 className="request-title">{item?.title ?? target.title}</h1>
      {item && <p className="request-meta">{metaLine(item)}</p>}
      {item?.overview && <p className="request-overview">{item.overview}</p>}
      {words ? <div role="status" className="request-sent">
        <p className="request-sent__title"><Icon name="check"/>{words.title}</p>
        <p className="tv-note">{words.body}</p>
        <div className="tv-actions"><Button id="request/done" variant="primary" onPress={() => onClose(true)}>Done</Button></div>
      </div> : <>
        {stand === 'inLibrary' && <p className="request-line"><Icon name="check"/>It’s in your library already.</p>}
        {stand === 'asked' && <p className="request-line"><Icon name="clock"/>It’s been asked for already. You’ll see it in your library when it arrives.</p>}
        <div className="tv-actions request-actions">
          {stand === 'ask'
            ? <Button id="request/send" variant="primary" autoFocus busy={create.isPending} onPress={send}>
              {create.isPending ? 'Requesting…' : failed ? 'Try again' : requestLabel(target.kind, count)}
            </Button>
            : <Button id="request/close" autoFocus onPress={() => onClose(false)}>Close</Button>}
          {stand === 'ask' && <Button id="request/cancel" onPress={() => onClose(false)}>Cancel</Button>}
        </div>
        {stand === 'ask' && note && <p className="request-line request-line--muted"><Icon name="clock"/>{note}</p>}
        {failed && <p role="alert" className="tv-error"><Icon name="alert"/>Couldn’t ask for it. {failed.body}</p>}
        {lookup.isError && <p role="alert" className="tv-error"><Icon name="alert"/>{problemWords(lookup.error).title}. {problemWords(lookup.error).body}</p>}
      </>}
    </section>
    {isShow && <section className="request-seasons" aria-label="Seasons">
      <h2 className="request-seasons__head">Seasons</h2>
      {lookup.isPending && <p className="tv-note request-seasons__note">Loading…</p>}
      {item && !choices.length && <p className="tv-note request-seasons__note">No seasons are listed for it yet.</p>}
      <div className="request-seasons__list">
        {choices.map((c) => {
          const on = ticked.includes(c.number)
          const body = <>
            <span className={`request-box${c.locked ? ' request-box--locked' : on ? ' request-box--on' : ''}`}>
              {c.locked ? <Icon name={c.note === 'Requested' ? 'clock' : 'check'}/> : on ? <Icon name="check"/> : null}
            </span>
            <span className="request-season__text"><span className="request-season__name">{c.name}</span><span className="request-season__detail">{c.detail}</span></span>
            {c.note && <span className="request-season__note">{c.note}</span>}
          </>
          return c.locked || sent
            ? <div key={c.number} className="request-season request-season--locked">{body}</div>
            : <button key={c.number} type="button" role="checkbox" aria-checked={on} data-focus={`request/season/${c.number}`} className="request-season"
              aria-label={`${c.name}, ${on ? 'selected' : 'not selected'}`} onClick={() => setPicked(toggle(ticked, c.number))}>{body}</button>
        })}
      </div>
    </section>}
    <KeyHints hints={[
      ...(isShow && !sent ? [{ key: '▶', label: 'Seasons' }, { key: 'OK', label: 'Tick or untick a season' }] : []),
      { key: BACK, label: sent ? 'Close' : 'Cancel' },
    ]}/>
  </Sheet>
}

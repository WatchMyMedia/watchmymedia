import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import { Icon } from '../../ui/Icon'
import { Modal } from '../../ui/Overlays'
import { problemWords } from '../../ui/problemWords'
import { ProgrammeArt } from './ChannelArt'
import type { PanelMark, PanelRowId, ProgrammePanelView } from './guideModel'
import { LiveIcon } from './LiveIcon'

/** A row's mark: the red dot to record, ringed for every episode, the bell, play, the star. */
export function RowMark({ mark }: { mark: PanelMark }) {
  switch (mark) {
    case 'dot': return <span className="live-mark live-mark--dot" aria-hidden="true"/>
    case 'ring': return <span className="live-mark live-mark--ring" aria-hidden="true"/>
    case 'record': return <LiveIcon name="record" className="live-row__icon live-row__icon--rec"/>
    default: return <LiveIcon name={mark} className="live-row__icon"/>
  }
}

/**
 * A programme's panel, from the right: its picture, title, when and where,
 * a line about it, then what can be done (Record this episode, Record every
 * new episode, Remind me, Watch the channel now, its favourite). OK on a
 * later programme opens it, and Hold OK on any. Return closes it.
 */
export function ProgrammePanel({ channel, programme, view, busy, error, onPick, onClose }: {
  channel: LiveChannel; programme: LiveProgramme; view: ProgrammePanelView; busy?: boolean; error?: unknown; onPick(id: PanelRowId): void; onClose(): void
}) {
  return <Modal onClose={onClose} className="">
    <aside role="dialog" aria-modal="true" aria-label={view.title} className="tv-panel live-ppanel">
      <ProgrammeArt channel={channel} programme={programme} size="panel" overlay={false}/>
      <h2 className="live-ppanel__title">{view.title}</h2>
      <p className="live-ppanel__when"><b>{view.when}</b><span>{view.where}</span></p>
      {view.about && <p className="live-ppanel__about">{view.about}</p>}
      <div className="live-ppanel__rows" role="menu" aria-label={view.title}>
        {view.rows.map((r, i) => <button key={r.id} type="button" role="menuitem" className="tv-option live-row" data-focus={`programme/${r.id}`} data-autofocus={i === 0 || undefined}
          aria-disabled={r.disabled || (busy && r.id.startsWith('record')) || undefined} aria-busy={busy && r.id.startsWith('record') || undefined}
          onClick={r.disabled || busy ? undefined : () => onPick(r.id)}>
          <span className="live-row__mark"><RowMark mark={r.mark}/></span>
          <span className="tv-option__text"><span className="tv-option__label">{r.label}</span>{r.detail && <span className="tv-option__detail">{r.detail}</span>}</span>
          {r.next && <span className="tv-option__end"><Icon name="right"/></span>}
        </button>)}
      </div>
      {error ? <p className="tv-error live-ppanel__foot" role="alert">{problemWords(error).body}</p> : view.foot && <p className="live-ppanel__foot">{view.foot}</p>}
    </aside>
  </Modal>
}

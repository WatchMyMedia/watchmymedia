import type { ReactNode } from 'react'
import { useSession } from '../../app/hooks'
import { Icon } from '../../ui/Icon'
import { Button } from '../../ui/Button'
import { Option, SidePanel } from '../../ui/Overlays'
import { QrCode } from '../../ui/QrCode'
import { problemWords } from '../../ui/problemWords'
import { displayAddress } from '../../session/address'
import { Chevron } from './SettingRow'
import { BACK } from '../../app/keys'

export type Choice<V> = { value: V; label: string; detail?: string | null }

/**
 * A setting's choices in the side panel:
 * the chosen one ticked and focused, what each does under it, a note, and
 * "Return closes this panel." Choosing closes it, unless `keepOpen` (Subtitle
 * size, whose preview changes as you choose). With no `value` it is a list
 * of actions: nothing ticked, focus on the first.
 */
export function ChoicePanel<V>({ title, context, choices, value, onChoose, onClose, note, keepOpen, children, foot = `${BACK} closes this panel.`, idPrefix }: {
  title: string
  context?: string
  choices: readonly Choice<V>[]
  value?: V
  onChoose(value: V): void
  onClose(): void
  note?: string
  keepOpen?: boolean
  /** Drawn under the choices (Subtitle size's preview). */
  children?: ReactNode
  foot?: ReactNode
  idPrefix: string
}) {
  return <SidePanel title={title} onClose={onClose}>
    {context && <p className="settings-panel__context">{context}</p>}
    <div className="settings-panel__list" role="radiogroup" aria-label={title}>
      {choices.map((c, i) => <Option key={String(c.value)} id={`${idPrefix}/${i}`} label={c.label} detail={c.detail ?? undefined}
        checked={value !== undefined && c.value === value} onPress={() => { onChoose(c.value); if (!keepOpen) onClose() }}/>)}
    </div>
    {children}
    {note && <p className="settings-panel__note">{note}</p>}
    {foot && <p className="settings-panel__foot">{foot}</p>}
  </SidePanel>
}

/**
 * A QR code for a phone, with its address (Server settings, Change
 * password, the privacy policy, Open Seerr): a TV has no browser.
 */
export function HandOff({ url, label, address, children }: { url: string; label: string; address?: string; children?: ReactNode }) {
  return <div className="settings-handoff">
    <QrCode text={url} label={label}/>
    <span className="settings-handoff__text">
      <span><span className="settings-label">Address</span><span className="settings-address">{address ?? displayAddress(url)}</span></span>
      {children}
    </span>
  </div>
}

/**
 * The row at the top of a pane when the account's settings didn't
 * load: OK loads them again,
 * and the rows of this TV's own still work.
 */
export function AccountNotLoaded({ id, error, retrying, onRetry }: { id: string; error: unknown; retrying: boolean; onRetry(): void }) {
  const session = useSession()
  const why = error instanceof TypeError ? `${session.server.name} didn’t answer.` : `${problemWords(error).title}.`
  return <button type="button" role="alert" data-focus={id} className="settings-attention" onClick={retrying ? undefined : onRetry}>
    <Icon name="alert"/>
    <span className="settings-row__stack">
      <span className="settings-row__label">Your account’s settings didn’t load</span>
      <span className="settings-row__detail settings-row__detail--wrap">{retrying ? 'Loading them again…' : `${why} The settings on this TV still work.`}</span>
    </span>
    {!retrying && <span className="settings-row__value">Try again<Chevron/></span>}
  </button>
}

/**
 * A side panel that only tells (Change password, the privacy policy,
 * licences): its lines, then Close, where focus starts. Return closes it too.
 */
export function InfoPanel({ title, children, onClose }: { title: string; children: ReactNode; onClose(): void }) {
  return <SidePanel title={title} onClose={onClose}>
    {children}
    <div className="settings-panel__close"><Button id="panel/close" autoFocus onPress={onClose}>Close</Button></div>
    <p className="settings-panel__foot settings-panel__foot--tight">{BACK} closes this panel.</p>
  </SidePanel>
}

/** A QR code in a side panel, under the address it opens. */
export function PanelQr({ url, label, children }: { url: string; label: string; children?: ReactNode }) {
  return <div className="settings-qr">
    <span><span className="settings-label">Scan, or open</span><span className="settings-address settings-address--small">{displayAddress(url)}</span></span>
    <QrCode text={url} label={label}/>
    {children}
  </div>
}

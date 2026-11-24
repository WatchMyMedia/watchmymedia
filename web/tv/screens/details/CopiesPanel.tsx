import { Option, SidePanel } from '../../ui/Overlays'
import { serverHeading, type CopyGroup, type CopyRef } from './copies'

/**
 * Choose a version, for a title on several servers: grouped by server
 * ("Home server", "Cabin server"), each version with where the person is
 * in it there, a server away from home with its note, the copy Play uses
 * ticked and focused. OK on a copy chooses it: the panel closes and focus
 * goes back to Play, which now plays that copy; choosing never plays. A
 * server still being asked is named at the end, and can't take focus.
 */
export function CopiesPanel({ sub, groups, note, asking, onChoose, onClose }: {
  sub: string
  groups: CopyGroup[]
  /** "Resume starts at 1:06:12, where you got to on Cabin, whichever you choose." */
  note?: string
  /** Servers still being asked after a moment. */
  asking: string[]
  onChoose(ref: CopyRef): void
  onClose(): void
}) {
  return <SidePanel title="Choose a version" sub={sub} onClose={onClose}>
    {note && <p className="dt-copies__note">{note}</p>}
    <div role="menu" aria-label="Versions on each server" className="dt-rows dt-copies">
      {groups.map((g) => <div key={g.server.id} className="dt-copies__group" role="group" aria-label={g.heading}>
        <h3 className="dt-rows__head">{g.heading}</h3>
        {g.note && <p className="dt-copies__away">{g.note}</p>}
        {g.rows.map((r) => <Option key={`${r.ref.server}:${r.ref.file}`} id={`copy/${r.ref.server}/${r.ref.file}`} label={r.quality} mark="tick"
          detail={r.chosen ? `${r.place} · plays next` : r.place} checked={r.chosen} onPress={() => onChoose(r.ref)}/>)}
      </div>)}
      {asking.length > 0 && <p className="dt-copies__asking" role="status"><span className="tv-spinner" aria-hidden="true"/>Still asking {asking.map(serverHeading).join(' and ')}…</p>}
    </div>
  </SidePanel>
}

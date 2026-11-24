import { memo } from 'react'
import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import { ProgrammeArt } from './ChannelArt'
import type { Strip } from './guideModel'

/**
 * The focused programme in two lines above the guide: its picture, the
 * title (and New), when and on which channel, a line about it, and what OK
 * does. It changes as focus moves and takes no focus itself.
 */
export const GuideStrip = memo(function GuideStrip({ strip, channel, programme, stale }: { strip: Strip; channel: LiveChannel; programme?: LiveProgramme; stale?: boolean }) {
  return <section className="live-strip" role="status" aria-label="The focused programme">
    <ProgrammeArt channel={channel} programme={programme} size="strip" overlay={false}/>
    <span className="live-strip__text">
      <span className="live-strip__head"><h1 className="live-strip__title">{strip.title}</h1>{strip.isNew && <span className="tv-tag">New</span>}</span>
      <span className="live-strip__when"><b>{strip.when}</b><span>{strip.where}</span></span>
      {(strip.about || stale) && <span className="live-strip__about">{stale ? `Listings may be out of date. ${strip.about}` : strip.about}</span>}
    </span>
    <span className="live-strip__ok" aria-hidden="true"><span className="tv-kbd">OK</span>{strip.ok}</span>
  </section>
})

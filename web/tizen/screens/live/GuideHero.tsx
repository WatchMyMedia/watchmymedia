import { memo } from 'react'
import { panelPicture } from '../../../src/lib/liveGuide'
import { useMediaUrls } from '../../app/hooks'
import { ChannelLogo } from './ChannelArt'
import type { Hero } from './guideModel'
import { LiveIcon } from './LiveIcon'

/**
 * The focused programme above the guide (LiveGuide's hero): the channel and
 * when, the title, the episode and tags, the times with progress, a few
 * lines of description, and what OK and Hold OK do. Its picture fills the
 * top right and fades into the page.
 */
export const GuideHero = memo(function GuideHero({ hero }: { hero?: Hero }) {
  const media = useMediaUrls()
  if (!hero) return <section className="live-hero" aria-hidden="true"/>
  const picture = hero.programme ? media.mediaUrl(panelPicture(hero.programme)?.url) : undefined
  return <>
    <div className="live-hero__pic" aria-hidden="true">
      {picture && <img key={picture} src={picture} alt="" decoding="async" onError={(e) => { e.currentTarget.style.display = 'none' }}/>}
      <span className="live-hero__fade"/>
    </div>
    <section className="live-hero" role="status" aria-label="Selected programme">
      <div className="live-hero__line">
        <ChannelLogo channel={hero.channel} className="live-hero__logo"/>
        <b>{hero.channel.number}</b>
        {hero.favourite && <LiveIcon name="starFilled" className="live-hero__star"/>}
        <span>{hero.channel.name}</span><span aria-hidden="true">·</span>
        <span className={hero.airing ? 'live-hero__when--now' : ''}>{hero.when}</span>
        {hero.reminderLine && <><span aria-hidden="true">·</span><span className="live-hero__reminder"><LiveIcon name="bell"/>{hero.reminderLine}</span></>}
        {hero.recordingLine && <><span aria-hidden="true">·</span><span className="live-hero__recording"><LiveIcon name="record"/>{hero.recordingLine}</span></>}
      </div>
      <h1 className="live-hero__title">{hero.title}</h1>
      {(hero.episode || hero.tags.new || hero.tags.live || hero.tags.hd || hero.categories) && <div className="live-hero__meta">
        {hero.episode && <span>{hero.episode}</span>}
        {hero.tags.new && <span className="live-tag">New</span>}
        {hero.tags.live && <span className="live-tag live-tag--live">Live</span>}
        {hero.tags.hd && <span className="live-tag live-tag--hd">HD</span>}
        {hero.categories && <span className="live-hero__cats">{hero.categories}</span>}
      </div>}
      <div className="live-hero__time">
        <span>{hero.time}</span>
        {hero.progress !== undefined && <><span className="live-bar" role="img" aria-label={`${Math.round(hero.progress * 100)}% through`}><span style={{ width: `${Math.round(hero.progress * 100)}%` }}/></span><span>{hero.left}</span></>}
        {hero.startsIn && <><span aria-hidden="true" className="live-hero__dot">·</span><span>{hero.startsIn}</span></>}
      </div>
      {hero.description && <p className="live-hero__desc">{hero.description}</p>}
      <div className="live-hero__keys">
        {hero.later && <><b>{hero.later}</b><span className="live-sep" aria-hidden="true"/></>}
        <span className="tv-kbd">OK</span><span className={hero.airing ? '' : 'live-hero__soft'}>{hero.watch}</span>
        {hero.hold && <><span className="live-gap" aria-hidden="true"/><span className="tv-kbd">Hold OK</span><span>{hero.hold}</span></>}
      </div>
    </section>
  </>
})

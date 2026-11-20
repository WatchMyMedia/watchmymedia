import { useState, type ReactNode } from 'react'
import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import { panelPicture } from '../../../src/lib/liveGuide'
import { useMediaUrls } from '../../app/hooks'

/** A picture that leaves its fallback showing when it doesn't load (the preview's fakes have none). */
function Img({ src, className, fallback }: { src?: string; className: string; fallback?: ReactNode }) {
  const [failed, setFailed] = useState<string>()
  if (!src || failed === src) return <>{fallback}</>
  return <img className={className} src={src} alt="" decoding="async" loading="lazy" draggable={false} onError={() => setFailed(src)}/>
}

/** The channel's logo from the listings (4:3, transparent), or nothing. */
export function ChannelLogo({ channel, className = 'live-logo' }: { channel: Pick<LiveChannel, 'logoUrl'>; className?: string }) {
  const media = useMediaUrls()
  return <Img src={media.mediaUrl(channel.logoUrl)} className={className}/>
}

export type ArtSize = 'card' | 'row' | 'banner' | 'panel' | 'hero' | 'thumb'

/**
 * A programme's 16:9 picture as the boards draw it: the series card or the
 * matched still, with the channel's logo and number over its top edge;
 * without a picture, the logo (or the channel's name) on the card's ground.
 */
export function ProgrammeArt({ channel, programme, picture, size, overlay = true, live, progress, dim, children }: {
  channel: Pick<LiveChannel, 'number' | 'name' | 'logoUrl'>
  programme?: LiveProgramme
  /** A server path to use instead of the programme's own (a recording's picture). */
  picture?: string
  size: ArtSize
  overlay?: boolean
  /** The red LIVE badge at the top right. */
  live?: boolean
  /** 0–1: the gold bar along the bottom. */
  progress?: number
  dim?: boolean
  children?: ReactNode
}) {
  const media = useMediaUrls()
  const src = media.mediaUrl(picture ?? (programme ? panelPicture(programme)?.url : undefined))
  const plain = <span className="live-art__plain">
    <Img src={media.mediaUrl(channel.logoUrl)} className="live-art__logo" fallback={<span className="live-art__name">{channel.name}</span>}/>
  </span>
  return <span className={`live-art live-art--${size}${dim ? ' live-art--dim' : ''}`} aria-hidden="true">
    <Img key={src} src={src} className="live-art__img" fallback={plain}/>
    {overlay && <span className="live-art__over">
      <ChannelLogo channel={channel} className="live-art__mini"/>
      <b>{channel.number}</b>
      {live && <span className="live-badge"><span className="live-dot"/>LIVE</span>}
      {children}
    </span>}
    {progress !== undefined && progress > 0 && <span className="live-art__progress"><span style={{ width: `${Math.min(100, Math.round(progress * 100))}%` }}/></span>}
  </span>
}

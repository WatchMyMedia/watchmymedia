/**
 * The watchmy.media mark, matching brand/tools/make_sources.py: a film reel
 * with a play triangle for its hub and the film's tail running out along the
 * bottom, white with burgundy details. Below 32 px it draws the small
 * artwork, bigger holes and no play or sprockets, as the favicons do.
 */
const BODY = '#f5f5f5'
const DETAIL = '#5a1414'

// The wheel is drawn as if it filled the grid and scaled to r 200 about
// (216, 256), leaving the bottom right for the tail.
const WHEEL = 200 / 244
const WHEEL_AT = `translate(${216 - 256 * WHEEL} ${256 - 256 * WHEEL}) scale(${WHEEL})`
const HOLES = [0, 1, 2, 3, 4].map((i) => ((-90 + 72 * i) * Math.PI) / 180)

export function BrandMark({ size = 24, className = '' }: { size?: number; className?: string }) {
  const small = size < 32
  const [ring, hole] = small ? [136, 64] : [126, 56]
  return (
    <svg width={size} height={size} viewBox="0 0 512 512" aria-hidden="true" className={className}>
      <rect x="216" y="401" width="274" height="48" rx="10" fill={BODY} stroke={DETAIL} strokeWidth="14" />
      {!small && [343, 391, 439].map((x) => <rect key={x} x={x} y="416" width="18" height="18" rx="4" fill={DETAIL} />)}
      <g transform={WHEEL_AT}>
        <circle cx="256" cy="256" r="244" fill={BODY} />
        <circle cx="256" cy="256" r="233" fill="none" stroke={DETAIL} strokeWidth="22" />
        {HOLES.map((a) => (
          <circle key={a} cx={256 + ring * Math.cos(a)} cy={256 + ring * Math.sin(a)} r={hole} fill={DETAIL} />
        ))}
        {!small && (
          <polygon points="232,214 232,298 300,256" fill={DETAIL} stroke={DETAIL} strokeWidth="18" strokeLinejoin="round" />
        )}
      </g>
    </svg>
  )
}

/** Mark plus the app name, as in the brand's app lockup. */
export function BrandLockup({ size = 24, textClass = 'text-base' }: { size?: number; textClass?: string }) {
  return (
    <span className="inline-flex items-center gap-2">
      <BrandMark size={size} />
      <span className={`${textClass} font-bold tracking-tight`}>
        WatchMy<span className="font-semibold text-neutral-400">Media</span>
      </span>
    </span>
  )
}

import type { ReactNode } from 'react'

// Line icons drawn the same way on every page: 24-unit boxes, round ends,
// the current text colour. Each is decorative: the button or link it sits in
// carries the name.

function Line({ size = 18, children, className, fill = 'none', strokeWidth = 2 }: { size?: number; children: ReactNode; className?: string; fill?: string; strokeWidth?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={fill} stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={className}>
      {children}
    </svg>
  )
}

type IconProps = { size?: number; className?: string }

export const PlayIcon = ({ size = 18, className }: IconProps) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
    <path d="M7 4.5v15a1 1 0 0 0 1.5.86l12.4-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5z" />
  </svg>
)
export const PauseIcon = ({ size = 18, className }: IconProps) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
    <rect x="6" y="4.5" width="4.2" height="15" rx="1" />
    <rect x="13.8" y="4.5" width="4.2" height="15" rx="1" />
  </svg>
)
export const CheckIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="M20 6 9 17l-5-5" />
  </Line>
)
export const PlusIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="M12 5v14M5 12h14" />
  </Line>
)
export const CloseIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="M18 6 6 18M6 6l12 12" />
  </Line>
)
export const ChevronDownIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="m6 9 6 6 6-6" />
  </Line>
)
export const ChevronUpIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="m18 15-6-6-6 6" />
  </Line>
)
export const ChevronRightIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="m9 6 6 6-6 6" />
  </Line>
)
export const ChevronLeftIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="m15 6-6 6 6 6" />
  </Line>
)
export const MoreIcon = ({ size = 18, className }: IconProps) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
    <circle cx="5" cy="12" r="1.8" />
    <circle cx="12" cy="12" r="1.8" />
    <circle cx="19" cy="12" r="1.8" />
  </svg>
)
export const SearchIcon = (p: IconProps) => (
  <Line {...p}>
    <circle cx="10.5" cy="10.5" r="6.5" />
    <path d="m15.5 15.5 5 5" />
  </Line>
)
export const InfoIcon = (p: IconProps) => (
  <Line {...p}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 11v5M12 8h.01" />
  </Line>
)
export const WarnIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="M12 3 2 20h20L12 3z" />
    <path d="M12 10v4M12 17h.01" />
  </Line>
)
export const BookmarkIcon = ({ filled = false, ...p }: IconProps & { filled?: boolean }) => (
  <Line {...p} fill={filled ? 'currentColor' : 'none'}>
    <path d="M6 3.5h12a1 1 0 0 1 1 1V21l-7-4.5L5 21V4.5a1 1 0 0 1 1-1z" />
  </Line>
)
export const BellIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
    <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
  </Line>
)
export const GridIcon = (p: IconProps) => (
  <Line {...p}>
    <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" />
    <rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
    <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" />
    <rect x="13.5" y="13.5" width="7" height="7" rx="1.5" />
  </Line>
)
export const ListIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01" />
  </Line>
)
export const FilterIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="M3 5h18M6.5 12h11M10 19h4" />
  </Line>
)
export const Back10Icon = (p: IconProps) => (
  <Line {...p}>
    <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
    <path d="M3 3v5h5" />
    <text x="12" y="15.5" textAnchor="middle" fontSize="7.5" fontWeight="700" fill="currentColor" stroke="none" fontFamily="inherit">10</text>
  </Line>
)
export const Forward10Icon = (p: IconProps) => (
  <Line {...p}>
    <path d="M21 12a9 9 0 1 1-3-6.7L21 8" />
    <path d="M21 3v5h-5" />
    <text x="12" y="15.5" textAnchor="middle" fontSize="7.5" fontWeight="700" fill="currentColor" stroke="none" fontFamily="inherit">10</text>
  </Line>
)
export const PreviousIcon = ({ size = 18, className }: IconProps) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
    <path d="M18 5.5v13a.8.8 0 0 1-1.25.66L8 13v5.5a1 1 0 0 1-2 0v-13a1 1 0 0 1 2 0V11l8.75-6.16A.8.8 0 0 1 18 5.5z" />
  </svg>
)
export const NextIcon = ({ size = 18, className }: IconProps) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
    <path d="M6 5.5v13a.8.8 0 0 0 1.25.66L16 13v5.5a1 1 0 0 0 2 0v-13a1 1 0 0 0-2 0V11L7.25 4.84A.8.8 0 0 0 6 5.5z" />
  </svg>
)
export const VolumeIcon = ({ muted = false, ...p }: IconProps & { muted?: boolean }) => (
  <Line {...p}>
    <path d="M11 5 6 9H3v6h3l5 4V5z" />
    {muted ? <path d="m22 9-6 6M16 9l6 6" /> : <path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13" />}
  </Line>
)
export const PipIcon = (p: IconProps) => (
  <Line {...p}>
    <rect x="2.5" y="4.5" width="19" height="15" rx="2" />
    <rect x="12" y="11.5" width="7" height="5.5" rx="1" fill="currentColor" stroke="none" />
  </Line>
)
export const FullscreenIcon = ({ exit = false, ...p }: IconProps & { exit?: boolean }) => (
  <Line {...p}>
    {exit ? <path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" /> : <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />}
  </Line>
)
export const SubtitlesIcon = (p: IconProps) => (
  <Line {...p}>
    <rect x="2.5" y="5" width="19" height="14" rx="2" />
    <path d="M7 13h3M13 13h4M7 16h7" />
  </Line>
)
export const EpisodesIcon = (p: IconProps) => (
  <Line {...p}>
    <rect x="3" y="4" width="13" height="9" rx="1.5" />
    <path d="M7 17h13M7 20.5h13M19 8v5" />
  </Line>
)
export const GuideIcon = (p: IconProps) => (
  <Line {...p}>
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <path d="M3 9h18M9 9v11M15 9v11" />
  </Line>
)
export const RecordIcon = ({ size = 18, className }: IconProps) => (
  <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" className={className}>
    <circle cx="12" cy="12" r="8.5" fill="none" stroke="currentColor" strokeWidth="2" />
    <circle cx="12" cy="12" r="4.5" fill="currentColor" />
  </svg>
)
export const ArrowLeftIcon = (p: IconProps) => (
  <Line {...p}>
    <path d="M19 12H5M11 18l-6-6 6-6" />
  </Line>
)
export const StarIcon = ({ size = 14, className }: IconProps) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
    <path d="m12 2.8 2.85 5.78 6.37.93-4.61 4.49 1.09 6.35L12 17.35l-5.7 3 1.09-6.35L2.78 9.5l6.37-.93z" />
  </svg>
)

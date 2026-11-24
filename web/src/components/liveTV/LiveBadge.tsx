export function LiveBadge({ behind = false }: { behind?: boolean }) {
  return <span className={`inline-flex items-center gap-1.5 rounded px-2 py-1 text-[11px] font-bold tracking-wider ${behind ? 'bg-surface-2 text-neutral-300' : 'bg-red-700 text-white'}`}>
    <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-current" />{behind ? 'BEHIND LIVE' : 'LIVE'}
  </span>
}

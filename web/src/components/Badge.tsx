export function Badge({ children, tone = 'neutral', className = '' }: { children: React.ReactNode; tone?: 'accent' | 'warn' | 'neutral'; className?: string }) {
  const tones = {
    accent: 'bg-accent text-black',
    warn: 'bg-warn text-black',
    neutral: 'bg-black/70 text-neutral-100 backdrop-blur',
  }
  return <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${tones[tone]} ${className}`}>{children}</span>
}

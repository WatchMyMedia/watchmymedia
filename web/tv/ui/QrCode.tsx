import { useMemo } from 'react'
import { qrPath } from './qr'

/** `text` as a QR code on a white card, in whole modules so it stays crisp; dimmed when `faded`. */
export function QrCode({ text, label, faded }: { text: string; label: string; faded?: boolean }) {
  const qr = useMemo(() => qrPath(text), [text])
  return <div className="tv-qr" style={{ background: faded ? '#e8e8e8' : '#fff' }}>
    {qr && <svg viewBox={`0 0 ${qr.size} ${qr.size}`} shapeRendering="crispEdges" role="img" aria-label={label} style={{ opacity: faded ? .18 : 1 }}>
      <path d={qr.path} fill="var(--ground)"/>
    </svg>}
  </div>
}

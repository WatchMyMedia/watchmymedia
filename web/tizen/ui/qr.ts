import qrcode from 'qrcode-generator'

/**
 * `text` as a QR code at error correction M, as one SVG path of whole
 * modules (no quiet zone: the white card around it is the margin), and its
 * size in modules. Undefined when it doesn't fit, which a link never does.
 */
export function qrPath(text: string): { size: number; path: string } | undefined {
  try {
    const qr = qrcode(0, 'M')
    qr.addData(text, 'Byte')
    qr.make()
    const size = qr.getModuleCount()
    let path = ''
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        if (!qr.isDark(y, x)) continue
        // Runs of dark modules along a row make one rectangle each: a shorter path.
        let run = 1
        while (x + run < size && qr.isDark(y, x + run)) run++
        path += `M${x} ${y}h${run}v1h-${run}z`
        x += run - 1
      }
    }
    return { size, path }
  } catch {
    return undefined
  }
}

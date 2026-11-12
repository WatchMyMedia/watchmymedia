package artwork

import (
	"encoding/binary"
	"image"
	"image/draw"
)

// jpegOrientation reads the EXIF orientation (1 to 8) from a JPEG, or 1
// when it has none. Phone photos are stored sideways with this tag; the
// picture is re-encoded without EXIF, so it has to be turned first.
func jpegOrientation(raw []byte) int {
	i := 2 // past the SOI marker
	for i+4 <= len(raw) && raw[i] == 0xFF {
		marker := raw[i+1]
		size := int(binary.BigEndian.Uint16(raw[i+2:]))
		if marker == 0xDA || size < 2 || i+2+size > len(raw) { // image data, or a broken segment
			return 1
		}
		if seg := raw[i+4 : i+2+size]; marker == 0xE1 && len(seg) > 6 && string(seg[:6]) == "Exif\x00\x00" {
			return tiffOrientation(seg[6:])
		}
		i += 2 + size
	}
	return 1
}

func tiffOrientation(t []byte) int {
	if len(t) < 8 {
		return 1
	}
	var bo binary.ByteOrder
	switch string(t[:2]) {
	case "II":
		bo = binary.LittleEndian
	case "MM":
		bo = binary.BigEndian
	default:
		return 1
	}
	off := int(bo.Uint32(t[4:]))
	if off < 8 || off+2 > len(t) {
		return 1
	}
	n := int(bo.Uint16(t[off:]))
	for e := range n {
		p := off + 2 + e*12
		if p+12 > len(t) {
			return 1
		}
		if bo.Uint16(t[p:]) == 0x0112 {
			if v := int(bo.Uint16(t[p+8:])); v >= 1 && v <= 8 {
				return v
			}
			return 1
		}
	}
	return 1
}

// upright returns img turned as its EXIF orientation asks.
func upright(img image.Image, orientation int) image.Image {
	if orientation <= 1 || orientation > 8 {
		return img
	}
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	src := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.Draw(src, src.Bounds(), img, b.Min, draw.Src)
	dw, dh := w, h
	if orientation >= 5 {
		dw, dh = h, w
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	for y := range h {
		for x := range w {
			var dx, dy int
			switch orientation {
			case 2:
				dx, dy = w-1-x, y
			case 3:
				dx, dy = w-1-x, h-1-y
			case 4:
				dx, dy = x, h-1-y
			case 5:
				dx, dy = y, x
			case 6:
				dx, dy = h-1-y, x
			case 7:
				dx, dy = h-1-y, w-1-x
			case 8:
				dx, dy = y, w-1-x
			}
			dst.SetRGBA(dx, dy, src.RGBAAt(x, y))
		}
	}
	return dst
}

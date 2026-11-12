package media.watchmy.client.tv.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import media.watchmy.client.ui.WmmColors
import kotlin.math.floor

/** The QR modules for [text], without a quiet zone (the white card around it is the margin). */
internal fun qrMatrix(text: String): BitMatrix? = try {
    QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
} catch (_: WriterException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** [text] as a QR code, in whole-pixel modules so it stays crisp, centred in the space given. */
@Composable
fun QrCode(text: String, contentDescription: String, modifier: Modifier = Modifier, color: Color = WmmColors.Ground) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        val m = matrix ?: return@Canvas
        val cell = floor(size.minDimension / m.width).coerceAtLeast(1f)
        val origin = Offset((size.width - cell * m.width) / 2, (size.height - cell * m.height) / 2)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m.get(x, y)) drawRect(color, Offset(origin.x + x * cell, origin.y + y * cell), Size(cell, cell))
            }
        }
    }
}

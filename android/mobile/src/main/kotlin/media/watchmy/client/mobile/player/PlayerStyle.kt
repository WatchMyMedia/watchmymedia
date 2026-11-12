package media.watchmy.client.mobile.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import media.watchmy.client.ui.Inter
import media.watchmy.client.ui.WmmColors

/** Text sizes and colours from the player artboards (design/mockups/android/Player*.dc.html). */
internal object PlayerType {
    val Title = inter(18, FontWeight.Bold)
    val Subtitle = inter(13, FontWeight.Normal, WmmColors.TextSoft)
    val Time = inter(13, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum")
    val Button = inter(13, FontWeight.SemiBold)
    val ButtonDetail = inter(11, FontWeight.Medium, WmmColors.TextMuted)
    val Chip = inter(12, FontWeight.Normal, WmmColors.TextSoft)
    val Skip = inter(15, FontWeight.SemiBold)
    val CardTitle = inter(16, FontWeight.Bold)
    val CardMeta = inter(12, FontWeight.Normal, WmmColors.TextMuted)
    val MiniTime = inter(12, FontWeight.Normal, WmmColors.TextMuted).copy(fontFeatureSettings = "tnum")
    val SheetHeader = inter(11, FontWeight.Bold, WmmColors.TextMuted).copy(letterSpacing = 0.88.sp)
    val Option = inter(14, FontWeight.Medium)
    val OptionDetail = inter(12, FontWeight.Normal, WmmColors.TextMuted)
    val OptionGroup = inter(12, FontWeight.SemiBold, WmmColors.Accent)
    val Footnote = inter(11, FontWeight.Normal, WmmColors.TextFaint)
    val Note = inter(13, FontWeight.Medium)
    val Ripple = inter(13, FontWeight.SemiBold)

    private fun inter(size: Int, weight: FontWeight, color: Color = WmmColors.Text) =
        TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, color = color)
}

/** The player's own translucent colours. */
internal object PlayerColors {
    val Light = Color(0xFFE5E5E5)
    val Track = Color.White.copy(alpha = 0.22f)
    val Buffer = Color.White.copy(alpha = 0.35f)
    val OpeningFill = WmmColors.Accent.copy(alpha = 0.22f)
    val OpeningLine = WmmColors.Accent.copy(alpha = 0.6f)
    val ClosingFill = Color.White.copy(alpha = 0.10f)
    val ClosingLine = Color.White.copy(alpha = 0.35f)
    val ThumbHalo = WmmColors.Accent.copy(alpha = 0.3f)
}

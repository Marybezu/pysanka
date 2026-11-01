package ua.pysanka.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Pysanka: the mark of the AI — one four-pointed star ✦, as in Mandat. Drawn in the colour of
 * where it stands (the keyboard theme, or the AI violet below in the settings).
 */
val AiMark: ImageVector = ImageVector.Builder(
    name = "PysankaAiMark",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).path(fill = SolidColor(Color.Black)) {
    moveTo(12f, 2f)
    curveTo(12.8f, 7.6f, 16.4f, 11.2f, 22f, 12f)
    curveTo(16.4f, 12.8f, 12.8f, 16.4f, 12f, 22f)
    curveTo(11.2f, 16.4f, 7.6f, 12.8f, 2f, 12f)
    curveTo(7.6f, 11.2f, 11.2f, 7.6f, 12f, 2f)
    close()
}.build()

/** The AI's violet, as in Mandat (light and dark theme). */
val AiVioletLight = Color(0xFF6A4FD8)
val AiVioletDark = Color(0xFFB9A8FF)

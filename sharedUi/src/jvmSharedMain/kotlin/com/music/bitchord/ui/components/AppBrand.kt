package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.ic_apple_logo
import com.music.bitchord.sharedui.resources.ic_logo
import org.jetbrains.compose.resources.painterResource

/**
 * Which brand the app wears, following the icon picked in Settings.
 *
 * Compose state, so a screen already showing a logo changes with the setting.
 */
object AppBrand {
    var isApple by mutableStateOf(false)

    /** The word set beside the mark. */
    val name: String
        get() = if (isApple) "Music" else "BitChord"
}

/** The mark for the brand in use. Drawn with `Fit`, so it keeps its own shape. */
@Composable
fun brandLogo(): Painter =
    painterResource(if (AppBrand.isApple) Res.drawable.ic_apple_logo else Res.drawable.ic_logo)

package com.ideaforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * App theme. Always dark with a fixed palette: no dynamic colour (it would wash out the amber
 * identity) and no light variant (the app is mostly used while recording in dim rooms).
 */
@Composable
fun IdeaForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IdeaForgeDarkColors,
        typography = IdeaForgeTypography,
        shapes = IdeaForgeShapes,
        content = content,
    )
}

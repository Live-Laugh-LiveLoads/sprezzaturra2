package com.ideaforge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

/** Generous sizes: body text is never below 16sp, which matters on a small, older screen. */
internal val IdeaForgeTypography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontSize = 34.sp, lineHeight = 42.sp),
    headlineMedium = base.headlineMedium.copy(fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = base.headlineSmall.copy(fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = base.titleLarge.copy(fontSize = 24.sp, lineHeight = 32.sp),
    titleMedium = base.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = base.labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = base.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
)

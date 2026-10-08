package com.izumiapps.cameramute.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import com.izumiapps.cameramute.R

// 日本語の字形を端末の標準フォントやシステム言語に依存させない。
@OptIn(ExperimentalTextApi::class)
private val NotoSansJapanese = FontFamily(
    (100..900 step 100).map { weight ->
        Font(
            resId = R.font.noto_sans_jp_variable,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

private fun TextStyle.withJapaneseFont() = copy(
    fontFamily = NotoSansJapanese,
    localeList = LocaleList("ja-JP"),
)

// Material 3の文字サイズ・太さ・行間は維持し、ボタンやダイアログを含む全ロールへ適用する。
val CameraMuteAssistTypography = Typography().let { defaults ->
    Typography(
        displayLarge = defaults.displayLarge.withJapaneseFont(),
        displayMedium = defaults.displayMedium.withJapaneseFont(),
        displaySmall = defaults.displaySmall.withJapaneseFont(),
        headlineLarge = defaults.headlineLarge.withJapaneseFont(),
        headlineMedium = defaults.headlineMedium.withJapaneseFont(),
        headlineSmall = defaults.headlineSmall.withJapaneseFont(),
        titleLarge = defaults.titleLarge.withJapaneseFont(),
        titleMedium = defaults.titleMedium.withJapaneseFont(),
        titleSmall = defaults.titleSmall.withJapaneseFont(),
        bodyLarge = defaults.bodyLarge.withJapaneseFont(),
        bodyMedium = defaults.bodyMedium.withJapaneseFont(),
        bodySmall = defaults.bodySmall.withJapaneseFont(),
        labelLarge = defaults.labelLarge.withJapaneseFont(),
        labelMedium = defaults.labelMedium.withJapaneseFont(),
        labelSmall = defaults.labelSmall.withJapaneseFont(),
    )
}

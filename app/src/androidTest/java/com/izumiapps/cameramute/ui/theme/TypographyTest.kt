package com.izumiapps.cameramute.ui.theme

import android.content.res.Configuration
import android.os.LocaleList as AndroidLocaleList
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.text.intl.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.izumiapps.cameramute.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypographyTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun allMaterialRoles_useBundledJapaneseFont_andKeepTheirMetrics() {
        lateinit var typography: Typography
        composeRule.setContent {
            CameraMuteAssistTheme {
                typography = MaterialTheme.typography
            }
        }
        composeRule.runOnIdle {
            val defaults = Typography().styles()
            typography.styles().forEachIndexed { index, style ->
                assertTrue("Typography role $index must use a bundled font", style.fontFamily is FontListFontFamily)
                val family = style.fontFamily as FontListFontFamily
                assertEquals((100..900 step 100).toList(), family.map { it.weight.weight })
                family.forEach { font ->
                    assertTrue(font is ResourceFont)
                    font as ResourceFont
                    assertEquals(R.font.noto_sans_jp_variable, font.resId)
                    assertEquals(FontVariation.Settings(FontVariation.weight(font.weight.weight)), font.variationSettings)
                }
                assertEquals(LocaleList("ja-JP"), style.localeList)
                assertEquals(defaults[index], style.copy(fontFamily = defaults[index].fontFamily, localeList = defaults[index].localeList))
            }
        }
    }

    @Test fun japaneseGlyphs_renderIdenticallyUnderEnglishJapaneseAndChineseLocales() {
        var languageTag by mutableStateOf("en-US")
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(AndroidLocaleList.forLanguageTags(languageTag))
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                CameraMuteAssistTheme(darkTheme = false) {
                    Text(
                        "直 骨 令 返 無料 補助 状態 確認",
                        modifier = Modifier.testTag("glyphs").background(Color.White),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black,
                    )
                }
            }
        }
        val english = composeRule.onNodeWithTag("glyphs").captureToImage().asAndroidBitmap()
        for (locale in listOf("ja-JP", "zh-CN", "zh-TW")) {
            composeRule.runOnIdle { languageTag = locale }
            val localized = composeRule.onNodeWithTag("glyphs").captureToImage().asAndroidBitmap()
            assertTrue("Japanese glyphs must not change with $locale", english.sameAs(localized))
        }
    }

    private fun Typography.styles() = listOf(
        displayLarge, displayMedium, displaySmall,
        headlineLarge, headlineMedium, headlineSmall,
        titleLarge, titleMedium, titleSmall,
        bodyLarge, bodyMedium, bodySmall,
        labelLarge, labelMedium, labelSmall,
    )
}

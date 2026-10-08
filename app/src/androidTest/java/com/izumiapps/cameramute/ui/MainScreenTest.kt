package com.izumiapps.cameramute.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.izumiapps.cameramute.audiopolicy.ForceUseState
import com.izumiapps.cameramute.shizuku.ShizukuConnectionState
import com.izumiapps.cameramute.ui.theme.CameraMuteAssistTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val ready = MainUiState(
        shizukuState = ShizukuConnectionState.READY,
        deviceModel = "画面テスト（模擬）",
        deviceDisplay = "実機未接続",
        forceUseState = ForceUseState.GenuineEnforced,
        consentAccepted = true,
        notificationPermissionGranted = true,
        silentOrVibrate = true,
    )

    private fun setContent(
        state: MainUiState,
        onAcceptSafety: () -> Unit = {},
        stateProvider: () -> MainUiState = { state },
        onNotification: () -> Unit = {},
        onSound: () -> Unit = {},
        onOpenShizuku: () -> Unit = {},
        onShizukuPermission: () -> Unit = {},
        onRestore: () -> Unit = {},
    ) {
        composeRule.setContent {
            CameraMuteAssistTheme {
                MainScreen(
                    uiState = stateProvider(),
                    onEnableAssist = {}, onRestoreGenuine = onRestore, onRefresh = {},
                    onOpenSoundSettings = onSound,
                    onRequestShizukuPermission = onShizukuPermission, onRequestNotificationPermission = onNotification,
                    onOpenNotificationSettings = {}, onOpenShizuku = onOpenShizuku, onAcceptSafety = onAcceptSafety,
                )
            }
        }
    }

    // 画面確認用。模擬状態であり、対応実機のE2E証拠ではない。
    private fun saveScreenshot(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review")
        check(directory.mkdirs() || directory.isDirectory)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "画面画像を取得できません"
        }
        try {
            File(directory, name).outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    "画面画像をPNGに保存できません"
                }
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun assertDialogFontScale(expected: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("閉じる", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(layouts))
            }
        assertEquals(1, layouts.size)
        assertEquals(expected, layouts.single().layoutInput.density.fontScale, 0.001f)
    }

    // Dialogは別Windowのため、専用エミュレーター側で設定した実際の倍率を検証する。
    // このテスト自体は端末の設定を変更しない。
    private fun expectedFontScale(): Float {
        val scale = InstrumentationRegistry.getArguments().getString("expectedFontScale")?.toFloat()
            ?: InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        require(scale.isFinite() && scale > 0f) { "expectedFontScale must be finite and positive" }
        return scale
    }

    @Test fun sourcePublishedVersion_showsAppTitle() {
        setContent(ready)
        val title = composeRule.onNodeWithText("オッポカメラ無音化（非公式）")
        title.assertExists()
        // 文字を拡大した専用エミュレーターでは省略を許容し、標準倍率ではアプリ名が省略されないことを確認する。
        if (expectedFontScale() <= 1f) {
            val layouts = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue(action(layouts))
            }
            val layout = layouts.single()
            assertFalse(layout.isLineEllipsized(layout.lineCount - 1))
        }
    }

    @Test fun readyState_canEnable_andShowsGenuineState() {
        setContent(ready)
        composeRule.onNodeWithText("通常の音設定").assertExists()
        composeRule.onNodeWithText("無音補助をオン").assertIsEnabled()
        composeRule.onNodeWithText("純正状態に戻す").assertIsNotEnabled()
        saveScreenshot("simulated-ready.png")
    }

    @Test fun active_restoreInvokesRestore() {
        var restored = false
        setContent(ready.copy(forceUseState = ForceUseState.MuteAssistActive)) { restored = true }
        composeRule.onNodeWithText("無音補助中").assertExists()
        composeRule.onNodeWithText("純正状態に戻す").assertIsEnabled().performScrollTo().performClick()
        assertTrue(restored)
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
        saveScreenshot("simulated-active.png")
    }

    @Test fun disconnected_offersShizuku_andDisablesEnable() {
        setContent(ready.copy(shizukuState = ShizukuConnectionState.NOT_AVAILABLE))
        composeRule.onNodeWithText("Shizuku未接続").assertExists()
        composeRule.onNodeWithText("Shizukuアプリを開く").assertExists()
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
    }

    @Test fun permissionDenied_offersPermission() {
        setContent(ready.copy(shizukuState = ShizukuConnectionState.PERMISSION_DENIED))
        composeRule.onNodeWithText("Shizukuの権限が未許可です").assertExists()
        composeRule.onNodeWithText("Shizukuの権限を許可").assertExists()
    }

    @Test fun rootBackend_explainsRestartMethod_andHidesPermissionUntilSupported() {
        val state = androidx.compose.runtime.mutableStateOf(ready.copy(
            shizukuState = ShizukuConnectionState.UNSUPPORTED_BACKEND,
            forceUseState = ForceUseState.MuteAssistActive,
            consentAccepted = false,
        ))
        var shizukuOpened = false
        var permissionRequested = false
        setContent(state.value, stateProvider = { state.value },
            onOpenShizuku = { shizukuOpened = true },
            onShizukuPermission = { permissionRequested = true })
        composeRule.onNodeWithText("Shizukuの起動方式が未対応です").assertExists()
        composeRule.onNodeWithText("ワイヤレスデバッグまたはADBで起動し直してください。", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
        composeRule.onNodeWithText("純正状態に戻す").assertIsNotEnabled()
        composeRule.onNodeWithText("Shizukuの権限を許可").assertDoesNotExist()
        composeRule.onNodeWithText("Shizuku連携完了").assertDoesNotExist()
        composeRule.onNodeWithText("無音補助中").assertDoesNotExist()
        composeRule.onNodeWithText("Shizukuを開いて準備する").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(shizukuOpened) }
        saveScreenshot("simulated-root-backend.png")
        composeRule.runOnIdle {
            state.value = state.value.copy(shizukuState = ShizukuConnectionState.PERMISSION_DENIED)
        }
        composeRule.onNodeWithText("Shizukuの権限を許可").assertIsEnabled().performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(permissionRequested) }
    }

    @Test fun initialState_offersConnectionAndSetupGuidance() {
        setContent(MainUiState())
        composeRule.onNodeWithText("Shizuku未接続").assertExists()
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
        composeRule.onNodeWithText("Shizukuアプリを開く").assertExists()
        composeRule.onNodeWithText("Shizukuの準備方法を見る").assertExists()
        composeRule.onNodeWithText("対応機種と復元の案内").assertExists()
        composeRule.onNodeWithText("通知を許可する").assertExists()
        composeRule.onNodeWithText("純正状態に戻す").assertIsNotEnabled()
    }

    @Test fun help_isAvailable() {
        setContent(ready)
        composeRule.onNodeWithText("Shizukuの準備方法を見る").performScrollTo().performClick()
        composeRule.onNodeWithText("Shizukuの準備方法").assertExists()
        composeRule.onNodeWithText("② OPPOの権限制限を解除する").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("認可の監視を無効化", substring = true).assertExists()
        composeRule.onNodeWithText("Disable permission monitoring", substring = true).assertExists()
        saveScreenshot("shizuku-setup-oppo-permission.png")
        composeRule.onNodeWithText("③ ワイヤレスデバッグでShizukuを起動する").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("④ オッポカメラ無音化（非公式）に権限を許可する").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("閉じる").performClick()
        composeRule.onNodeWithText("Shizukuの準備方法").assertDoesNotExist()
    }

    @Test fun unknownState_doesNotEnable_butOffersSafeRestoreAttempt() {
        setContent(ready.copy(forceUseState = null))
        composeRule.onNodeWithText("状態不明").assertExists()
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
        composeRule.onNodeWithText("純正状態に戻す").assertIsEnabled()
    }

    @Test fun disclosure_includesManualRestore_andUnverifiedEmergencyAudio() {
        setContent(MainUiState())
        composeRule.onNodeWithText("個人開発の非公式アプリです。OPPO・Shizukuの開発元とは提携していません。OPPOおよびAndroidは、各権利者の商標または登録商標です。").assertExists()
        composeRule.onNodeWithText("自動では元に戻りません。", substring = true).assertExists()
        composeRule.onNodeWithText("本物の緊急地震速報", substring = true).assertExists()
    }

    @Test fun safetyConsent_appearsAfterConnection_andClosesOnlyAfterAcceptance() {
        val state = androidx.compose.runtime.mutableStateOf(ready.copy(
            consentAccepted = false, shizukuState = ShizukuConnectionState.PERMISSION_DENIED,
        ))
        setContent(state.value, stateProvider = { state.value }, onAcceptSafety = {
            state.value = state.value.copy(consentAccepted = true)
        })
        composeRule.onNodeWithText("Shizuku連携完了").assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(shizukuState = ShizukuConnectionState.READY) }
        composeRule.onNodeWithText("Shizuku連携完了").assertIsDisplayed()
        composeRule.onNodeWithText("理解して次へ").performClick()
        composeRule.onNodeWithText("Shizuku連携完了").assertDoesNotExist()
        composeRule.onNodeWithText("無音補助をオン").assertIsEnabled()
        composeRule.runOnIdle {
            state.value = state.value.copy(shizukuState = ShizukuConnectionState.NOT_AVAILABLE)
        }
        composeRule.runOnIdle { state.value = state.value.copy(shizukuState = ShizukuConnectionState.READY) }
        composeRule.onNodeWithText("Shizuku連携完了").assertDoesNotExist()
    }

    @Test fun safetyConsent_privacyWarningAndAcceptanceRemainReachable() {
        var accepted = false
        setContent(ready.copy(consentAccepted = false), onAcceptSafety = { accepted = true })
        composeRule.onNode(
            hasText("撮影時は周囲のプライバシーと意思を尊重し", substring = true) and hasAnyAncestor(isDialog()),
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNode(
            hasText("盗撮など、他人の権利を侵害する目的では使用しないでください。", substring = true) and hasAnyAncestor(isDialog()),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        saveScreenshot("safety-respect-privacy.png")
        composeRule.onNodeWithText("理解して次へ").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(accepted) }
    }

    @Test fun setupGuidance_advancesFromConsentToNotificationsAndSound() {
        val state = androidx.compose.runtime.mutableStateOf(ready.copy(
            consentAccepted = false, notificationPermissionGranted = false, silentOrVibrate = false))
        var soundSettingsOpened = false
        setContent(state.value, stateProvider = { state.value },
            onAcceptSafety = { state.value = state.value.copy(consentAccepted = true) },
            onNotification = { state.value = state.value.copy(notificationPermissionGranted = true) },
            onSound = { soundSettingsOpened = true })
        composeRule.onNodeWithText("理解して次へ").performClick()
        composeRule.onNodeWithText("状態通知を許可する").performScrollTo().performClick()
        composeRule.onNodeWithText("現在は通常モードのため開始できません", substring = true).assertExists()
        composeRule.onNodeWithText("音の設定を開く").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(soundSettingsOpened) }
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
        saveScreenshot("setup-normal-ringer.png")
        composeRule.runOnIdle { state.value = state.value.copy(silentOrVibrate = true) }
        composeRule.onNodeWithText("準備ができました。下のボタンで無音補助を開始できます。").assertExists()
        composeRule.onNodeWithText("無音補助をオン").assertIsEnabled()
    }

    @Test fun missingConsent_blocksEnable() {
        setContent(ready.copy(consentAccepted = false))
        composeRule.onNodeWithText("Shizuku連携完了").assertIsDisplayed()
        composeRule.onNodeWithText("理解して次へ").assertIsEnabled()
        saveScreenshot("shizuku-connected-safety.png")
        composeRule.onNodeWithText("あとで").performClick()
        composeRule.onNodeWithText("Shizuku連携完了").assertDoesNotExist()
        composeRule.onNodeWithText("注意事項を確認する").performScrollTo().performClick()
        composeRule.onNodeWithText("Shizuku連携完了").assertIsDisplayed()
        composeRule.onNodeWithText("あとで").performClick()
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
    }

    @Test fun disabledNotification_blocksEnable_butNeverRestore() {
        setContent(ready.copy(notificationPermissionGranted = false, forceUseState = ForceUseState.MuteAssistActive))
        composeRule.onNodeWithText("通知設定を開く").assertExists()
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
        composeRule.onNodeWithText("純正状態に戻す").assertIsEnabled()
    }

    @Test fun privacy_isAvailableBeforeShizukuConnection() {
        setContent(MainUiState())
        composeRule.onNodeWithText("プライバシーポリシー").performScrollTo().performClick()
        composeRule.onNodeWithText("イズミアプリケーション", substring = true).assertExists()
        saveScreenshot("privacy-policy-top.png")
        composeRule.onNodeWithText("6. ポリシーの変更", substring = true).performScrollTo().assertIsDisplayed()
        saveScreenshot("privacy-policy-bottom.png")
        composeRule.onNodeWithText("閉じる").performClick()
    }

    @Test fun privacy_remainsAvailableWhileBusy() {
        setContent(ready.copy(isBusy = true))
        composeRule.onNodeWithText("プライバシーポリシー").performScrollTo().performClick()
        composeRule.onNodeWithText("6. ポリシーの変更", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("閉じる").performClick()
    }

    @Test fun privacy_keepsTheEndAndCloseReachableAtConfiguredFontScale() {
        var dismissed = false
        val expectedScale = expectedFontScale()
        composeRule.setContent {
            CameraMuteAssistTheme {
                PrivacyPolicyDialog(onDismiss = { dismissed = true })
            }
        }
        assertDialogFontScale(expectedScale)
        composeRule.onNodeWithText("6. ポリシーの変更", substring = true).performScrollTo().assertIsDisplayed()
        saveScreenshot("privacy-policy-font-scale-$expectedScale.png")
        composeRule.onNodeWithText("閉じる").assertIsDisplayed().performClick()
        assertTrue(dismissed)
    }

    @Test fun fontLicense_isAvailableOfflineBeforeShizukuConnection() {
        setContent(MainUiState())
        composeRule.onNodeWithText("フォントのライセンス").performScrollTo().performClick()
        composeRule.onNodeWithText("Noto Sans JP").assertExists()
        composeRule.onNodeWithText("SIL OPEN FONT LICENSE Version 1.1", substring = true).assertExists()
        composeRule.onNodeWithText("Copyright 2014-2021 Adobe", substring = true).assertExists()
        composeRule.onNodeWithText("閉じる").performClick()
        composeRule.onNodeWithText("Noto Sans JP").assertDoesNotExist()
    }

    @Test fun unverified_showsDeviceNotice_andCanEnable() {
        setContent(ready.copy(deviceUnverified = true))
        composeRule.onNodeWithText("この機種／OSビルドは未検証です").assertExists()
        composeRule.onNodeWithText("無音補助をオン").assertIsEnabled()
        saveScreenshot("simulated-unverified-ready.png")
    }

    @Test fun unverified_zeroWithoutRecord_doesNotClaimAppIsActive() {
        setContent(ready.copy(deviceUnverified = true, forceUseState = ForceUseState.UnmanagedUnforced))
        composeRule.onNodeWithText("変更前の音設定の記録がありません").assertExists()
        composeRule.onNodeWithText("無音補助中").assertDoesNotExist()
        composeRule.onNodeWithText("純正状態に戻す").assertIsNotEnabled()
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
        composeRule.onNodeWithText("元々強制音が設定されていない場合、別のアプリによる変更", substring = true).assertExists()
        composeRule.onNodeWithText("本アプリの変更後にデータ消去などで記録を失った場合", substring = true).assertExists()
        composeRule.onNodeWithText("本アプリで変更した後に記録を失った場合は、端末を再起動して設定と実際の音を確認してください。", substring = true).assertExists()
        composeRule.onNodeWithText("再起動で元に戻るとは限りません。", substring = true).assertExists()
        composeRule.onNodeWithText("画面上部の状態欄の案内に沿って確認してください。", substring = true).assertExists()
        composeRule.onNodeWithText("元の値を推測して変更せず", substring = true).assertExists()
        composeRule.onNodeWithText("必要に応じて元のアプリで戻し", substring = true).assertExists()
        composeRule.onNodeWithText("端末を再起動して、音設定を再確認してください。", substring = true).assertDoesNotExist()
        saveScreenshot("simulated-unverified-no-original.png")
    }

    @Test fun unverified_active_restoreRemainsAvailable_withoutConsent() {
        setContent(ready.copy(deviceUnverified = true, forceUseState = ForceUseState.MuteAssistActive,
            consentAccepted = false))
        composeRule.onNodeWithText("純正状態に戻す").assertIsEnabled()
        composeRule.onNodeWithText("無音補助をオン").assertIsNotEnabled()
    }

    @Test fun compatibilityHelp_separatesTestedDeviceFromUnverifiedCandidates() {
        setContent(ready.copy(deviceUnverified = true))
        composeRule.onNodeWithText("対応機種と復元の案内").performScrollTo().performClick()
        composeRule.onNodeWithText("Android 15以降で利用できます", substring = true).assertExists()
        composeRule.onNodeWithText("CPH2797_16.0.10.501(EX01)", substring = true).assertExists()
        composeRule.onNodeWithText("Play版の同じ音設定方式では、Find X9日本版とReno13 Aの指定ビルドで無音化・復元を確認しています。", substring = true).assertExists()
        composeRule.onNodeWithText("公開ソースから作成した本アプリの実際の音は未確認です。利用者自身で無音補助中と復元後の音を確認してください。", substring = true).assertExists()
        saveScreenshot("simulated-compatibility-help.png")
    }
}

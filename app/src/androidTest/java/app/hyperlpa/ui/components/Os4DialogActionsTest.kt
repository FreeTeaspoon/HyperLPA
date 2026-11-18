package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.ui.theme.HyperLpaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Os4DialogActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeActionsHaveEqualWidthsTwelveDpGapAndOneConfirmPerAcceptedClick() {
        val feedback = mutableListOf<HapticFeedbackType>()
        var cancels = 0
        var confirms = 0
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        feedback += hapticFeedbackType
                    }
                }) {
                    Box(Modifier.width(300.dp)) {
                        DialogActionRow(onCancel = { cancels++ }, cancelText = "Cancel", confirmText = "Confirm",
                            onConfirm = { confirms++ })
                    }
                }
            }
        }
        val cancel = compose.onNode(hasText("Cancel") and hasClickAction())
        val confirm = compose.onNode(hasText("Confirm") and hasClickAction())
        val a = cancel.getUnclippedBoundsInRoot()
        val b = confirm.getUnclippedBoundsInRoot()
        assertEquals((a.right - a.left).value, (b.right - b.left).value, .1f)
        // Layout rounds the 12dp gap to physical pixels at fractional densities.
        val pixelTolerance = with(compose.density) { 1.toDp().value }
        assertEquals(12f, (b.left - a.right).value, pixelTolerance)
        val minimumHeight = 50f - pixelTolerance
        assertTrue("Cancel height: ${a.bottom - a.top}", (a.bottom - a.top).value >= minimumHeight)
        assertTrue("Confirm height: ${b.bottom - b.top}", (b.bottom - b.top).value >= minimumHeight)
        cancel.performClick()
        confirm.performClick()
        assertEquals(1, cancels)
        assertEquals(1, confirms)
        assertEquals(listOf(HapticFeedbackType.Confirm, HapticFeedbackType.Confirm), feedback)
        cancel.performTouchInput { down(center); moveTo(center.copy(x = -1000f)); up() }
        assertEquals(2, feedback.size)
    }

    @Test fun disabledConfirmationHasNoAddedFeedback() {
        val feedback = mutableListOf<HapticFeedbackType>()
        compose.setContent {
            HyperLpaTheme(AppSettings(blurEnabled = false)) {
                CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { feedback += hapticFeedbackType }
                }) {
                    DialogActionRow(onCancel = {}, confirmText = "Confirm", confirmEnabled = false,
                        onConfirm = { error("Disabled action activated") })
                }
            }
        }
        compose.onNodeWithText("Confirm").assertIsNotEnabled().performTouchInput { click() }
        assertTrue(feedback.isEmpty())
    }
}

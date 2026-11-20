package com.smartdesk.companion.tablet

import com.smartdesk.companion.shared.BatteryAutomationConfig
import com.smartdesk.companion.shared.BatteryAutomationStatus
import com.smartdesk.companion.shared.BatteryProtectionStateMachine
import com.smartdesk.companion.shared.BatteryWebhookAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V6 Section 5 (Step 20):
 * Unit tests for the Smart Battery Protection hysteresis state machine.
 */
class BatteryProtectionStateMachineTest {

    private val enabledConfig = BatteryAutomationConfig(
        enabled = true,
        cutAt = 80,
        resumeAt = 25,
        offUrl = "https://example.com/off",
        onUrl = "https://example.com/on",
        method = "GET"
    )

    @Test
    fun `crossing 80 percent while charging sends OFF once and enters holding`() {
        val first = BatteryProtectionStateMachine.evaluate(
            config = enabledConfig,
            batteryLevel = 80,
            isCharging = true,
            previousLastCommand = BatteryAutomationStatus.COMMAND_UNKNOWN,
            previousState = BatteryAutomationStatus.STATE_IDLE
        )
        assertEquals(BatteryWebhookAction.SEND_OFF, first.action)
        assertEquals(BatteryAutomationStatus.COMMAND_OFF, first.nextLastCommand)
        assertEquals(BatteryAutomationStatus.STATE_HOLDING, first.nextState)

        // Subsequent tick at 81% must NOT re-send OFF
        val second = BatteryProtectionStateMachine.evaluate(
            config = enabledConfig,
            batteryLevel = 81,
            isCharging = false,
            previousLastCommand = first.nextLastCommand,
            previousState = first.nextState
        )
        assertEquals(BatteryWebhookAction.NONE, second.action)
        assertEquals(BatteryAutomationStatus.COMMAND_OFF, second.nextLastCommand)
        assertEquals(BatteryAutomationStatus.STATE_HOLDING, second.nextState)
    }

    @Test
    fun `crossing 25 percent while discharging sends ON once and enters charging`() {
        val first = BatteryProtectionStateMachine.evaluate(
            config = enabledConfig,
            batteryLevel = 25,
            isCharging = false,
            previousLastCommand = BatteryAutomationStatus.COMMAND_OFF,
            previousState = BatteryAutomationStatus.STATE_HOLDING
        )
        assertEquals(BatteryWebhookAction.SEND_ON, first.action)
        assertEquals(BatteryAutomationStatus.COMMAND_ON, first.nextLastCommand)
        assertEquals(BatteryAutomationStatus.STATE_CHARGING, first.nextState)

        // Subsequent tick at 26% while charging must NOT re-send ON
        val second = BatteryProtectionStateMachine.evaluate(
            config = enabledConfig,
            batteryLevel = 26,
            isCharging = true,
            previousLastCommand = first.nextLastCommand,
            previousState = first.nextState
        )
        assertEquals(BatteryWebhookAction.NONE, second.action)
        assertEquals(BatteryAutomationStatus.COMMAND_ON, second.nextLastCommand)
        assertEquals(BatteryAutomationStatus.STATE_CHARGING, second.nextState)
    }

    @Test
    fun `unknown start state between thresholds does nothing`() {
        val eval = BatteryProtectionStateMachine.evaluate(
            config = enabledConfig,
            batteryLevel = 55,
            isCharging = false,
            previousLastCommand = BatteryAutomationStatus.COMMAND_UNKNOWN,
            previousState = BatteryAutomationStatus.STATE_IDLE
        )
        assertEquals(BatteryWebhookAction.NONE, eval.action)
        assertEquals(BatteryAutomationStatus.COMMAND_UNKNOWN, eval.nextLastCommand)
        assertEquals(BatteryAutomationStatus.STATE_IDLE, eval.nextState)
    }

    @Test
    fun `disabling while lastCommand was OFF triggers failsafe SEND_ON`() {
        val disabledConfig = enabledConfig.copy(enabled = false)
        val eval = BatteryProtectionStateMachine.evaluate(
            config = disabledConfig,
            batteryLevel = 62,
            isCharging = false,
            previousLastCommand = BatteryAutomationStatus.COMMAND_OFF,
            previousState = BatteryAutomationStatus.STATE_HOLDING
        )
        assertEquals(BatteryWebhookAction.SEND_ON, eval.action)
        assertEquals(BatteryAutomationStatus.COMMAND_ON, eval.nextLastCommand)
        assertEquals(BatteryAutomationStatus.STATE_IDLE, eval.nextState)
    }

    @Test
    fun `sanitizeThresholds enforces minimum 20 point gap`() {
        val (cut, resume) = BatteryProtectionStateMachine.sanitizeThresholds(60, 50)
        assertEquals(60, cut)
        assertEquals(40, resume)
    }
}

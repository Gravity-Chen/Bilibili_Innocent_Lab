package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate.Condition
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationEntryGate.Evidence
import org.junit.Assert.*
import org.junit.Test

class FrameInterpolationEntryGateTest {
    private val complete = Evidence(true, true, true, true, true, true, true, true, true)

    @Test fun `entry opens only when every condition is evidenced`() {
        assertTrue(FrameInterpolationEntryGate.evaluate(complete).open)
        val missing = listOf(
            complete.copy(nativeReady = false) to Condition.NATIVE_READY,
            complete.copy(modelVerified = false) to Condition.MODEL_VERIFIED,
            complete.copy(mediaPermit = false) to Condition.MEDIA_PERMIT,
            complete.copy(verifiedClock = false) to Condition.VERIFIED_CLOCK,
            complete.copy(verifiedCapture = false) to Condition.VERIFIED_CAPTURE,
            complete.copy(presentationOwned = false) to Condition.PRESENTATION_OWNED,
            complete.copy(ownershipAndRecovery = false) to Condition.OWNERSHIP_AND_RECOVERY,
            complete.copy(inferenceWithinBudget = false) to Condition.INFERENCE_WITHIN_BUDGET,
            complete.copy(deviceValidated = false) to Condition.DEVICE_VALIDATED)
        missing.forEach { (evidence, condition) ->
            val verdict = FrameInterpolationEntryGate.evaluate(evidence)
            assertFalse(verdict.open)
            assertEquals(listOf(condition), verdict.missing)
        }
    }

    @Test fun `default evidence carries only what this repository proves`() {
        val verdict = FrameInterpolationEntryGate.evaluate(Evidence())
        assertFalse(Condition.OWNERSHIP_AND_RECOVERY in verdict.missing)
        assertTrue(Condition.DEVICE_VALIDATED in verdict.missing)
    }

    @Test fun `production entry is closed and names every unproven condition`() {
        val verdict = FrameInterpolationEntryGate.production()
        assertFalse(verdict.open)
        assertFalse(FrameInterpolationEntryGate.entryOpen())
        assertTrue(verdict.missing.containsAll(listOf(Condition.MODEL_VERIFIED, Condition.MEDIA_PERMIT,
            Condition.VERIFIED_CLOCK, Condition.VERIFIED_CAPTURE, Condition.PRESENTATION_OWNED,
            Condition.INFERENCE_WITHIN_BUDGET, Condition.DEVICE_VALIDATED)))
        assertTrue(Condition.NATIVE_READY in verdict.missing)
    }
}

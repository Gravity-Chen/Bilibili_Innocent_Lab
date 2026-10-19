package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class FrameCaptureTimingContractTest {
    @Test fun `host clock observation is taken after the potentially blocking texture latch`() {
        val callback = SourceContract.read("hook/feature/interpolation/FrameGlCapture.kt")
            .after("private fun onFrameAvailable(").before("private fun read(")
        val latch = callback.indexOf("runCatching { texture.updateTexImage() }")
        val observation = callback.indexOf("val arrival = System.nanoTime()")
        val delivery = callback.indexOf("listener.onFrame(texture.timestamp, arrival)")
        assertTrue("callback notification time cannot describe a newer frame latched during a wait", latch >= 0 && observation > latch)
        assertTrue("the completed latch and its timestamp are delivered together", delivery > observation)
    }
}

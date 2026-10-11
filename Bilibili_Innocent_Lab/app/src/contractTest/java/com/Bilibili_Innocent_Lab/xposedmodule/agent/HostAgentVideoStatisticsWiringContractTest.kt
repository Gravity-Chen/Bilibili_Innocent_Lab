package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

/** DTO 的统计读取与能力必须使用同一实际回复载体，不能凭别的协议存在 Stat 就启用。 */
class HostAgentVideoStatisticsWiringContractTest {
    @Test fun capabilityAndDtoUseTheActualDetailArcAndExcludeDisplayText() {
        val rpc = SourceContract.read("agent/host/HostAgentRpc.kt")
        val detail = rpc.after("internal class HostAgentDetailQuery").before("internal class HostAgentVideoStatistics")
        assertTrue(detail.contains("HostAgentVideoStatistics.resolve(getArc.returnType)"))
        assertTrue(detail.contains("val canReadStatistics: Boolean get() = statistics != null"))
        assertTrue(detail.contains("statistics?.read(arc) ?: HostAgentVideoStatistics.unknown()"))
        val statistics = rpc.after("internal class HostAgentVideoStatistics").before("internal fun hostGetter")
        assertFalse(statistics.contains("getText"))
        assertFalse(statistics.contains("getPureText"))
        assertFalse(statistics.contains("hostText("))
        val runtime = SourceContract.read("agent/host/HostAgentRuntime.kt")
        assertTrue(runtime.contains("put(\"get_video_metrics\", rpc.details?.canReadStatistics == true)"))
    }
}

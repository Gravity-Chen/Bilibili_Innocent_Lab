package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import tv.danmaku.bili.update.model.BiliUpgradeInfo

class Adapter915CandidatesTest {
    class Network { fun a(context: Context?): BiliUpgradeInfo? = null }
    class WrongNetwork { fun a(context: Context?): String = "" }
    class Quality { companion object { @JvmStatic fun a(): Int = 32 } }
    class WrongQuality { companion object { @JvmStatic fun a(): String = "" } }
    private val parent = javaClass.classLoader!!
    private fun mapped(names: Map<String, Class<*>>) = object : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = names[name] ?: super.loadClass(name, resolve)
    }

    @Test fun `new 915 ABI takes precedence while unrelated new owner falls through to 914`() {
        for ((network, quality) in listOf("ss1.c" to "ju1.h", "rs1.c" to "iu1.h")) {
            val host = mapped(mapOf(network to Network::class.java, quality to Quality::class.java))
            assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(host)?.className)
            assertEquals(Quality::class.java.name, VersionAdapter.locateDefaultVideoQuality(host)?.defaultQualityMethod?.className)
        }
        val collision = mapped(mapOf("rs1.c" to WrongNetwork::class.java, "ss1.c" to WrongNetwork::class.java,
            "iu1.h" to WrongQuality::class.java, "ju1.h" to WrongQuality::class.java))
        assertEquals("Dr1.c", VersionAdapter.locateBlockUpdate(collision)?.className)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(collision)?.defaultQualityMethod?.className)
    }
}

package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostViewResourceNames
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostViewResourceNamesInstrumentedTest {
    @Suppress("DEPRECATION")
    private class Table(base: Resources, val entries: MutableMap<Int, String>) :
        Resources(base.assets, base.displayMetrics, base.configuration) {
        var queries = 0
        override fun getResourceEntryName(resid: Int): String {
            queries++
            return entries[resid] ?: throw NotFoundException()
        }
    }

    @Test fun repeatedFramesReuseNamesButKeepResourceTablesAndRecycledIdsIndependent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val id = 0x7f010001
            val other = 0x7f010002
            val first = Table(context.resources, mutableMapOf(id to "cover", other to "nav_top_bar"))
            val second = Table(context.resources, mutableMapOf(id to "dy_list"))
            val view = View(object : ContextWrapper(context) {
                override fun getResources() = first
            }).apply { this.id = id }
            repeat(300) {
                assertEquals("cover", HostViewResourceNames.name(view))
                assertEquals("dy_list", HostViewResourceNames.name(second, id))
            }
            assertEquals("Scrolling must not reopen the resource table for each frame", 1, first.queries)
            assertEquals(1, second.queries)
            view.id = other
            assertEquals("nav_top_bar", HostViewResourceNames.name(view))
            view.id = id
            assertEquals("cover", HostViewResourceNames.name(view))
            assertEquals(2, first.queries)
        }
    }

    @Test fun generatedIdsSkipAssetLookupAndMissingResourcesCanAppearLater() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val table = Table(instrumentation.targetContext.resources, mutableMapOf())
            for (id in listOf(View.NO_ID, 0, View.generateViewId()))
                assertEquals("", HostViewResourceNames.name(table, id))
            assertEquals(0, table.queries)
            val id = 0x7f010001
            HostViewResourceNames.inPass {
                repeat(300) { assertEquals("", HostViewResourceNames.name(table, id)) }
            }
            assertEquals("Invalid host ids must not allocate an exception for every container", 1, table.queries)
            table.entries[id] = "video_container"
            assertEquals("video_container", HostViewResourceNames.name(table, id))
            assertEquals(2, table.queries)
        }
    }
}

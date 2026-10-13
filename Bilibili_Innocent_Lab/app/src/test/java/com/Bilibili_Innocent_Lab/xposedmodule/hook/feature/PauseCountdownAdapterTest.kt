package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PauseCountdownAdapterTest {
    private fun access(owner: Class<*>) = requireNotNull(PauseCountdownAdapter.resolve(owner,
        Service::class.java, Source::class.java, Reportable::class.java, Context::class.java,
        Active::class.java.getMethod("ensureActive", Context::class.java), Unit))

    @Test fun `normal bar missing source failures and resumed coroutines keep the host behavior`() {
        val service = Service(SourceImpl(false))
        val receiver = OldCountdown(service)
        val guard = access(OldCountdown::class.java)
        assertFalse(guard.allows(receiver, Unit))
        service.source = null
        assertFalse(guard.allows(receiver, Unit))
        service.source = SourceImpl(true)
        assertTrue(guard.allows(receiver, Unit))
        assertSame(Unit, guard.emptyResult())
        receiver.label = 1
        assertFalse(guard.allows(receiver, Unit))
        receiver.label = 0
        assertFalse(guard.allows(receiver, IllegalStateException("host failure")))
        receiver.context.active = false
        assertFalse(guard.allows(receiver, Unit))
        receiver.context.active = true
        service.source = object : Source { override fun getHasAdContent(): Boolean = error("source failed") }
        assertFalse(guard.allows(receiver, Unit))
    }

    @Test fun `new captured ad source wins over a different current service source`() {
        val receiver = NewCountdown(Service(SourceImpl(true)), SourceImpl(false))
        val guard = access(NewCountdown::class.java)
        assertFalse(guard.allows(receiver, Unit))
        receiver.captured = SourceImpl(true)
        assertTrue(guard.allows(receiver, Unit))
        receiver.captured = object : Reportable {}
        assertFalse(guard.allows(receiver, Unit))
        receiver.captured = null
        assertFalse(guard.allows(receiver, Unit))
    }

    @Test fun `continuations and legacy helper aliases do not multiply request family coverage`() {
        assertEquals(setOf("ad-api", "theseus"), PauseCountdownAdapter.requestFamilies(listOf(
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$1",
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$2",
            "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService\$requestPausedPageData\$2",
            "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService\$requestPausedPageResult\$1")))
    }

    interface Reportable
    interface Source : Reportable { fun getHasAdContent(): Boolean }
    class SourceImpl(private val ad: Boolean) : Source { override fun getHasAdContent() = ad }
    class Service(@JvmField var source: Source?)
    class Context(var active: Boolean = true)
    class Active {
        companion object {
            @JvmStatic fun ensureActive(context: Context) {
                if (!context.active) throw CancellationException("host cancelled")
            }
        }
    }
    class OldCountdown(@JvmField val `this$0`: Service) {
        @JvmField var label = 0
        val context = Context()
        fun invokeSuspend(value: Any): Any = Unit
    }
    class NewCountdown(@JvmField val `this$0`: Service, @JvmField var captured: Reportable?) {
        @JvmField var label = 0
        val context = Context()
        fun invokeSuspend(value: Any): Any = Unit
    }
}

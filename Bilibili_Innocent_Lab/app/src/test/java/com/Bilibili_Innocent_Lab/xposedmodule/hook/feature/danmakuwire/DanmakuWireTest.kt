package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufBuilderPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** wire 引擎（JVM 基准）金样本：向量与 Rust `lib.rs` 的同名测试一一对应，钉死双侧语义一致。 */
class DanmakuWireTest {
    private val fields = DanmakuWireFields(
        elemsField = 1, weightField = 8, colorfulField = 11, contentField = 10,
        colorfulSrcField = 4, colorfulTypeField = 2,
    )

    private fun elem(weight: Int?, colorful: Int?, content: String?) =
        WireElem(weight, colorful, content).toByteArray().let { payload ->
            DanmakuWire.ByteArrayOutput().also { writeBytesField(it, 1, payload) }.toByteArray()
        }

    private fun src(type: Int?) =
        WireColorful(type).toByteArray().let { payload ->
            DanmakuWire.ByteArrayOutput().also { writeBytesField(it, 4, payload) }.toByteArray()
        }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = DanmakuWire.ByteArrayOutput()
        for (p in parts) out.write(p, 0, p.size)
        return out.toByteArray()
    }

    private fun unknownTop(): ByteArray =
        DanmakuWire.ByteArrayOutput().also {
            writeBytesField(it, 99, byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()))
        }.toByteArray()

    @Test
    fun scanReadsMixedElementsAndSources() {
        val reply = concat(
            unknownTop(),
            elem(7, null, "甲"),
            elem(null, 60001, null),
            src(60001),
            src(null),
        )
        val scan = DanmakuWire.scan(reply, fields)!!
        assertEquals(2, scan.elems.size)
        assertEquals(7, scan.elems[0].weight)
        assertEquals(null, scan.elems[0].colorful)
        assertTrue(scan.elems[0].hasContent)
        assertEquals("甲", String(reply, scan.elems[0].contentStart, scan.elems[0].contentLen, Charsets.UTF_8))
        assertEquals(null, scan.elems[1].weight)
        assertEquals(60001, scan.elems[1].colorful)
        assertFalse(scan.elems[1].hasContent)
        assertEquals(listOf<Int?>(60001, null), scan.srcKinds)
    }

    @Test
    fun scanRejectsTruncatedInputAndBadFields() {
        // 截断：只有 tag 没有长度。
        assertNull(DanmakuWire.scan(byteArrayOf(0x0a, 0x7f), fields))
        // 字段号冲突。
        val bad = fields.copy(weightField = fields.colorfulField)
        assertNull(DanmakuWire.scan(unknownTop(), bad))
        // 字段号越界。
        assertNull(DanmakuWire.scan(unknownTop(), fields.copy(elemsField = 536_870_912)))
    }

    @Test
    fun rewriteDropsRecolorsAndPreservesUnknownBytes() {
        val reply = concat(
            unknownTop(),
            elem(7, null, "keep-me"),
            elem(1, 60001, "drop"),
            elem(5, 60001, null),
            src(60001),
            src(0),
        )
        val keep = booleanArrayOf(true, false, true)
        val recolor = booleanArrayOf(false, false, true)
        val keepSrc = booleanArrayOf(false, true)
        val rewritten = DanmakuWire.rewrite(reply, fields, keep, recolor, keepSrc)!!
        val rescan = DanmakuWire.scan(rewritten, fields)!!
        assertEquals(2, rescan.elems.size)
        assertEquals(1, rescan.srcKinds.size)
        assertEquals(7, rescan.elems[0].weight)
        assertEquals(5, rescan.elems[1].weight)
        assertEquals(0, rescan.elems[1].colorful)
        assertEquals(0, rescan.srcKinds[0])
        // 未知字段逐字节保留。
        val marker = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        var found = false
        for (i in 0..rewritten.size - 4) {
            if (rewritten.copyOfRange(i, i + 4).contentEquals(marker)) found = true
        }
        assertTrue(found)
        // 无改动 = 逐字节原样输出。
        val allKeep = BooleanArray(3) { true }
        val noRecolor = BooleanArray(3) { false }
        val allSrc = BooleanArray(2) { true }
        assertArrayEquals(reply, DanmakuWire.rewrite(reply, fields, allKeep, noRecolor, allSrc))
    }

    @Test
    fun maskLengthMismatchIsRejected() {
        val reply = concat(elem(5, 60001, null))
        assertNull(DanmakuWire.rewrite(reply, fields, booleanArrayOf(true, true), BooleanArray(1) { false }, BooleanArray(0)))
        assertNull(DanmakuWire.rewrite(reply, fields, booleanArrayOf(true), booleanArrayOf(false, false), BooleanArray(0)))
    }

    @Test
    fun flattenLayoutMatchesContract() {
        val reply = concat(elem(Int.MIN_VALUE, 60001, "t"), src(null))
        val scan = DanmakuWire.scan(reply, fields)!!
        val flat = DanmakuWire.flatten(scan)
        assertEquals(2 + 5 + 2, flat.size)
        assertEquals(1, flat[0])
        assertEquals(1, flat[1])
        assertEquals(Int.MIN_VALUE, flat[2])
        assertEquals(60001, flat[3])
        assertEquals(0b111, flat[4])
        // roundtrip。
        val back = DanmakuWire.fromFlattened(flat)!!
        assertEquals(scan.elems[0], back.elems[0])
        assertEquals(scan.srcKinds, back.srcKinds)
    }
}

/** 探针 + 端到端自检：在带真实 wire 编解码的 fixture 上过全链路（含宿主 parseFrom 核条数）。 */
class DanmakuWirePlanTest {
    @Test
    fun probeDiscoversFieldsAndSelfTestPassesEndToEnd() {
        val plan = DanmakuWirePlan.resolve(
            replyClass = WireReply::class.java,
            elemClass = WireElem::class.java,
            colorfulClass = WireColorful::class.java,
            replyBuilder = ProtobufBuilderPlan.resolve(WireReply::class.java)!!,
            elemBuilder = ProtobufBuilderPlan.resolve(WireElem::class.java)!!,
            vipValue = 60001,
            listCountOf = { parsed -> (parsed as WireReply).elems.size },
            logError = { _, _ -> },
        )
        assertNotNull(plan)
        assertEquals(
            DanmakuWireFields(1, 8, 11, 10, 4, 2),
            plan!!.fields,
        )
        // 序列化/解析可用（供运行期 wire 路径使用）。
        val reply = WireReply(
            listOf(WireElem(9), WireElem(1, 60001, "x")),
            listOf(WireColorful(60001)),
            flags = 77,
        )
        val bytes = plan.serialize(reply)!!
        val parsed = plan.parse(bytes) as WireReply
        assertEquals(2, parsed.elems.size)
        assertEquals(77, parsed.getFlags())
    }

    @Test
    fun probeFailsGracefullyWithoutCodecMembers() {
        // WireElem 没有静态 parseFrom：reply 侧必须整体拒绝（返回 null），绝不猜。
        val plan = DanmakuWirePlan.resolve(
            replyClass = WireElem::class.java,
            elemClass = WireElem::class.java,
            colorfulClass = WireColorful::class.java,
            replyBuilder = ProtobufBuilderPlan.resolve(WireElem::class.java)!!,
            elemBuilder = ProtobufBuilderPlan.resolve(WireElem::class.java)!!,
            vipValue = 60001,
            listCountOf = { null },
            logError = { _, _ -> },
        )
        assertNull(plan)
    }
}

package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test

class KotlinMossResponseHandlerProxyTest {
    data class JavaReply(val cards: List<String>, val attributes: Map<String, String>)
    data class KotlinReply(val message: JavaReply)
    interface Handler { fun onNext(reply: Any?) }
    class Endpoint {
        @Suppress("UNUSED_PARAMETER")
        fun mainList(request: Any, serializer: SerializationStrategy<Any>, decoder: DeserializationStrategy<Any>,
            handler: Handler, protoBuf: ProtoBuf) = Unit
    }
    private val endpoint = Endpoint::class.java.getDeclaredMethod("mainList", Any::class.java,
        SerializationStrategy::class.java, DeserializationStrategy::class.java, Handler::class.java, ProtoBuf::class.java)
    private fun key() = KotlinMossResponseHandlerProxy.Key(endpoint, JavaReply::class.java, this, endpoint)

    private class Codec(val failDecode: Boolean = false, val corrupt: Boolean = false) {
        var encodes = 0
        var decodes = 0
        private val messages = ArrayList<JavaReply>()
        private fun store(message: JavaReply): ByteArray { messages += message; return byteArrayOf((messages.size - 1).toByte()) }
        private fun read(bytes: ByteArray) = messages[bytes[0].toInt()]
        val bridge = KotlinMossReplyBridge(
            encode = { encodes++; store((it as KotlinReply).message) },
            parseJava = { read(it).copy(cards = read(it).cards.toList(), attributes = read(it).attributes.toMap()) },
            toBytes = { store(it as JavaReply) },
            decode = { bytes ->
                decodes++
                check(!failDecode) { "decode failed" }
                val value = read(bytes)
                KotlinReply(value.copy(cards = if (corrupt) listOf("restored") else value.cards.toList(),
                    attributes = value.attributes.entries.reversed().associate { it.key to it.value }))
            }
        )
    }

    @Test fun `confirmed receipts run after the final new callback and map order is structural`() {
        val codec = Codec()
        val original = KotlinReply(JavaReply(listOf("drop", "keep"), linkedMapOf("a" to "1", "b" to "2")))
        val events = mutableListOf<String>()
        var delivered: Any? = null
        val delegate = object : Handler {
            override fun onNext(reply: Any?) { assertTrue(events.isEmpty()); delivered = reply }
        }
        val proxy = KotlinMossResponseHandlerProxy.wrap(Handler::class.java, delegate, key(), codec.bridge,
            failed = { throw AssertionError(it) }, transform = { it }, confirmedTransform = { source ->
                val value = source as JavaReply
                KotlinMossResponseHandlerProxy.ConfirmedReply(value.copy(cards = listOf("keep"))) { events += "applied:1" }
            }) as Handler
        proxy.onNext(original)
        assertNotSame(original, delivered)
        assertEquals(listOf("keep"), (delivered as KotlinReply).message.cards)
        assertEquals(original.message.attributes, (delivered as KotlinReply).message.attributes)
        assertEquals(listOf("applied:1"), events)
    }

    @Test fun `failed or corrupt decode keeps the original callback without a confirmed receipt`() {
        for (codec in listOf(Codec(failDecode = true), Codec(corrupt = true))) {
            val original = KotlinReply(JavaReply(listOf("drop"), emptyMap()))
            var delivered: Any? = null
            var applied = 0
            var errors = 0
            val delegate = object : Handler { override fun onNext(reply: Any?) { delivered = reply } }
            val proxy = KotlinMossResponseHandlerProxy.wrap(Handler::class.java, delegate, key(), codec.bridge,
                failed = { errors++ }, transform = { it }, confirmedTransform = { source ->
                    KotlinMossResponseHandlerProxy.ConfirmedReply((source as JavaReply).copy(cards = emptyList())) { applied++ }
                }) as Handler
            proxy.onNext(original)
            assertSame(original, delivered)
            assertEquals(0, applied)
            assertEquals(1, errors)
        }
    }

    @Test fun `unchanged confirmed callback retains identity with one encode and no decoder`() {
        val codec = Codec()
        val original = KotlinReply(JavaReply(listOf("keep"), emptyMap()))
        var delivered: Any? = null
        var applied = 0
        val delegate = object : Handler { override fun onNext(reply: Any?) { delivered = reply } }
        val proxy = KotlinMossResponseHandlerProxy.wrap(Handler::class.java, delegate, key(), codec.bridge,
            failed = { throw AssertionError(it) }, transform = { it }, confirmedTransform = {
                KotlinMossResponseHandlerProxy.ConfirmedReply(it) { applied++ }
            }) as Handler
        proxy.onNext(original)
        assertSame(original, delivered)
        assertEquals(1, codec.encodes)
        assertEquals(0, codec.decodes)
        assertEquals(0, applied)
    }

    @Test fun `merged steps confirm only after one final delivery and host exceptions keep their type`() {
        val codec = Codec()
        val original = KotlinReply(JavaReply(listOf("first", "second", "keep"), emptyMap()))
        val events = mutableListOf<String>()
        val delegate = object : Handler {
            override fun onNext(reply: Any?) {
                assertTrue(events.isEmpty())
                assertEquals(listOf("keep"), (reply as KotlinReply).message.cards)
            }
        }
        fun wrap(target: Handler, name: String) = KotlinMossResponseHandlerProxy.wrap(Handler::class.java,
            target, key(), codec.bridge, failed = { throw AssertionError(it) }, transform = { it }, confirmedTransform = { source ->
                val value = source as JavaReply
                KotlinMossResponseHandlerProxy.ConfirmedReply(value.copy(cards = value.cards.filterNot { it == name })) { events += name }
            }) as Handler
        wrap(wrap(delegate, "first"), "second").onNext(original)
        assertEquals(listOf("second", "first"), events)
        events.clear()
        val failure = IllegalStateException("host")
        val throwing = object : Handler { override fun onNext(reply: Any?) { throw failure } }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { wrap(throwing, "first").onNext(original) })
        assertTrue(events.isEmpty())
    }
}

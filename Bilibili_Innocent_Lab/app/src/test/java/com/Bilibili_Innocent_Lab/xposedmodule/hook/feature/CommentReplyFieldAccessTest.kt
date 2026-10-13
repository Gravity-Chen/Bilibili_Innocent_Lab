package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Field

class CommentReplyFieldAccessTest {
    @Test
    fun selectsOnlyTheExactReplyIdAndIgnoresOtherActionFields() {
        val reply = Button(REPLY_ID)
        val binding = Binding(Button(REPLY_ID + 1), reply, Button(REPLY_ID + 2))

        val field = uniqueCommentReplyField(fields, binding, ::isReply)

        assertSame(reply, field?.get(binding))
    }

    @Test
    fun permitsAliasedFieldsOnlyWhenTheyReferToTheSameReplyObject() {
        val reply = Button(REPLY_ID)
        val binding = Binding(reply, reply)

        val field = uniqueCommentReplyField(fields, binding, ::isReply)

        assertSame(reply, field?.get(binding))
        assertSame(reply, access().read(binding))
    }

    @Test
    fun rejectsDifferentReplyObjectsWithTheSameResourceId() {
        val binding = Binding(Button(REPLY_ID), Button(REPLY_ID))

        assertNull(uniqueCommentReplyField(fields, binding, ::isReply))
        assertNull(access().read(binding))
    }

    @Test
    fun anEmptyFirstBindingDoesNotPreventResolvingALaterOne() {
        val access = access()
        assertNull(access.read(Binding()))
        val reply = Button(REPLY_ID)

        assertSame(reply, access.read(Binding(b = reply)))
    }

    @Test
    fun anAmbiguousFirstBindingDoesNotPreventResolvingALaterOne() {
        val access = access()
        assertNull(access.read(Binding(Button(REPLY_ID), Button(REPLY_ID))))
        val reply = Button(REPLY_ID)

        assertSame(reply, access.read(Binding(a = reply)))
    }

    @Test
    fun rebindingReadsTheCurrentInstanceInsteadOfThePreviousReplyObject() {
        val access = access()
        val previous = Button(REPLY_ID)
        val current = Button(REPLY_ID)
        assertSame(previous, access.read(Binding(a = previous)))

        assertSame(current, access.read(Binding(a = current)))
    }

    @Test
    fun aNewBindingMustBeCheckedForAmbiguityAfterAUniqueBindingWasCached() {
        val access = access()
        assertSame(firstReply, access.read(Binding(a = firstReply)))

        assertNull(access.read(Binding(Button(REPLY_ID), Button(REPLY_ID))))
    }

    @Test
    fun anInvalidatedCachedFieldIsReplacedByTheCurrentMatchingField() {
        val old = Button(REPLY_ID)
        val current = Button(REPLY_ID + 1)
        val binding = Binding(old, current)
        val access = access()
        assertSame(old, access.read(binding))
        old.id = REPLY_ID + 2
        current.id = REPLY_ID

        assertSame(current, access.read(binding))
    }

    @Test
    fun aChangedResourceIdentityRecalibratesTheCachedField() {
        var expectedId = REPLY_ID
        val first = Button(REPLY_ID)
        val second = Button(REPLY_ID + 1)
        val binding = Binding(first, second)
        val access = CommentReplyFieldAccess(fields) { (it as? Button)?.id == expectedId }
        assertSame(first, access.read(binding))
        expectedId = REPLY_ID + 1

        assertSame(second, access.read(binding))
    }

    private val fields: List<Field> = listOf("a", "b", "c").map { name ->
        Binding::class.java.getDeclaredField(name).apply { isAccessible = true }
    }
    private val firstReply = Button(REPLY_ID)

    private fun access() = CommentReplyFieldAccess(fields, ::isReply)
    private fun isReply(value: Any) = (value as? Button)?.id == REPLY_ID

    private class Button(var id: Int)
    private class Binding(var a: Button? = null, var b: Button? = null, var c: Button? = null)

    private companion object {
        const val REPLY_ID = 41
    }
}

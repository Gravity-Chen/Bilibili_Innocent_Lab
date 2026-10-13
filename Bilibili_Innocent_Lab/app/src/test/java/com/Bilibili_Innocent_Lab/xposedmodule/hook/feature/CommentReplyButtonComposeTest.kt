package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNUSED_PARAMETER")
class CommentReplyButtonComposeTest {
    @Test
    fun selectsTheUniqueRendererWithoutDependingOnItsName() {
        val method = renderer(UniqueRenderer::class.java)

        assertEquals("z", method?.name)
        assertEquals(UniqueRenderer::class.java, method?.declaringClass)
        assertEquals(
            listOf(Button::class.java, String::class.java, Composer::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            method?.parameterTypes?.toList()
        )
    }

    @Test
    fun ignoresAnOtherwiseMatchingStaticMethod() {
        val method = renderer(RendererWithStaticDecoy::class.java)

        assertEquals("render", method?.name)
        assertEquals(RendererWithStaticDecoy::class.java, method?.declaringClass)
    }

    @Test
    fun rejectsTwoMatchingMethodsInsteadOfChoosingByDeclarationOrder() {
        assertNull(renderer(AmbiguousRenderer::class.java))
    }

    @Test
    fun rejectsNonVoidMissingRepeatedAndMisplacedComposerShapes() {
        assertNull(renderer(WrongShapes::class.java))
    }

    @Test
    fun requiresTheExactButtonAndComposerClasses() {
        assertNull(renderer(UniqueRenderer::class.java, OtherButton::class.java))
        assertNull(renderer(UniqueRenderer::class.java, composer = OtherComposer::class.java))
    }

    @Test
    fun ignoresACompilerBridgeEvenWhenItsErasedSignatureMatches() {
        val owner = ConcreteComposerRenderer::class.java
        assertTrue(owner.declaredMethods.any { method ->
            method.isBridge && method.isSynthetic && method.parameterTypes.contentEquals(
                arrayOf(Button::class.java, Composer::class.java)
            )
        })

        assertNull(renderer(owner))
    }

    @Test
    fun doesNotTreatAnInheritedRendererAsADeclaredCandidate() {
        assertNull(renderer(InheritedRenderer::class.java))
    }

    private fun renderer(
        owner: Class<*>,
        button: Class<*> = Button::class.java,
        composer: Class<*> = Composer::class.java
    ) = commentReplyButtonRenderer(owner, button, composer)

    private enum class Button { TIME_AND_IP, REPLY, LIKE, DISLIKE, MORE }
    private enum class OtherButton { REPLY }
    private interface Composer
    private interface OtherComposer
    private class ConcreteComposer : Composer

    private class UniqueRenderer {
        fun z(button: Button, model: String, composer: Composer, changed: Int, defaults: Int) = Unit
        fun unused(button: Any, composer: Composer) = Unit
    }

    private class RendererWithStaticDecoy {
        fun render(button: Button, composer: Composer) = Unit

        companion object {
            @JvmStatic
            fun decoy(button: Button, composer: Composer) = Unit
        }
    }

    private class AmbiguousRenderer {
        fun a(button: Button, composer: Composer) = Unit
        fun b(button: Button, composer: Composer, changed: Int) = Unit
    }

    private class WrongShapes {
        fun nonVoid(button: Button, composer: Composer): Int = 1
        fun boxedUnit(button: Button, composer: Composer): Unit? = Unit
        fun missingComposer(button: Button) = Unit
        fun repeatedComposer(button: Button, first: Composer, second: Composer) = Unit
        fun misplacedButton(composer: Composer, button: Button) = Unit
        fun differentButton(button: OtherButton, composer: Composer) = Unit
        fun erasedButton(button: Any, composer: Composer) = Unit
        fun noArguments() = Unit
    }

    private interface GenericRenderer<T : Composer> {
        fun render(button: Button, composer: T)
    }

    private class ConcreteComposerRenderer : GenericRenderer<ConcreteComposer> {
        override fun render(button: Button, composer: ConcreteComposer) = Unit
    }

    private open class BaseRenderer {
        fun render(button: Button, composer: Composer) = Unit
    }

    private class InheritedRenderer : BaseRenderer()
}

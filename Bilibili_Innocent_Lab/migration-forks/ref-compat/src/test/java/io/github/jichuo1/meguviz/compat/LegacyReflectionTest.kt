package io.github.jichuo1.meguviz.compat

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class LegacyReflectionTest {
    private open class Parent { private fun inherited(value: Int) = value + 10 }
    private class Target(private var value: Int) : Parent() {
        var entries = 0
        private fun read() = value
        private fun read(value: String) = value
        private fun throwsOnce(): Nothing { entries++; error("target") }
    }

    @Test fun instanceResolverAndCopyHaveSeparateOwnership() {
        val first = Target(3)
        val second = Target(9)
        val resolver = first.asResolver().firstMethodOrNull { name = "read"; emptyParameters() }!!
        assertEquals(3, resolver.invokeQuietly<Int>())
        assertEquals(9, resolver.copy().of(second).invokeQuietly<Int>())
        assertNull(resolver.copy().invokeQuietly())
        assertEquals(3, resolver.invokeQuietly<Int>())
    }

    @Test fun fieldReadWriteAndCopyUseExplicitReceiver() {
        val value = Target(1)
        val field = value.asResolver().firstFieldOrNull { name = "value" }!!
        assertEquals(1, field.getQuietly<Int>())
        field.setQuietly(7)
        assertEquals(7, field.copy().of(value).getQuietly<Int>())
        assertNull(field.copy().getQuietly())
    }

    @Test fun exactZeroArityAndInheritedSelectionArePreserved() {
        val value = Target(2)
        assertNull(Target::class.resolve().firstMethodOrNull { name = "inherited"; parameters(Int::class) })
        val inherited = Target::class.resolve().firstMethodOrNull { name = "inherited"; parameters(Int::class); superclass() }!!
        assertEquals(15, inherited.of(value).invokeQuietly<Int>(5))
        assertEquals(0, Target::class.resolve().firstMethodOrNull { name = "read"; emptyParameters() }!!.self.parameterCount)
    }

    @Test fun primitiveAndBoxedTypePoliciesRemainSeparate() {
        assertSame(Integer.TYPE, classOf<Int>())
        assertSame(java.lang.Integer::class.java, classOf<Int>(false))
        assertTrue(String::class.java isSubclassOf CharSequence::class)
        assertTrue(Target::class isNotSubclassOf String::class)
    }

    @Test fun constructorAndLegacyArgumentSelectionAreRealCalls() {
        val constructor = Target::class.resolve().firstConstructorOrNull { parameters(Int::class) }!!
        assertEquals(4, constructor.create(4).asResolver().firstMethodOrNull { name = "read"; emptyParameters() }!!.invokeQuietly<Int>())
        assertNotNull(Target::class.createInstanceOrNull(6))
        assertNull(Target::class.createInstanceOrNull(6L))
        assertNull(Target::class.createInstanceOrNull(null))
    }

    @Test fun targetExceptionIsNeverRetried() {
        val target = Target(1)
        val resolver = target.asResolver().firstMethodOrNull { name = "throwsOnce"; emptyParameters() }!!
        assertNull(resolver.invokeQuietly())
        assertEquals(1, target.entries)
        try { resolver.invoke(); fail("expected target exception") } catch (expected: InvocationTargetException) { assertEquals("target", expected.targetException.message) }
        assertEquals(2, target.entries)
    }

    @Test fun rawMemberExportDoesNotForceAccess() {
        val method = Target::class.resolve().firstMethodOrNull { name = "read"; emptyParameters() }!!.self
        @Suppress("DEPRECATION")
        assertFalse(method.isAccessible)
    }

    @Test fun interruptedLookupIsNotConvertedToMissing() {
        Thread.currentThread().interrupt()
        try {
            try { Target::class.resolve().optional().firstFieldOrNull { name = "value" }; fail("must reject cancelled lookup") }
            catch (_: IncompleteLookupException) { assertTrue(Thread.currentThread().isInterrupted) }
        } finally { Thread.interrupted() }
    }

    @Test fun explicitProcessorCandidatesAreStillFiltered() {
        val processor = object : MemberProcessor.Resolver() {
            override fun <T : Any> getDeclaredMethods(declaringClass: Class<T>) = super.getDeclaredMethods(declaringClass).reversed()
        }
        val method = Target::class.resolve().processor(processor).firstMethodOrNull { name = "read"; parameters(String::class) }!!
        assertArrayEquals(arrayOf(String::class.java), method.self.parameterTypes)
    }

    @Test fun typedClassAndMissingClassBehaveExplicitly() {
        val typed: Class<CharSequence> = String::class.java.toClass<CharSequence>()
        assertSame(String::class.java, typed)
        assertNull("missing.meguviz.Fixture".toClassOrNull())
        assertSame(String::class.java, "java.lang.String".toClass())
    }

    @Test fun quietLookupAbsorbsLinkageFailures() {
        val name = BrokenInit::class.java.name
        // First load fails with ExceptionInInitializerError, later loads with NoClassDefFoundError.
        assertNull(name.toClassOrNull(initialize = true))
        assertNull(name.toClassOrNull(initialize = true))
    }
}

private object BrokenInit {
    init { if (System.nanoTime() != 0L) throw IllegalStateException("static init") }
}

@file:Suppress("UNUSED_PARAMETER")

package io.github.jichuo1.meguviz.compat

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

class LegacyConstructorsTest {
    @Test fun referencePhasePrecedesPrimitiveUnboxing() {
        assertArrayEquals(arrayOf(Number::class.java), selected(ReferenceFirst::class.java, 1))
    }
    @Test fun mostSpecificReferenceWinsRegardlessOfEnumerationOrder() {
        assertArrayEquals(arrayOf(Number::class.java), selected(ReferenceSpecific::class.java, 1))
    }
    @Test fun narrowestApplicableWideningWins() {
        assertArrayEquals(arrayOf(Long::class.javaPrimitiveType), selected(Widening::class.java, 1))
    }
    @Test fun boxedReferenceWinsBeforePrimitiveCounterpart() {
        assertArrayEquals(arrayOf(Int::class.javaObjectType), selected(BoxedFirst::class.java, 1))
    }
    @Test fun privateConstructorsRequireExplicitOptIn() {
        assertNull(selectLegacyConstructor(PrivateOnly::class.java, arrayOf(1)))
        assertNotNull(selectLegacyConstructor(PrivateOnly::class.java, arrayOf(1), false))
    }
    @Test fun narrowingAndBooleanNumericConversionsAreRejected() {
        assertNull(selectLegacyConstructor(OnlyInt::class.java, arrayOf(1L)))
        assertNull(selectLegacyConstructor(OnlyInt::class.java, arrayOf(true)))
        assertNull(selectLegacyConstructor(OnlyBoolean::class.java, arrayOf(1)))
    }
    @Test fun allNullNonemptyInputRetainsLegacyRejection() {
        assertNull(selectLegacyConstructor(OnlyString::class.java, arrayOf(null)))
    }
    @Test fun emptyInputCanUseDefaultConstructor() {
        assertNotNull(selectLegacyConstructor(Zero::class.java, emptyArray()))
    }
    @Test fun mixedNullSelectsTheNarrowerReference() {
        assertArrayEquals(arrayOf(String::class.java, Int::class.javaPrimitiveType), selected(MixedNullSpecific::class.java, null, 1))
    }
    @Test fun unrelatedReferenceOverloadsAreAmbiguous() {
        try { selectLegacyConstructor(Ambiguous::class.java, arrayOf(null, 1)); fail("must reject ambiguity") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun crossedParameterSpecificityIsAmbiguous() {
        try { selectLegacyConstructor(Crossed::class.java, arrayOf("a", "b")); fail("must reject ambiguity") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun varargsRequireAnExplicitArray() {
        assertNull(selectLegacyConstructor(Varargs::class.java, arrayOf("a", "b")))
        assertNotNull(selectLegacyConstructor(Varargs::class.java, arrayOf(arrayOf("a", "b"))))
    }
    @Test fun primitiveCharacterCanWidenToInteger() {
        assertArrayEquals(arrayOf(Int::class.javaPrimitiveType), selected(OnlyInt::class.java, 'a'))
    }
    @Test fun selectedConstructorExecutesOnceWithStandardConversion() {
        assertEquals("long", selectLegacyConstructor(Widening::class.java, arrayOf(1))!!.newInstance(1).selection)
    }
    @Test fun primitivePublicConstructorOnNonPublicClassIsStillEligible() {
        assertNotNull(selectLegacyConstructor(NonPublic::class.java, arrayOf(1)))
    }
    private fun selected(owner: Class<*>, vararg arguments: Any?) = selectLegacyConstructor(owner, arguments)!!.parameterTypes
    interface Selected { val selection: String }
    class ReferenceFirst : Selected {
        override val selection: String
        constructor(value: Number) { selection = "number" }
        constructor(value: Int) { selection = "int" }
    }
    class ReferenceSpecific : Selected {
        override val selection: String
        constructor(value: Any) { selection = "object" }
        constructor(value: Number) { selection = "number" }
    }
    class Widening : Selected {
        override val selection: String
        constructor(value: Long) { selection = "long" }
        constructor(value: Double) { selection = "double" }
    }
    class BoxedFirst : Selected {
        override val selection: String
        constructor(value: Int?) { selection = "boxed" }
        constructor(value: Int) { selection = "int" }
    }
    class PrivateOnly private constructor(value: Int) : Selected { override val selection = "private" }
    class OnlyInt(value: Int) : Selected { override val selection = "int" }
    class OnlyBoolean(value: Boolean) : Selected { override val selection = "boolean" }
    class OnlyString(value: String?) : Selected { override val selection = "string" }
    class Zero : Selected { override val selection = "zero" }
    class MixedNullSpecific : Selected {
        override val selection: String
        constructor(value: Any?, other: Int) { selection = "object" }
        constructor(value: String?, other: Int) { selection = "string" }
    }
    class Ambiguous : Selected {
        override val selection: String
        constructor(value: Number?, other: Int) { selection = "number" }
        constructor(value: String?, other: Int) { selection = "string" }
    }
    class Crossed : Selected {
        override val selection: String
        constructor(first: String, second: Any) { selection = "left" }
        constructor(first: Any, second: String) { selection = "right" }
    }
    class Varargs(vararg strings: String) : Selected { override val selection = "array" }
    private class NonPublic(value: Int)
}

/** Explicit isolated baseline runner; the baseline JAR never enters production dependency graphs. */
object LegacyConstructorDifferential {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Pass the verified kavaref-extension-1.1.1.jar path" }
        val baselineJar = File(args[0]).canonicalFile
        require(baselineJar.isFile) { "Baseline JAR missing" }
        val cases = listOf(
            Case(LegacyConstructorsTest.ReferenceFirst::class.java, arrayOf(1)),
            Case(LegacyConstructorsTest.ReferenceSpecific::class.java, arrayOf(1)),
            Case(LegacyConstructorsTest.Widening::class.java, arrayOf(1)),
            Case(LegacyConstructorsTest.Widening::class.java, arrayOf(1.toByte())),
            Case(LegacyConstructorsTest.BoxedFirst::class.java, arrayOf(1)),
            Case(LegacyConstructorsTest.PrivateOnly::class.java, arrayOf(1)),
            Case(LegacyConstructorsTest.PrivateOnly::class.java, arrayOf(1), false),
            Case(LegacyConstructorsTest.OnlyInt::class.java, arrayOf(1L)),
            Case(LegacyConstructorsTest.OnlyInt::class.java, arrayOf(true)),
            Case(LegacyConstructorsTest.OnlyInt::class.java, arrayOf('a')),
            Case(LegacyConstructorsTest.OnlyBoolean::class.java, arrayOf(1)),
            Case(LegacyConstructorsTest.OnlyString::class.java, arrayOf(null)),
            Case(LegacyConstructorsTest.Zero::class.java, emptyArray()),
            Case(LegacyConstructorsTest.MixedNullSpecific::class.java, arrayOf(null, 1)),
            Case(LegacyConstructorsTest.Ambiguous::class.java, arrayOf(null, 1)),
            Case(LegacyConstructorsTest.Crossed::class.java, arrayOf("a", "b")),
            Case(LegacyConstructorsTest.Varargs::class.java, arrayOf("a", "b")),
            Case(LegacyConstructorsTest.Varargs::class.java, arrayOf(arrayOf("a", "b")))
        )
        URLClassLoader(arrayOf(baselineJar.toURI().toURL()), LegacyConstructorDifferential::class.java.classLoader).use { baselineLoader ->
            val old = Class.forName("com.highcapable.kavaref.extension.ClassUtils", true, baselineLoader)
                .getMethod("createInstanceOrNull", Class::class.java, Array<Any?>::class.java, Boolean::class.javaPrimitiveType)
            for ((index, case) in cases.withIndex()) {
                val oldValue = old.invoke(null, case.owner, case.arguments, case.publicOnly) as LegacyConstructorsTest.Selected?
                val selected = try { selectLegacyConstructor(case.owner, case.arguments, case.publicOnly) } catch (_: IllegalArgumentException) { null }
                val newValue = selected?.let { constructor ->
                    constructor.isAccessible = true
                    constructor.newInstance(*case.arguments) as LegacyConstructorsTest.Selected
                }
                check(oldValue?.selection == newValue?.selection) { "case $index ${case.owner.name}: old=${oldValue?.selection} new=${newValue?.selection}" }
                println("PASS constructor-differential-$index old=${oldValue?.selection} new=${newValue?.selection}")
            }
        }
        println("DIFFERENTIAL_CASES=${cases.size} FAILURES=0")
    }
    private class Case(val owner: Class<*>, val arguments: Array<out Any?>, val publicOnly: Boolean = true)
}

package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Constructor

class CommentReplyButtonFeatureInstallerTest {
    @Test
    fun identifiesLegacyAndExperimentThreeBindingsFromTheVerifiedFieldTuples() {
        assertEquals("legacy", commentReplyBindingShape(shape(constraint = 1, image = 5, text = 3)))
        assertEquals("exp3", commentReplyBindingShape(shape(linear = 2, image = 4, text = 4, frame = 1)))
    }

    @Test
    fun identifiesAllThreeVerifiedNextBindingTuples() {
        assertEquals("next", commentReplyBindingShape(shape(constraint = 1, image = 4, text = 3, frame = 3, linear = 1)))
        assertEquals("next", commentReplyBindingShape(shape(constraint = 1, image = 4, text = 3, frame = 3, linear = 2)))
        assertEquals("next", commentReplyBindingShape(shape(constraint = 1, image = 2, text = 3, frame = 3, linear = 1)))
    }

    @Test
    fun fieldDeclarationOrderDoesNotChangeTheBindingFamily() {
        val fields = shape(linear = 2, image = 4, text = 4, frame = 1)
        assertEquals("exp3", commentReplyBindingShape(fields.reversed()))
    }

    @Test
    fun rejectsUnknownCountsMissingFieldsAndAdditionalFieldTypes() {
        assertNull(commentReplyBindingShape(emptyList()))
        assertNull(commentReplyBindingShape(shape(constraint = 1, image = 3, text = 3, frame = 3, linear = 1)))
        assertNull(commentReplyBindingShape(shape(constraint = 1, image = 5, text = 2)))
        assertNull(commentReplyBindingShape(shape(constraint = 1, image = 5, text = 3) + "android.view.View"))
        assertNull(commentReplyBindingShape(shape(constraint = 1, image = 5, text = 3) + "int"))
    }

    @Test
    fun disabledReturnsBeforeRequiringAClassLoaderOrRegisteringHooks() {
        val registrar = CountingRegistrar()
        val installer = CommentReplyButtonFeatureInstaller(enabled = false)

        assertEquals(FeatureInstallResult.Skipped("disabled"), installer.install(environment("tv.danmaku.bili", registrar)))
        assertEquals(emptyList<String>(), installer.capabilityIds)
        assertEquals(0, registrar.registrations)
    }

    @Test
    fun nonMainProcessReturnsBeforeRequiringAClassLoaderOrRegisteringHooks() {
        val registrar = CountingRegistrar()
        val installer = CommentReplyButtonFeatureInstaller(enabled = true)

        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            installer.install(environment("tv.danmaku.bili:download", registrar)))
        assertEquals(listOf(CommentReplyButtonFeatureInstaller.CAPABILITY), installer.capabilityIds)
        assertEquals(0, registrar.registrations)
    }

    private fun environment(process: String, registrar: HookRegistrar) = HookEnvironment(
        processName = process,
        classLoader = null,
        hookPoints = HookPointRegistry(null),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> }
    )

    private fun shape(
        constraint: Int = 0,
        image: Int = 0,
        text: Int = 0,
        frame: Int = 0,
        linear: Int = 0
    ): List<String> = buildList {
        repeat(constraint) { add("androidx.constraintlayout.widget.ConstraintLayout") }
        repeat(image) { add("com.bilibili.magicasakura.widgets.TintImageView") }
        repeat(text) { add("com.bilibili.magicasakura.widgets.TintTextView") }
        repeat(frame) { add("android.widget.FrameLayout") }
        repeat(linear) { add("android.widget.LinearLayout") }
    }

    private class CountingRegistrar : HookRegistrar {
        var registrations = 0
            private set

        private fun registered() {
            registrations++
        }

        override fun first(id: String, className: String, methodName: String,
            block: ModernMemberHookCreator.() -> Unit) = registered()

        override fun all(id: String, className: String, methodName: String,
            block: ModernMemberHookCreator.() -> Unit) = registered()

        override fun exact(id: String, owner: Class<*>, methodName: String,
            vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) = registered()

        override fun adapted(id: String, point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) = registered()

        override fun constructor(id: String, constructor: Constructor<*>,
            block: ModernMemberHookCreator.() -> Unit) = registered()
    }
}

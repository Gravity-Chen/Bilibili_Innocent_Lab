package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Genuine reflected members and Modern registration; these isolated classfiles do not prove Native/UI execution. */
class FollowFeedPersonalCoverageTest {
    private val personal = "com.bilibili.bplus.followinglist.quick.consume.VideoQuickConsumeFragment"
    private val decoration = "com.bilibili.bplus.followinglist.quick.consume.E"
    private val separator = "com.bilibili.bplus.followinglist.quick.consume.S"

    private inner class RolesLoader(private val absent: Set<String> = emptySet(),
        private val missingMethod: String? = null) : ClassLoader(FollowFeedPersonalCoverageTest::class.java.classLoader) {
        private val definitions = mapOf(FollowFeedAdapter.FRAGMENT to followtest.roles.MainPage::class.java,
            personal to followtest.roles.PersonalPage::class.java,
            decoration to followtest.roles.PersonalDecoration::class.java,
            separator to followtest.roles.PersonalSeparator::class.java)
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name in absent) throw ClassNotFoundException(name)
            val template = definitions[name] ?: return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val replacements = mutableMapOf(template.name.replace('.', '/') to name.replace('.', '/'))
                if (name == personal && missingMethod != null) replacements[missingMethod] = "missingRoleMethod"
                val bytes = classfile(template, replacements)
                defineClass(name, bytes, 0, bytes.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }

    /** Rename UTF8 constants only, preserving actual method descriptors/code and avoiding global host-name fixtures. */
    private fun classfile(template: Class<*>, replacements: Map<String, String>): ByteArray {
        val resource = "/${template.name.replace('.', '/')}.class"
        val bytes = requireNotNull(template.getResourceAsStream(resource)).use { it.readBytes() }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val buffer = ByteArrayOutputStream()
        val output = DataOutputStream(buffer)
        output.writeInt(input.readInt()); output.writeShort(input.readUnsignedShort()); output.writeShort(input.readUnsignedShort())
        val count = input.readUnsignedShort(); output.writeShort(count)
        var index = 1
        while (index < count) {
            val tag = input.readUnsignedByte(); output.writeByte(tag)
            if (tag == 1) {
                val value = input.readUTF(); output.writeUTF(replacements[value] ?: value)
            } else {
                val length = when (tag) {
                    3, 4, 9, 10, 11, 12, 17, 18 -> 4
                    5, 6 -> 8
                    7, 8, 16, 19, 20 -> 2
                    15 -> 3
                    else -> error("unsupported classfile tag $tag")
                }
                repeat(length) { output.writeByte(input.readUnsignedByte()) }
                if (tag == 5 || tag == 6) index++
            }
            index++
        }
        output.write(input.readBytes()); output.flush()
        return buffer.toByteArray()
    }

    private fun install(loader: ClassLoader, cached: FollowFeedPoints? = null,
        failId: String? = null): Pair<FeatureInstallResult, PlayerPortTestRegistrar> {
        val registrar = PlayerPortTestRegistrar(failId)
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        return FollowFeedFeatureInstaller(true, cached).install(environment) to registrar
    }

    @Test fun completePersonalRoleRegistersAllSixEntrypoints() {
        val (result, registrar) = install(RolesLoader())
        assertTrue(result is FeatureInstallResult.Installed && result.complete)
        assertEquals(6, registrar.hooks.keys.count { it.startsWith("follow_feed.personal.") })
    }

    @Test fun validHomeCacheRefreshesMissingOptionalPersonalRoles() {
        val loader = RolesLoader()
        val complete = requireNotNull(FollowFeedAdapter.direct(loader))
        val old = complete.copy(personalDecorationClass = null, personalSeparatorClass = null)
        val refreshed = requireNotNull(FollowFeedAdapter.resolve(loader, old))
        assertEquals(complete, refreshed.points)
        val (result, registrar) = install(loader, old)
        assertTrue(result is FeatureInstallResult.Installed && result.complete)
        assertEquals(6, registrar.hooks.keys.count { it.startsWith("follow_feed.personal.") })
    }

    @Test fun existingPersonalOwnerWithMissingLifecycleOrVisibilityRemainsInTheDenominator() {
        for (method in listOf("onViewCreated", "onDestroyView", "setUserVisibleCompat")) {
            val loader = RolesLoader(missingMethod = method)
            assertEquals(personal, loader.loadClass(personal).name)
            val (result, registrar) = install(loader)
            assertTrue(result is FeatureInstallResult.Installed && !result.complete)
            assertTrue(registrar.hooks.keys.none { it.startsWith("follow_feed.personal.") })
        }
    }

    @Test fun missingSeparatorOrBackgroundCannotExemptAnExistingPersonalPage() {
        for (missing in listOf(separator, decoration)) {
            val (result, registrar) = install(RolesLoader(absent = setOf(missing)))
            assertTrue(result is FeatureInstallResult.Installed && !result.complete)
            assertTrue(registrar.hooks.keys.none { it.startsWith("follow_feed.personal.") })
        }
    }

    @Test fun absentWholeRoleIsNotApplicableButCachedRoleDescriptionStillCounts() {
        val absent = setOf(personal, separator, decoration)
        val (withoutRole, _) = install(RolesLoader(absent))
        assertTrue(withoutRole is FeatureInstallResult.Installed && withoutRole.complete)
        val cached = requireNotNull(FollowFeedAdapter.direct(RolesLoader()))
        val (describedRole, _) = install(RolesLoader(absent), cached)
        assertTrue(describedRole is FeatureInstallResult.Installed && !describedRole.complete)
    }

    @Test fun personalRegistrationFailureCannotProduceACompleteReturn() {
        assertTrue(runCatching { install(RolesLoader(), failId = "follow_feed.personal.visibility") }.isFailure)
    }
}

package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostOpusBackgroundAccess
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Modifier

/** Child-loader fixtures keep the 9.16 names and G.b shape out of other host generations. */
class FollowFeed916AdapterTest {
    private class Host(private val localField: String = "b", private val hideOpus: Boolean = false) :
        ClassLoader(FollowFeed916AdapterTest::class.java.classLoader) {
        private val definitions = mapOf(
            "mU.d" to follow916.Painter::class.java,
            "xS.G" to tS.G::class.java,
            "xS.H" to tS.H::class.java,
            "xS.TestModule" to follow916.ConcreteModule::class.java,
            "com.bilibili.bplus.followinglist.home.U" to com.bilibili.bplus.followinglist.home.Q::class.java,
            "com.bilibili.bplus.followinglist.quick.consume.I" to followtest.roles.PersonalDecoration::class.java,
            "com.bilibili.bplus.followinglist.quick.consume.W" to followtest.roles.PersonalSeparator::class.java,
            "com.bilibili.bplus.followinglist.opus.k" to follow916.Opus::class.java
        )
        private val names = definitions.map { (name, template) ->
            template.name.replace('.', '/') to name.replace('.', '/')
        }.toMap() + ("iU/d" to "mU/d")

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name in setOf("gv1.c", "iU.d") || hideOpus && name.endsWith(".opus.k"))
                throw ClassNotFoundException(name)
            val template = definitions[name] ?: return@synchronized super.loadClass(name, resolve)
            val type = findLoadedClass(name) ?: run {
                val bytes = rewrite(template, names, if (name == "xS.G") localField else null)
                defineClass(name, bytes, 0, bytes.size)
            }
            if (resolve) resolveClass(type)
            type
        }

        fun item(): Any = loadClass("xS.H").getConstructor().newInstance()
        fun module(item: Any): Any = loadClass("xS.TestModule").getConstructor(loadClass("xS.H")).newInstance(item)

        private fun rewrite(template: Class<*>, names: Map<String, String>, field: String?): ByteArray {
            val bytes = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class"))
                .use { it.readBytes() }
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val buffer = ByteArrayOutputStream()
            val output = DataOutputStream(buffer)
            output.writeInt(input.readInt()); output.writeShort(input.readUnsignedShort()); output.writeShort(input.readUnsignedShort())
            val count = input.readUnsignedShort(); output.writeShort(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte(); output.writeByte(tag)
                if (tag == 1) {
                    val original = input.readUTF()
                    val value = names.entries.fold(original) { text, (old, new) -> text.replace(old, new) }
                    output.writeUTF(if (field != null && value == "a") field else value)
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
    }

    @Test fun `new painter home personal and separator resolve with the b local owner`() {
        val loader = Host()
        val points = requireNotNull(FollowFeedAdapter.direct(loader))
        assertEquals("mU.d", points.painterOwnerClass)
        assertEquals("com.bilibili.bplus.followinglist.home.U", points.homeDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.I", points.personalDecorationClass)
        assertEquals("com.bilibili.bplus.followinglist.quick.consume.W", points.personalSeparatorClass)
        val resolved = requireNotNull(FollowFeedAdapter.resolve(loader, points))
        assertEquals(points, resolved.points)
        assertTrue(resolved.host.embeddedVideoSupported)
    }

    @Test fun `local quote test does not mistake the top owner or boolean O for the local item`() {
        val loader = Host()
        val resolved = requireNotNull(FollowFeedAdapter.resolve(loader, FollowFeedAdapter.direct(loader)))
        val item = loader.item()
        val module = loader.module(item)
        assertEquals(false, resolved.host.isQuotedModule(module))
        item.javaClass.getField("j").set(item, loader.item())
        assertEquals(true, resolved.host.isQuotedModule(module))
        assertNull(resolved.host.isQuotedModule(Any()))
    }

    @Test fun `static opus color accepts ordinary modules and preserves quoted or unknown modules`() {
        val loader = Host()
        val access = requireNotNull(HostOpusBackgroundAccess.resolve(loader))
        assertTrue(Modifier.isStatic(access.color.modifiers))
        val item = loader.item()
        val module = loader.module(item)
        assertTrue(access.accepts(null, module))
        item.javaClass.getField("j").set(item, loader.item())
        assertFalse(access.accepts(null, module))
        assertFalse(access.accepts(null, Any()))
        assertFalse(access.accepts(null, null))
    }

    @Test fun `unknown local ownership and absent opus painter remain unavailable`() {
        assertNull(FollowFeedAdapter.direct(Host(localField = "unknown")))
        assertNull(HostOpusBackgroundAccess.resolve(Host(localField = "unknown")))
        assertNull(HostOpusBackgroundAccess.resolve(Host(hideOpus = true)))
    }
}

package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/** 安装期按评论 Handler 命名空间、Binding 和 Widget 接口限定身份，不依赖混淆成员名。 */
internal object CommentReplyButtonLocator {
    const val COMMENT_ITEM = "com.bilibili.app.comment3.data.model.CommentItem"
    const val LEGACY_WIDGET = "com.bilibili.app.comm.comment2.phoenix.view.CommentReplyWidget"
    const val COMPOSE_RENDERER = "kntr.common.comment.card.renderer.comment.CommentActionBarRenderer"
    const val BUTTON_TYPE = "kntr.common.comment.card.model.comment.ActionBarButtonType"
    const val COMPOSE_MODEL = "kntr.common.comment.card.model.comment.CommentModel"
    private const val COMPOSER = "androidx.compose.runtime.Composer"
    private const val MODEL_PACKAGE = "kntr.common.comment.card.model.comment."

    data class NativePoint(val method: Method, val bindingIndex: Int, val rootGetter: Method)

    fun nativePoints(owner: Class<*>): List<NativePoint> {
        val scopedHandler = owner.name.substringBeforeLast('.') in NATIVE_FAMILIES
        val capturesModel = KavaMemberLookup.declaredFields(owner).any {
            !it.isStatic && it.type.name == COMMENT_ITEM
        }
        return KavaMemberLookup.declaredMethods(owner).mapNotNull { method ->
            if (!instanceVoid(method) || method.parameterCount !in 1..6 ||
                !scopedHandler && !capturesModel && method.parameterTypes.none { it.name == COMMENT_ITEM }
            ) return@mapNotNull null
            val bindings = method.parameterTypes.mapIndexedNotNull { index, type ->
                if (type.isPrimitive || type.isArray || type.isInterface) return@mapIndexedNotNull null
                val getter = KavaMemberLookup.methodOrNull(type, "getRoot")
                    ?.takeIf { !it.isStatic && it.returnType isSubclassOf classOf<View>() }
                    ?: return@mapIndexedNotNull null
                index to getter
            }
            val binding = bindings.singleOrNull() ?: return@mapNotNull null
            NativePoint(method, binding.first, binding.second)
        }
    }

    fun widgetMethods(widget: Class<*>): List<Method> {
        val interfaces = linkedSetOf<Class<*>>()
        fun collect(type: Class<*>) {
            type.interfaces.forEach { if (interfaces.add(it)) collect(it) }
        }
        collect(widget)
        val contracts = interfaces.flatMap { KavaMemberLookup.declaredMethods(it) }.filter {
            it.returnType == Void.TYPE && it.parameterCount == 1 &&
                !it.parameterTypes[0].isPrimitive &&
                !(it.parameterTypes[0] isSubclassOf classOf<View>())
        }
        return KavaMemberLookup.declaredMethods(widget).filter { method ->
            instanceVoid(method) && contracts.any {
                it.name == method.name && it.parameterTypes.contentEquals(method.parameterTypes)
            }
        }
    }

    /** 8.96.0 私有 I 与后续 f 的参数数目不同，均为独立 RenderButton。 */
    fun composeMethods(renderer: Class<*>, buttonType: Class<*>): List<Method> =
        KavaMemberLookup.declaredMethods(renderer).filter { method ->
            val p = method.parameterTypes
            instanceVoid(method) && p.size in 5..16 && p[0] == buttonType &&
                p[1].name.startsWith(MODEL_PACKAGE) &&
                p.count { it.name == COMPOSER } == 1 && p.last() == classOf<Int>()
        }

    fun isReply(value: Any?, buttonType: Class<*>): Boolean =
        buttonType.isInstance(value) && (value as? Enum<*>)?.name == "REPLY"

    fun ownerNames(family: String): List<String> = buildList {
        for (letter in ('a'..'z') + ('A'..'Z')) {
            add("$family.$letter")
            add("$family.${letter}0")
            add("$family.${letter}1")
        }
        addAll(CommentNativeBindingCatalog.mainHandlers.filter { it.startsWith("$family.") })
    }

    private fun instanceVoid(method: Method): Boolean =
        !method.isStatic && !method.isBridge && !method.isSynthetic && method.returnType == Void.TYPE

    private val NATIVE_FAMILIES = listOf("holder", "nextholder", "nextholderexp3")
        .map { "com.bilibili.app.comment3.ui.$it.handle" }
}

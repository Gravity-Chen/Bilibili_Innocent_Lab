package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Story 生命周期订阅只在可空 free_flow_toast 存在时显示办理推广；不修改原 DTO。 */
internal class PlayerStoryFreeDataPromotionAccess private constructor(val reader: Method, private val model: Class<*>) {
    fun isPromotion(value: Any): Boolean = model.isInstance(value)

    companion object {
        const val PARENT_CLASS = "com.bilibili.video.story.StoryDetail"
        const val MODEL_CLASS = "com.bilibili.video.story.FreeDataToast"
        private const val JSON_FIELD = "com.alibaba.fastjson.annotation.JSONField"

        fun applicable(loader: ClassLoader): Boolean = KavaMemberLookup.hasClass(loader, MODEL_CLASS)

        fun resolve(loader: ClassLoader): PlayerStoryFreeDataPromotionAccess? {
            val parent = KavaMemberLookup.classOrNull(loader, PARENT_CLASS) ?: return null
            val model = KavaMemberLookup.classOrNull(loader, MODEL_CLASS) ?: return null
            return resolve(parent, model)
        }

        internal fun resolve(parent: Class<*>, model: Class<*>): PlayerStoryFreeDataPromotionAccess? = runCatching {
            fun exact(owner: Class<*>, name: String, returns: Class<*>) = KavaMemberLookup.declaredMethods(owner, true) {
                it.name == name && it.returnType == returns && it.parameterCount == 0 &&
                    !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers)
            }.singleOrNull()
            val reader = exact(parent, "getFreeDataToast", model) ?: return null
            for (name in listOf("getTitle", "getSubtitle", "getButtonText", "getUri")) {
                if (exact(model, name, String::class.java) == null) return null
            }
            if (exact(model, "getTipsId", Long::class.javaPrimitiveType!!) == null) return null
            val field = KavaMemberLookup.declaredFields(parent) { it.type == model && !Modifier.isStatic(it.modifiers) }
                .singleOrNull() ?: return null
            val annotations = field.declaredAnnotations
            if (annotations.size > 16) return null
            val annotation = annotations.singleOrNull { it.annotationClass.java.name == JSON_FIELD } ?: return null
            val nameReader = KavaMemberLookup.methodOrNull(annotation.annotationClass.java, "name")
                ?.takeIf { it.returnType == String::class.java } ?: return null
            if (nameReader.invoke(annotation) != "free_flow_toast") return null
            PlayerStoryFreeDataPromotionAccess(reader, model)
        }.getOrNull()
    }
}

package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 只覆盖 TFInfo 的推广文案字段；免流状态、tipsId 和用户主动打开的办理面板保留。 */
internal class PlayerFreeDataPromotionAccess private constructor(
    val reader: Method,
    private val toast: Class<*>,
    private val title: Method,
    val emptyToast: Any
) {
    fun isPromotion(value: Any): Boolean = toast.isInstance(value) && (title.invoke(value) as String).isNotEmpty()

    companion object {
        const val REPLY = "com.bapis.bilibili.app.view.v1.TFInfoReply"
        const val TOAST = "com.bapis.bilibili.app.view.v1.TFToast"

        fun resolve(loader: ClassLoader): PlayerFreeDataPromotionAccess? = runCatching {
            val reply = KavaMemberLookup.classOrNull(loader, REPLY) ?: return null
            val toast = KavaMemberLookup.classOrNull(loader, TOAST) ?: return null
            fun exact(owner: Class<*>, name: String, returns: Class<*>, static: Boolean = false) =
                KavaMemberLookup.declaredMethods(owner, true) {
                    it.name == name && it.returnType == returns && it.parameterCount == 0 &&
                        Modifier.isStatic(it.modifiers) == static && !Modifier.isAbstract(it.modifiers)
                }.singleOrNull()
            val reader = exact(reply, "getTfToast", toast) ?: return null
            val title = exact(toast, "getTitle", String::class.java) ?: return null
            val factory = exact(toast, "getDefaultInstance", toast, true) ?: return null
            val empty = factory.invoke(null)?.takeIf(toast::isInstance) ?: return null
            if ((title.invoke(empty) as? String) != "") return null
            PlayerFreeDataPromotionAccess(reader, toast, title, empty)
        }.getOrNull()
    }
}

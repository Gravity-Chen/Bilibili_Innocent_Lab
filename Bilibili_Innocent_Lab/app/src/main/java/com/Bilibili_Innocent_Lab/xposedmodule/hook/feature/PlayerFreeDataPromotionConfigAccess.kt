package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 宿主新旧播放器共同的自动推广次数开关；只解析具体实现，不调用配置服务或写配置。 */
internal object PlayerFreeDataPromotionConfigAccess {
    const val KEY = "freedata.mobile_free_data_toast_times"
    val OWNERS = listOf("com.bilibili.lib.blconfig.internal.TypedContract", "com.bilibili.lib.blconfig.internal.OverrideConfig")

    fun readers(loader: ClassLoader): List<Method> {
        val contract = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.blconfig.Contract") ?: return emptyList()
        if (!contract.isInterface) return emptyList()
        return OWNERS.mapNotNull { name ->
            val owner = KavaMemberLookup.classOrNull(loader, name) ?: return@mapNotNull null
            if (!contract.isAssignableFrom(owner)) return@mapNotNull null
            KavaMemberLookup.declaredMethods(owner, true) {
                it.name == "get" && it.returnType == Any::class.java &&
                    it.parameterTypes.toList() == listOf(String::class.java, Any::class.java) &&
                    !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers)
            }.singleOrNull()
        }.distinct()
    }

    fun matches(key: Any?, defaultValue: Any?): Boolean = key == KEY && defaultValue is String
}

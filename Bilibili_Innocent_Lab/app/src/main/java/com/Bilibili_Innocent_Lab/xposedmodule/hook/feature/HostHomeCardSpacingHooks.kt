package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.Bundle
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

internal object HostHomeCardSpacingHooks {
    /** 在测量前标记首页根视图，详情、历史、动态等页面不能使用首页间距。 */
    fun install(environment: HookEnvironment, markHome: (View) -> Unit): Int {
        val loader = environment.classLoader ?: return 0
        val fragment = KavaMemberLookup.classOrNull(loader, "androidx.fragment.app.Fragment") ?: return 0
        val created = KavaMemberLookup.methodOrNull(fragment, "performViewCreated")
            ?: KavaMemberLookup.methodOrNull(fragment, "onViewCreated", View::class.java, Bundle::class.java) ?: return 0
        val getView = KavaMemberLookup.methodOrNull(fragment, "getView") ?: return 0
        environment.registrar.exact("host_video_cards.home_spacing", created.declaringClass, created.name, *created.parameterTypes) {
            after {
                if (hasThrowable) return@after
                val page = instance ?: return@after
                if (!HomeComponentFilterFeatureInstaller.isHomeContainer(page.javaClass)) return@after
                val root = (if (created.parameterCount == 0) getView.invoke(page) else argOrNull(0)) as? View ?: return@after
                markHome(root)
            }
        }
        return 1
    }
}

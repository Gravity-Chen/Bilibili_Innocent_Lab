package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerPopupPromotionAdapter
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 广告模型的稳定语义入口；缓存成员也在安装期验真，回调内不定位或扫描。 */
internal object PlayerPopupPromotionLocator {
    const val ADVERT_CLASS = "com.bilibili.adcommon.data.model.DmAdvert"
    const val DM_CLASS = "com.bilibili.adcommon.data.model.Dm"
    const val CARD_CLASS = "com.bilibili.adcommon.data.model.Card"
    val READERS = listOf("getAds", "getFloatLayers", "getValidPanelData")

    class Access(
        val readers: List<Method>,
        private val dmClass: Class<*>,
        private val cardClass: Class<*>,
        private val getCard: Method,
        private val getCardType: Method
    ) {
        fun cardType(item: Any): Int? {
            if (!dmClass.isInstance(item)) return null
            val card = getCard.invoke(item) ?: return null
            if (!cardClass.isInstance(card)) return null
            return getCardType.invoke(card) as? Int
        }
    }

    fun resolve(loader: ClassLoader?, cachedFloat: HookPoint? = null): Access? {
        val assisted = loader?.let { PlayerPopupPromotionAdapter.resolveAdvertFloat(it, cachedFloat) }
        val advert = KavaMemberLookup.classOrNull(loader, ADVERT_CLASS) ?: assisted?.declaringClass ?: return null
        val dm = KavaMemberLookup.classOrNull(loader, DM_CLASS) ?: return null
        val card = KavaMemberLookup.classOrNull(loader, CARD_CLASS) ?: return null
        return resolve(advert, dm, card, assisted?.takeIf { it.declaringClass == advert })
    }

    internal fun resolve(advert: Class<*>, dm: Class<*>, card: Class<*>, assistedFloat: Method? = null): Access? {
        val getCard = exact(dm, "getCard", card) ?: return null
        val getCardType = exact(card, "getCardType", Int::class.javaPrimitiveType!!) ?: return null
        val readers = READERS.mapNotNull {
            exact(advert, it, List::class.java) ?: assistedFloat?.takeIf { _ -> it == "getFloatLayers" }
        }.distinct()
        return Access(readers, dm, card, getCard, getCardType)
    }

    private fun exact(owner: Class<*>, name: String, returnType: Class<*>): Method? =
        KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            it.name == name && it.parameterCount == 0 && it.returnType == returnType &&
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers)
        }.singleOrNull()
}

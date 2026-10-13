package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

internal object PlayerPopupPromotionPolicy {
    // HAR 的 x/v2/dm/ad 样本与宿主 Card.isValidCommon() 相互印证：
    // 宿主 DataUtil 将 31/32/79/80 转为三种 AdFloatView 广告。
    // 仅在 DmAdvert 广告列表内使用，不影响独立 VideoGuide 或普通弹幕读取。
    const val COMMON_FLOAT_PROMOTION = 80
    const val COMMERCE_FLOAT_PROMOTION = 79
    const val GUIDE_FLOAT_PROMOTION = 31
    const val ICON_GUIDE_FLOAT_PROMOTION = 32
    const val MAX_AD_ITEMS = 256

    fun filter(source: List<*>, cardType: (Any) -> Int?): List<*> {
        // 超限由调用方记录有界失败并保留原列表，不在播放器读取回调里遍历异常载荷。
        require(source.size <= MAX_AD_ITEMS) { "promotion-list-limit" }
        return CopyOnFilter.list(source) {
            when (cardType(it)) {
                COMMON_FLOAT_PROMOTION, COMMERCE_FLOAT_PROMOTION,
                GUIDE_FLOAT_PROMOTION, ICON_GUIDE_FLOAT_PROMOTION -> true
                else -> false
            }
        }
    }
}

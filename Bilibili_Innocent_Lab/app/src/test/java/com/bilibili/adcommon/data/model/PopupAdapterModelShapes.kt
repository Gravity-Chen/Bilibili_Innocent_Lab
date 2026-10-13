package com.bilibili.adcommon.data.model

/** 模拟模型 owner/getter 改名；候选来自有界假引擎，普通宿主模型链仍单独验真。 */
class RenamedPopupAdvert {
    fun floats(): List<Dm> = emptyList()
    fun getAds(): List<Dm> = emptyList()
    fun getValidPanelData(): List<Dm> = emptyList()
    fun wrong(): String = "unrelated"
}
class OtherPopupAdvert { fun floats(): List<Dm> = emptyList() }

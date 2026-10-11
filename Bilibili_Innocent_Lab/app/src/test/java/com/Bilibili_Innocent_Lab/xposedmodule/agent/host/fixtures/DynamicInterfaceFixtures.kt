package com.Bilibili_Innocent_Lab.xposedmodule.agent.host.fixtures.dynamic

import com.bapis.bilibili.app.interfaces.v1.ContentFixtureBuilder
import com.bapis.bilibili.app.interfaces.v1.ContentFixtureMessage
import com.bilibili.lib.moss.api.CallOptions

/** 只复现静态样本中公开的协议签名与枚举，不复制宿主实现。 */
enum class Refresh(private val code: Int) {
    refresh_new(0), refresh_history(1), UNRECOGNIZED(-1);
    fun getNumber(): Int = code
}
enum class LiveState(private val code: Int) {
    live_none(0), live_live(1), live_rotation(2), UNRECOGNIZED(-1);
    fun getNumber(): Int = code
}

class DynAllReq(val offset: String, val page: Int, val refresh: Int) : ContentFixtureMessage() {
    class Builder : ContentFixtureBuilder() {
        private var offset = "wrong"
        private var page = -1
        private var refresh = -1
        fun setOffset(value: String): Builder = apply { offset = value }
        fun setPage(value: Int): Builder = apply { page = value }
        fun setRefreshTypeValue(value: Int): Builder = apply { refresh = value }
        override fun build(): ContentFixtureMessage = DynAllReq(offset, page, refresh)
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}
class DynAllReply(private val dynamic: DynamicList?, private val users: CardVideoUpList?) {
    fun hasDynamicList(): Boolean = dynamic != null
    fun getDynamicList(): DynamicList = dynamic ?: DynamicList(emptyList(), false, "")
    fun hasUpList(): Boolean = users != null
    fun getUpList(): CardVideoUpList = users ?: CardVideoUpList(emptyList(), emptyList())
}
class DynamicList(private val items: List<DynamicItem>, private val more: Boolean, private val offset: String) {
    fun getListList(): List<DynamicItem> = items
    fun getHasMore(): Boolean = more
    fun getHistoryOffset(): String = offset
}
class DynamicItem(private val extend: Extend?, private val modules: List<Module>) {
    fun hasExtend(): Boolean = extend != null
    fun getExtend(): Extend = extend ?: Extend("", 0, "")
    fun getModulesList(): List<Module> = modules
}
class Extend(private val id: String, private val uid: Long, private val originalName: String) {
    fun getDynIdStr(): String = id
    fun getUid(): Long = uid
    fun getOrigName(): String = originalName
}
class Module(private val desc: ModuleDesc?, private val dynamic: ModuleDynamic? = null) {
    fun hasModuleDesc(): Boolean = desc != null
    fun getModuleDesc(): ModuleDesc = desc ?: ModuleDesc("")
    fun hasModuleDynamic(): Boolean = dynamic != null
    fun getModuleDynamic(): ModuleDynamic = dynamic ?: ModuleDynamic(null)
}
class ModuleDesc(private val text: String) { fun getText(): String = text }
class ModuleDynamic(private val archive: MdlDynArchive?) {
    fun hasDynArchive(): Boolean = archive != null
    fun getDynArchive(): MdlDynArchive = archive ?: MdlDynArchive(0, "", "", "", 0, false)
}
class MdlDynArchive(private val aid: Long, private val bvid: String, private val uri: String,
    private val title: String, private val duration: Long, private val pgc: Boolean) {
    fun getAvid(): Long = aid
    fun getBvid(): String = bvid
    fun getUri(): String = uri
    fun getTitle(): String = title
    fun getDuration(): Long = duration
    fun getIsPGC(): Boolean = pgc
}
class CardVideoUpList(private val first: List<UpListItem>, private val second: List<UpListItem>) {
    fun getListList(): List<UpListItem> = first
    fun getListSecondList(): List<UpListItem> = second
}
class UpListItem(private val uid: Long, private val name: String, private val state: Int, private val uri: String) {
    fun getUid(): Long = uid
    fun getName(): String = name
    fun getLiveStateValue(): Int = state
    fun getUri(): String = uri
}
class DynamicMoss(val address: String, val port: Int, val options: CallOptions) {
    fun executeDynAll(request: DynAllReq): DynAllReply {
        calls++
        lastRequest = request
        lastTimeout = options.getTimeoutInMs()
        return reply
    }
    companion object {
        var calls = 0
        var lastRequest: DynAllReq? = null
        var lastTimeout: Long? = null
        var reply = DynAllReply(null, null)
    }
}

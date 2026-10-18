package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.lang.ref.WeakReference

/**
 * Story 首播目标的纯 JVM 状态；调用方负责提供已确认的播放起点、当前条目和实时准入结果。
 * 默认读取不消耗操作预算；请求改写完成也不会提前结束后续资源选档。
 * 此处不调用宿主，不跨挂起保存线程状态，也不保存宿主实例的强引用。
 */
internal class PlayerQualitySessions(qualityQn: Int) {
    data class ItemKey(val aid: Long, val cid: Long) {
        internal val isValid: Boolean get() = aid > 0L && cid > 0L
    }

    /** ALLOW 必须由调用方正向确认 UGC、登录及当前适用的限制条件，未知不能按允许处理。 */
    enum class Admission { ALLOW, DENY, UNKNOWN }
    enum class ManualChoice { FIXED, AUTO }
    enum class Operation { REQUEST_TARGET, RESOURCE_SELECT, REPARSE, PLAYBACK_SETTLE }
    enum class Outcome { COMPLETED, FAILED }

    class Token internal constructor(
        internal val ownerSerial: Long,
        val generation: Long,
        internal val revision: Long,
        val operation: Operation,
        val qn: Int
    )

    private enum class Phase { ACTIVE, MANUAL_FIXED, MANUAL_AUTO, SETTLED }
    private class Attempt(val token: Token, var outcome: Outcome? = null)
    private class Entry(
        val owner: WeakReference<Any>,
        val ownerSerial: Long,
        val generation: Long,
        val item: ItemKey,
        var revision: Long = 0L,
        var admission: Admission = Admission.UNKNOWN,
        var phase: Phase = Phase.ACTIVE,
        val attempts: MutableMap<Operation, Attempt> = linkedMapOf()
    )

    private val qn = PlayerQualityConfig.normalize(qualityQn)
    private val entries = ArrayList<Entry>()
    private var sequence = 0L

    /** 仅真实新播放/新条目事件调用；同 aid/cid 重进也必须开始新代次，getter 不得调用。 */
    @Synchronized fun start(owner: Any, item: ItemKey?): Long? {
        prune()
        val previous = entries.firstOrNull { it.owner.get() === owner }
        entries.removeAll { it.owner.get() === owner }
        if (qn == 0 || item?.isValid != true || sequence == Long.MAX_VALUE) return null
        val generation = ++sequence
        while (entries.size >= MAX_OWNERS) entries.removeAt(0)
        entries += Entry(WeakReference(owner), previous?.ownerSerial ?: generation, generation, item)
        return generation
    }

    @Synchronized fun target(owner: Any, item: ItemKey?, admission: Admission): Int? =
        admittedEntry(owner, item, admission)?.let { qn }

    /** 每代每种操作只尝试一次，失败或准入失效不会退还预算。必须在异步操作发起前预留。 */
    @Synchronized fun reserve(owner: Any, item: ItemKey?, admission: Admission, operation: Operation): Token? {
        val entry = admittedEntry(owner, item, admission) ?: return null
        if (operation in entry.attempts) return null
        return Token(entry.ownerSerial, entry.generation, entry.revision, operation, qn).also {
            entry.attempts[operation] = Attempt(it)
        }
    }

    /** 调用宿主前与异步结果落地前重新检查；校验本身不提供外部宿主写入的原子性。 */
    @Synchronized fun valid(token: Token, owner: Any, item: ItemKey?, admission: Admission): Boolean =
        tokenEntry(token, owner, item, admission) != null

    @Synchronized fun finish(
        token: Token,
        owner: Any,
        item: ItemKey?,
        admission: Admission,
        outcome: Outcome
    ): Boolean {
        val entry = tokenEntry(token, owner, item, admission) ?: return false
        entry.attempts.getValue(token.operation).outcome = outcome
        return true
    }

    /**
     * 只有确认首播资源已选中或已合法回退才结束目标；请求改写成功不能代替此事件。
     * 必须提交当前 pending 的资源操作 token；独立同步 render 事件先预留 PLAYBACK_SETTLE。
     * 已 finish、旧 revision 或旧代次均不可结束会话，不能仅凭 aid/cid/generation 归属。
     */
    @Synchronized fun settle(token: Token, owner: Any, item: ItemKey?, admission: Admission): Boolean {
        if (token.operation == Operation.REQUEST_TARGET) return false
        val entry = tokenEntry(token, owner, item, admission) ?: return false
        if (!advanceRevision(entry)) return false
        entry.phase = Phase.SETTLED
        return true
    }

    /** 在手动选择的异步工作发起前同步调用；选择自动也明确交还用户，不能等偏好状态更新。 */
    @Synchronized fun manual(owner: Any, item: ItemKey?, choice: ManualChoice): Boolean {
        val entry = currentEntry(owner, item) ?: return false
        if (!advanceRevision(entry)) return false
        entry.phase = when (choice) {
            ManualChoice.FIXED -> Phase.MANUAL_FIXED
            ManualChoice.AUTO -> Phase.MANUAL_AUTO
        }
        return true
    }

    @Synchronized fun close(owner: Any) {
        entries.removeAll { it.owner.get() == null || it.owner.get() === owner }
    }

    private fun admittedEntry(owner: Any, item: ItemKey?, admission: Admission): Entry? {
        val entry = currentEntry(owner, item) ?: return null
        if (entry.phase != Phase.ACTIVE || !updateAdmission(entry, admission)) return null
        return entry.takeIf { admission == Admission.ALLOW }
    }

    private fun tokenEntry(token: Token, owner: Any, item: ItemKey?, admission: Admission): Entry? {
        val existing = find(owner) ?: return null
        // 旧结果不能改变新代次/新修订的准入快照，也不能终结新会话。
        if (existing.ownerSerial != token.ownerSerial || existing.generation != token.generation ||
            existing.revision != token.revision || existing.phase != Phase.ACTIVE) return null
        val attempt = existing.attempts[token.operation] ?: return null
        if (attempt.token !== token || attempt.outcome != null) return null
        val entry = currentEntry(owner, item) ?: return null
        if (!updateAdmission(entry, admission) || admission != Admission.ALLOW ||
            entry.revision != token.revision) return null
        return entry
    }

    private fun currentEntry(owner: Any, item: ItemKey?): Entry? {
        val entry = find(owner) ?: return null
        if (item?.isValid != true || entry.item != item) {
            // 即使随后返回相同 ID，旧 token 也不能复活；需要真实起点重新 start。
            entries.remove(entry)
            return null
        }
        return entry
    }

    private fun updateAdmission(entry: Entry, admission: Admission): Boolean {
        if (entry.admission == admission) return true
        if (!advanceRevision(entry)) return false
        entry.admission = admission
        return true
    }

    private fun advanceRevision(entry: Entry): Boolean {
        if (entry.revision == Long.MAX_VALUE) {
            entries.remove(entry)
            return false
        }
        entry.revision++
        return true
    }

    private fun find(owner: Any): Entry? {
        prune()
        return entries.firstOrNull { it.owner.get() === owner }
    }

    private fun prune() { entries.removeAll { it.owner.get() == null } }

    companion object {
        private const val MAX_OWNERS = 16
    }
}

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** UI consumption metadata, independent of backup eligibility and of constructed controls. */
internal data class SettingDescriptor(
    val id: String,
    val storageKey: String,
    val titleRes: Int,
    val page: Int,
    val section: String,
    val directToggle: Boolean,
    val favoriteEligible: Boolean = true,
)

internal data class SettingState(val checked: Boolean, val enabled: Boolean = true)

internal data class SettingCommandResult(val requested: Boolean, val state: SettingState, val accepted: Boolean)

/** Activity-owned pure state. Commands execute outside this store; no View or receiver is retained. */
internal class SettingStore(descriptors: List<SettingDescriptor>, initial: Map<String, Boolean>) : AutoCloseable {
    val descriptors: List<SettingDescriptor> = java.util.Collections.unmodifiableList(descriptors.toList())
    private val byId = descriptors.associateBy { it.id }
    private val states = descriptors.filter { it.favoriteEligible }
        .associate { it.id to SettingState(initial[it.storageKey] ?: false) }.toMutableMap()
    private class Subscription(var callback: ((SettingState) -> Unit)?)
    private val observers = mutableMapOf<String, MutableList<Subscription>>()
    private val executing = mutableSetOf<String>()
    private var closed = false

    init {
        require(byId.size == descriptors.size) { "Duplicate UI setting identity" }
        require(descriptors.all { it.id.isNotBlank() && it.storageKey.isNotBlank() && it.page in 1..4 })
    }

    fun descriptor(id: String): SettingDescriptor? = byId[id]
    fun state(id: String): SettingState = checkNotNull(states[id]) { "Unknown UI setting: $id" }

    fun observe(id: String, observer: (SettingState) -> Unit): () -> Unit {
        check(!closed)
        require(id in states)
        val listeners = observers.getOrPut(id) { mutableListOf() }
        val subscription = Subscription(observer)
        listeners += subscription
        return {
            subscription.callback = null
            listeners.remove(subscription)
            if (listeners.isEmpty() && observers[id] === listeners) observers.remove(id)
            Unit
        }
    }

    fun confirm(id: String, finalState: SettingState) {
        if (closed) return
        require(id in states)
        if (states[id] == finalState) return
        states[id] = finalState
        if (id in executing) return
        publish(id, finalState)
    }

    fun checked(id: String, finalValue: Boolean) = confirm(id, state(id).copy(checked = finalValue))

    /** The returned value is read after the business operation, including rejection or rollback. */
    fun request(id: String, requested: Boolean, execute: () -> Boolean): SettingCommandResult {
        check(!closed)
        val before = state(id)
        if (!before.enabled || !executing.add(id)) return SettingCommandResult(requested, before, false)
        var failure: Throwable? = null
        try {
            val finalValue = execute()
            checked(id, finalValue)
            val finalState = state(id)
            return SettingCommandResult(requested, finalState, finalState.checked == requested)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            executing.remove(id)
            val finalState = state(id)
            if (finalState != before) {
                try { publish(id, finalState) }
                catch (observerFailure: Throwable) {
                    if (failure != null) failure.addSuppressed(observerFailure) else throw observerFailure
                }
            }
        }
    }

    fun snapshot(): Map<String, SettingState> = states.toMap()

    private fun publish(id: String, value: SettingState) {
        val snapshot = observers[id]?.toList().orEmpty()
        for (subscription in snapshot) {
            if (closed || states[id] != value) return
            val callback = subscription.callback ?: continue
            if (observers[id]?.contains(subscription) == true) callback(value)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        observers.values.forEach { subscriptions -> subscriptions.forEach { it.callback = null }; subscriptions.clear() }
        observers.clear()
        executing.clear()
    }
}

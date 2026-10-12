package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/** Weak identity records, bounded independently of host equals/hashCode and playlist length. */
internal class PlayerBufferSessions {
    class Ticket {
        internal val state = AtomicInteger(PENDING)
        @Volatile internal var committedItem: WeakReference<Any>? = null
    }
    class Entry(val item: WeakReference<Any>, val ticket: Ticket, val cacheRoot: String?, val probePath: String?) {
        @Volatile internal var player: WeakReference<Any>? = null
        @Volatile internal var selected = false
    }
    private val entries = ArrayList<Entry>()
    private var active: Ticket? = null

    @Synchronized fun register(item: Any, ticket: Ticket, cacheRoot: String?, probePath: String?): Entry {
        entries.removeAll { it.item.get() == null || it.item.get() === item || it.ticket.state.get() == CANCELLED }
        while (entries.size >= MAX_ITEMS) entries.removeAt(0).ticket.state.set(CANCELLED)
        return Entry(WeakReference(item), ticket, cacheRoot, probePath).also(entries::add)
    }

    @Synchronized fun get(item: Any): Entry? = entries.firstOrNull { it.item.get() === item }

    /** Verify Java player ownership; enqueueing an attachment is not native playback acceptance. */
    @Synchronized fun select(player: Any, actualItem: Any?) {
        entries.filter { it.player?.get() === player && it.item.get() !== actualItem }.forEach { entry ->
            entry.selected = false
            if (entry.ticket.committedItem?.get() === entry.item.get()) entry.ticket.state.set(CANCELLED)
        }
        entries.firstOrNull { it.item.get() === actualItem && actualItem != null }?.let { entry ->
            entry.player = WeakReference(player)
            entry.selected = true
        }
    }

    @Synchronized fun commit(ticket: Ticket, actualItem: Any?): Boolean {
        val matches = actualItem != null && entries.any {
            it.ticket === ticket && it.item.get() === actualItem && it.selected && it.player?.get() != null
        }
        if (!matches || ticket.state.get() != PENDING) {
            if (active?.committedItem?.get() !== actualItem) {
                active?.state?.set(CANCELLED)
                active = null
            }
            ticket.state.set(CANCELLED)
            return false
        }
        active?.takeIf { it !== ticket }?.state?.set(CANCELLED)
        active = ticket
        ticket.committedItem = WeakReference(actualItem!!)
        ticket.state.set(COMMITTED)
        return true
    }

    fun cancel(ticket: Ticket) { ticket.state.set(CANCELLED) }
    @Synchronized fun remove(item: Any) {
        entries.filter { it.item.get() === item }.forEach { entry ->
            entry.selected = false
            if (entry.ticket.state.get() == PENDING || entry.ticket.committedItem?.get() === item) {
                entry.ticket.state.set(CANCELLED)
            }
        }
        entries.removeAll { it.item.get() == null || it.item.get() === item }
    }
    fun current(entry: Entry): Boolean {
        val item = entry.item.get() ?: return false
        return entry.ticket.state.get() == COMMITTED && entry.ticket.committedItem?.get() === item &&
            entry.selected && entry.player?.get() != null
    }
    fun hasPlayIntent(entry: Entry): Boolean = entry.item.get() != null && when (entry.ticket.state.get()) {
        PENDING -> true
        COMMITTED -> current(entry)
        else -> false
    }

    companion object {
        private const val PENDING = 0
        private const val COMMITTED = 1
        private const val CANCELLED = 2
        private const val MAX_ITEMS = 16
    }
}

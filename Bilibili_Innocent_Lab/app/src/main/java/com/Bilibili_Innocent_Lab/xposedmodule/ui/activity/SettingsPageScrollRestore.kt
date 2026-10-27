package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** A page keeps its saved offset until its first layout or actual input on that page. */
internal class SettingsPageScrollRestore(savedOffsets: List<Int>) {
    internal class Ticket internal constructor(val page: Int, val offset: Int)
    private val pending = savedOffsets.map { it.takeIf { value -> value > 0 } }.toMutableList()
    private val tickets = arrayOfNulls<Ticket>(savedOffsets.size)
    private var closed = false

    fun prepare(page: Int): Ticket? {
        if (closed) return null
        val offset = pending[page] ?: return null
        return Ticket(page, offset).also { tickets[page] = it }
    }

    fun consume(ticket: Ticket): Int? {
        if (closed || tickets[ticket.page] !== ticket) return null
        tickets[ticket.page] = null
        pending[ticket.page] = null
        return ticket.offset
    }

    fun userInput(page: Int) {
        tickets[page] = null
        pending[page] = null
    }

    fun savedOffset(page: Int, currentOffset: Int): Int = pending[page] ?: currentOffset

    fun close() {
        closed = true
        tickets.fill(null)
        pending.indices.forEach { pending[it] = null }
    }
}

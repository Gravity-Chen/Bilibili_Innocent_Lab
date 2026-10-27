package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** A preference notification cannot confirm a write whose verification has not completed. */
internal object SettingWritePolicy {
    data class Outcome(val confirmed: Boolean, val failure: Throwable?) {
        val saved: Boolean get() = failure == null
    }

    fun attempt(previous: Boolean, requested: Boolean, writeAndVerify: () -> Unit): Outcome =
        try {
            writeAndVerify()
            Outcome(requested, null)
        } catch (failure: Throwable) {
            Outcome(previous, failure)
        }
}

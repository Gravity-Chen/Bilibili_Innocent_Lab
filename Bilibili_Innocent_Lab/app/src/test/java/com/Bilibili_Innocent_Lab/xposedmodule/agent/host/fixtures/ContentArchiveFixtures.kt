package com.bapis.bilibili.app.archive.v1

/** 已验真 Arc/Author getter 的测试替身。 */
class Arc(private val aid: Long, private val title: String, private val duration: Long, private val author: Author?) {
    fun getAid(): Long = aid
    fun getTitle(): String = title
    fun getDuration(): Long = duration
    fun hasAuthor(): Boolean = author != null
    fun getAuthor(): Author = author ?: Author(0, "")
}

class Author(private val mid: Long, private val name: String) {
    fun getMid(): Long = mid
    fun getName(): String = name
}

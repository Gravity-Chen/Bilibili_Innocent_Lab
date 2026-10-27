package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** Logical targets survive lazy construction; no View, motion progress or process token is saved. */
internal class SettingsExpansionState(
    secondaryNames: Set<String>,
    categoryDefaults: Map<String, Boolean>,
    fixedCategories: Set<String> = emptySet(),
    schemaVersion: Int = SCHEMA_VERSION,
    savedValues: Map<String, Boolean> = emptyMap(),
) {
    companion object {
        const val BUNDLE_KEY = "settings_expansion"
        const val SCHEMA_VERSION = 1
    }

    private val fixed = fixedCategories.map { "category:$it" }.toSet()
    private val values = linkedMapOf<String, Boolean>().apply {
        secondaryNames.forEach { put("secondary:$it", false) }
        categoryDefaults.forEach { (name, expanded) -> put("category:$name", expanded) }
    }

    init {
        require(secondaryNames.all(String::isNotBlank) && categoryDefaults.keys.all(String::isNotBlank))
        require(fixedCategories.all { it in categoryDefaults })
        if (schemaVersion == SCHEMA_VERSION) values.keys.toList().forEach { key ->
            if (key !in fixed) savedValues[key]?.let { values[key] = it }
        }
    }

    fun secondary(name: String): Boolean = values.getValue("secondary:$name")
    fun category(name: String): Boolean = values.getValue("category:$name")

    fun setSecondary(name: String, expanded: Boolean) {
        val key = "secondary:$name"
        require(key in values)
        values[key] = expanded
    }

    fun setCategory(name: String, expanded: Boolean) {
        val key = "category:$name"
        require(key in values)
        if (key !in fixed) values[key] = expanded
    }

    fun snapshot(): Map<String, Boolean> = values.toMap()
}

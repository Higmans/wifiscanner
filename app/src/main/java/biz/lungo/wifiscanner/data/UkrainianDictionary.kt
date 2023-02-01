package biz.lungo.wifiscanner.data

import nl.mirrajabi.humanize.duration.languages.DictionaryKeys
import nl.mirrajabi.humanize.duration.languages.LanguageDictionary

class UkrainianDictionary : LanguageDictionary {
    private val words = mapOf(
        DictionaryKeys.YEAR to "р.",
        DictionaryKeys.MONTH to "м.",
        DictionaryKeys.WEEK to "тиж.",
        DictionaryKeys.DAY to "д.",
        DictionaryKeys.HOUR to "г.",
        DictionaryKeys.MINUTE to "хв.",
        DictionaryKeys.SECOND to "сек.",
        DictionaryKeys.MILLISECOND to "мс.",
        DictionaryKeys.DECIMAL to "."
    )

    override fun provide(key: String, count: Double): String {
        return words[key] ?: ""
    }
}
package com.ssytdlp.app

enum class LibraryPageSize(val items: Int) {
    ITEMS_50(50),
    ITEMS_100(100),
    ITEMS_200(200),
    ITEMS_500(500),
    ITEMS_1000(1000);

    companion object {
        val DEFAULT = ITEMS_50
        fun fromPreference(items: Int?): LibraryPageSize =
            entries.firstOrNull { it.items == items } ?: DEFAULT
    }
}

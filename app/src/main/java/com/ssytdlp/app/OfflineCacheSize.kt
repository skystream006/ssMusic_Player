package com.ssytdlp.app

internal enum class OfflineCacheSize(val bytes: Long, val label: String) {
    MIB_64(64L * 1024 * 1024, "64 MiB"),
    MIB_128(128L * 1024 * 1024, "128 MiB"),
    MIB_256(256L * 1024 * 1024, "256 MiB"),
    MIB_512(512L * 1024 * 1024, "512 MiB"),
    GIB_1(1024L * 1024 * 1024, "1 GiB"),
    GIB_2(2L * 1024 * 1024 * 1024, "2 GiB");

    companion object {
        val DEFAULT = MIB_256
        fun fromPreference(bytes: Long?): OfflineCacheSize =
            entries.firstOrNull { it.bytes == bytes } ?: DEFAULT
    }
}

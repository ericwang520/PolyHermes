package com.wrbug.polymarketbot.enums

enum class CopyExecutionMode {
    LIVE,
    PAPER;

    companion object {
        fun parse(value: String?): CopyExecutionMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("executionMode 必须是 LIVE 或 PAPER")
    }
}

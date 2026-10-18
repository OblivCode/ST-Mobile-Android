package app.stmobile.ui.theme

enum class ThemeMode(val code: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromCode(code: Int): ThemeMode = entries.find { it.code == code } ?: SYSTEM
    }
}

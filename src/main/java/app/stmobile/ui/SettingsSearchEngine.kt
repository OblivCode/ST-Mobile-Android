package app.stmobile.ui

enum class SettingsSearchTab {
    ALL,
    APP,
    SILLYTAVERN,
    NODE_ENGINE,
    WEBVIEW,
}

data class SettingsSearchItem(
    val id: String,
    val tab: SettingsSearchTab,
    val category: String,
    val title: String,
    val description: String = "",
    val keywords: List<String> = emptyList(),
)

class SettingsSearchEngine(
    private val items: List<SettingsSearchItem>,
) {
    fun search(query: String, selectedTab: SettingsSearchTab = SettingsSearchTab.ALL): List<SettingsSearchItem> {
        val trimmed = query.trim().lowercase()
        val queryTokens = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }

        return items.filter { item ->
            val tabMatches = selectedTab == SettingsSearchTab.ALL || item.tab == selectedTab
            if (!tabMatches) return@filter false

            if (queryTokens.isEmpty()) return@filter true

            val searchableText = buildString {
                append(item.title.lowercase())
                append(" ")
                append(item.description.lowercase())
                append(" ")
                append(item.category.lowercase())
                append(" ")
                item.keywords.forEach { kw ->
                    append(kw.lowercase())
                    append(" ")
                }
            }

            queryTokens.all { token -> searchableText.contains(token) }
        }
    }
}

package com.twentygx.tgxcode.data.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/models` — the models and effort levels the bridge offers, from its
 * `bridge/models.js`. Every model and effort picker in the app is built from this, as
 * the desktop's are. [ModelInfo.id] is the alias to send (`opus`, not `claude-opus-…`);
 * the order is the order to show them in. `inherit` is not in it: it is not a model.
 */
@Serializable
data class ModelsCatalog(
    val models: List<ModelInfo> = emptyList(),
    val efforts: List<String> = emptyList(),
) {
    val modelIds: List<String> get() = models.map { it.id }

    fun labelOf(id: String): String = models.firstOrNull { it.id == id }?.label ?: id

    companion object {
        /**
         * What a bridge that has not answered yet is assumed to offer — the list
         * `bridge/models.js` held when this was written. Replaced by the first read, so
         * it is only ever on screen for the moment before that, or against an old bridge.
         */
        val FALLBACK = ModelsCatalog(
            models = listOf("opus", "sonnet", "haiku", "fable").map { ModelInfo(it, it.replaceFirstChar(Char::uppercase)) },
            efforts = listOf("low", "medium", "high", "xhigh", "max"),
        )
    }
}

@Serializable
data class ModelInfo(
    val id: String,
    val label: String = id,
    /** One line on when it fits. */
    val note: String = "",
)

package com.twentygx.tgxcode.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.twentygx.tgxcode.data.model.ModelsCatalog
import com.twentygx.tgxcode.data.model.SessionChoices

/** `GET /api/models`, observed, so a picker redraws when the first read lands. */
@Composable
fun modelsCatalog(): ModelsCatalog {
    val catalog by SessionChoices.catalog.collectAsState()
    return catalog
}

/** A model picker's options: `""` (`inherit`) first, then the bridge's models. */
@Composable
fun modelOptions(): List<String> = listOf("") + modelsCatalog().modelIds

/** An effort picker's options: `""` (`inherit`) first, then the bridge's levels. */
@Composable
fun effortOptions(): List<String> = listOf("") + modelsCatalog().efforts

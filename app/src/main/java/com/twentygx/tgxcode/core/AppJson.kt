package com.twentygx.tgxcode.core

import com.twentygx.tgxcode.data.model.TranscriptEvent
import com.twentygx.tgxcode.data.model.UnknownEvent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * The one configured [Json] for the whole app.
 *
 * Three settings here are load-bearing rather than taste:
 *
 * - `ignoreUnknownKeys`: the live bridge already returns a dozen fields `docs/api.md`
 *   does not document (`projectCwd`, `gitBranch`, `titleSource`, `sessionKind`…), so strict
 *   parsing would break on the next bridge release rather than on a real problem.
 * - `explicitNulls = false`: outgoing bodies must *omit* absent fields, not send
 *   `null`. This matters most on `/send`, where the bridge normalises an absent
 *   `permissionMode` to `auto` — but it should never see one we did not mean.
 * - `polymorphicDefaultDeserializer`: JavaScript's `default: return null` on an
 *   unknown event kind costs nothing. Kotlin's sealed polymorphism throws, and a
 *   throw here fails the *entire* batch of events, not just the odd one. So an
 *   unrecognised kind decodes to [UnknownEvent] and renders as nothing.
 */
@OptIn(ExperimentalSerializationApi::class)
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    // The wire's discriminator is `kind`, not the library default `type`.
    classDiscriminator = "kind"
    serializersModule = SerializersModule {
        polymorphicDefaultDeserializer(TranscriptEvent::class) { UnknownEvent.serializer() }
    }
}

/** Pretty-printer used only for rendering unknown tool inputs in a tool body. */
val PrettyJson: Json = Json(AppJson) { prettyPrint = true }

package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.ModelsCatalog
import com.twentygx.tgxcode.data.model.OrchestratorPayload
import com.twentygx.tgxcode.data.model.OrchestratorPut
import com.twentygx.tgxcode.data.model.OrchestratorSettings
import com.twentygx.tgxcode.data.model.SessionChoices
import com.twentygx.tgxcode.data.model.SettingsPatch
import com.twentygx.tgxcode.ui.orchestrator.shortModel
import com.twentygx.tgxcode.ui.orchestrator.workerModelWords
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The orchestrator's model settings, `GET /api/models` and a worker's model fields. */
class OrchestratorModelsTest {

    @After fun resetCatalog() = SessionChoices.setCatalog(ModelsCatalog.FALLBACK)

    @Test fun `settings decode the six model fields`() {
        val p = AppJson.decodeFromString<OrchestratorPayload>(
            """{"orchestratorId":"o","enabled":true,"ownModelApplied":"after-turn","settings":{"maxRunning":2,
               "orchestratorModel":"opus","orchestratorEffort":null,"workerModel":"choose","workerEffort":"high",
               "workerDefaultModel":"haiku","workerDefaultEffort":null}}""",
        )
        val s = p.settings
        assertEquals("opus", s.orchestratorModel)
        assertNull(s.orchestratorEffort)
        assertEquals(OrchestratorSettings.CHOOSE, s.workerModel)
        assertEquals("high", s.workerEffort)
        assertEquals("haiku", s.workerDefaultModel)
        assertNull(s.workerDefaultEffort)
        assertEquals("after-turn", p.ownModelApplied)
    }

    @Test fun `settings from an older bridge read as choose and inherit`() {
        val s = AppJson.decodeFromString<OrchestratorPayload>("""{"orchestratorId":"o","enabled":true,"settings":{}}""").settings
        assertNull(s.orchestratorModel)
        assertEquals("choose", s.workerModel)
        assertEquals("choose", s.workerEffort)
        assertNull(s.workerDefaultModel)
    }

    @Test fun `a cleared model is sent as null and an untouched one is left out`() {
        val body = AppJson.encodeToString(
            OrchestratorPut.serializer(),
            OrchestratorPut(settings = SettingsPatch(orchestratorModel = SettingsPatch.modelValue(""))),
        )
        assertEquals("""{"settings":{"orchestratorModel":null}}""", body)
        val set = AppJson.encodeToString(
            OrchestratorPut.serializer(),
            OrchestratorPut(settings = SettingsPatch(workerModel = JsonPrimitive("sonnet"), workerDefaultEffort = SettingsPatch.modelValue("low"))),
        )
        assertEquals("""{"settings":{"workerModel":"sonnet","workerDefaultEffort":"low"}}""", set)
    }

    @Test fun `the models list decodes and becomes every picker's options`() {
        val c = AppJson.decodeFromString<ModelsCatalog>(
            """{"models":[{"id":"opus","label":"Opus","note":"hard work"},{"id":"newone","label":"New One","note":""}],
               "efforts":["low","high"]}""",
        )
        assertEquals(listOf("opus", "newone"), c.modelIds)
        assertEquals("New One", c.labelOf("newone"))
        assertEquals("hard work", c.models[0].note)
        SessionChoices.setCatalog(c)
        assertEquals(listOf("", "opus", "newone"), SessionChoices.MODELS)
        assertEquals(listOf("", "low", "high"), SessionChoices.EFFORTS)
        assertEquals("newone", SessionChoices.knownModel("newone"))
        assertEquals("", SessionChoices.knownModel("sonnet"))
    }

    @Test fun `an empty models answer keeps what there was`() {
        SessionChoices.setCatalog(ModelsCatalog())
        assertEquals(ModelsCatalog.FALLBACK, SessionChoices.catalog.value)
    }

    @Test fun `worker rows decode model fields and say where they came from`() {
        val p = AppJson.decodeFromString<OrchestratorPayload>(
            """{"orchestratorId":"o","enabled":true,"workers":[
               {"id":"a","model":"haiku","effort":"low","modelSource":{"model":"orchestrator","effort":"orchestrator"}},
               {"id":"b","model":"claude-sonnet-4-5-20250929","effort":null,"modelSource":{"model":"fixed","effort":"inherit"}},
               {"id":"c","model":null,"effort":"high","modelSource":{"model":"inherit","effort":"default"}},
               {"id":"d"}]}""",
        )
        val (a, b, c, d) = p.workers
        assertEquals("orchestrator", a.modelSource?.model)
        assertEquals("haiku · low effort", workerModelWords(a)?.text)
        assertNull(workerModelWords(a)?.marker)
        assertEquals("sonnet-4-5", workerModelWords(b)?.text)
        assertEquals("fixed", workerModelWords(b)?.marker)
        assertEquals("default model · high effort", workerModelWords(c)?.text)
        assertEquals("default", workerModelWords(c)?.marker)
        assertNull(workerModelWords(d))
    }

    @Test fun `short model names`() {
        assertEquals("opus-4-1", shortModel("claude-opus-4-1-20250805"))
        assertEquals("opus", shortModel("opus"))
    }

    @Test fun `the policy line says whether workers are fixed`() {
        val choose = workerModelWords(OrchestratorSettings()) { it }
        assertTrue(choose.startsWith("It is told which models there are"))
        val fixed = workerModelWords(OrchestratorSettings(workerModel = "sonnet")) { "Sonnet" }
        assertTrue(fixed.startsWith("Every worker runs on Sonnet"))
    }
}

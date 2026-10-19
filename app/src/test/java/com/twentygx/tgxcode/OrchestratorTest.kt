package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.Ask
import com.twentygx.tgxcode.data.model.BoardCard
import com.twentygx.tgxcode.data.model.OrchestratorInboxEvent
import com.twentygx.tgxcode.data.model.OrchestratorMessageEvent
import com.twentygx.tgxcode.data.model.OrchestratorPayload
import com.twentygx.tgxcode.data.model.OrchestratorPut
import com.twentygx.tgxcode.data.model.SettingsPatch
import com.twentygx.tgxcode.data.model.StreamEvent
import com.twentygx.tgxcode.data.model.TaskFields
import com.twentygx.tgxcode.data.model.TaskFiling
import com.twentygx.tgxcode.data.model.TranscriptEvent
import com.twentygx.tgxcode.data.model.UsageStop
import com.twentygx.tgxcode.data.model.UsageStopPatch
import com.twentygx.tgxcode.data.net.SseCodec
import com.twentygx.tgxcode.data.repo.decisionMessage
import com.twentygx.tgxcode.ui.orchestrator.NeedItem
import com.twentygx.tgxcode.ui.orchestrator.STUCK_MS
import com.twentygx.tgxcode.ui.orchestrator.StepState
import com.twentygx.tgxcode.ui.orchestrator.Tone
import com.twentygx.tgxcode.ui.orchestrator.inboxNudgeText
import com.twentygx.tgxcode.ui.orchestrator.mergeOrder
import com.twentygx.tgxcode.ui.orchestrator.needsCount
import com.twentygx.tgxcode.ui.orchestrator.needsYou
import com.twentygx.tgxcode.ui.orchestrator.progress
import com.twentygx.tgxcode.ui.orchestrator.shortAge
import com.twentygx.tgxcode.ui.orchestrator.usageMeters
import com.twentygx.tgxcode.ui.orchestrator.workerStage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestrator page's rules, against payloads in the shape `docs/api.md`
 * §Orchestrators gives — the same verdicts `web/orchestrator-model.js` reaches.
 */
class OrchestratorTest {

    private val now = 1_790_000_000_000L

    private val payload = """
        {"orchestratorId":"orch-1","enabled":true,
         "settings":{"maxRunning":3,"worktree":true,
           "usageStop":{"enabled":true,"percent":90,"window":"five_hour",
             "limits":{"five_hour":90,"seven_day":95,"seven_day_opus":null,"seven_day_sonnet":null},
             "autoResume":true,"autoResumeBy":{"by":"agent","at":${now - 60_000},"reason":"overnight run"}}},
         "paused":null,
         "summary":{"text":"Two workers on the export.","at":${now - 120_000},"goal":"Ship CSV export",
           "decisions":[
             {"id":"d2","text":"Which delimiter?","options":["comma","tab"],"workerId":"w1",
              "at":${now - 50_000},"answer":null,"answeredAt":null},
             {"id":"d1","text":"Keep the old route?","options":[],"workerId":null,
              "at":${now - 90_000},"answer":"yes","answeredAt":${now - 10_000}},
             {"id":"d3","text":"Name the file how?","options":[],"workerId":null,
              "at":${now - 80_000},"answer":null,"answeredAt":null}],
           "mergeOrder":[212, 999]},
         "workers":[
           {"id":"w2","title":"Tests","cwd":"/p","worktree":{"path":"/p/.claude/worktrees/t","branch":"tests"},
            "spawnedAt":${now - 500_000},"closedAt":null,"state":"busy","pending":null,
            "pr":{"number":212,"url":"https://github.com/o/r/pull/212","repo":"o/r","title":"Add tests",
                  "status":"merged","label":"merged"},"origin":null},
           {"id":"w1","title":"Export","cwd":"/p","worktree":null,
            "spawnedAt":${now - 900_000},"closedAt":null,"state":"idle","pending":"plan","pr":null,
            "origin":{"from":"draft","draftId":"dr1","draftCreatedAt":1,"title":"CSV export"}}],
         "pendingSpawns":[{"id":"q1","title":"Docs","at":${now - 5_000},"origin":null}],
         "openTasks":[{"id":"t1","at":${now - 30_000},"status":"new","text":"Write the docs\nfor it",
           "cwd":"/p","title":null,"model":null,"effort":null,"permissionMode":"plan","test":false,
           "worktree":null,"origin":{"from":"dialog","draftId":null,"draftCreatedAt":null,"title":null}}],
         "unread":2,
         "inbox":[
           {"id":"i2","workerId":"w1","kind":"plan","text":"## Plan","at":${now - STUCK_MS - 1_000},"seq":2,
            "status":"read","requestId":"r1","ask":{"kind":"plan","tool":"ExitPlanMode","displayName":null,"input":{}},
            "outcome":null,"by":null,"settledAt":null,"task":null},
           {"id":"i1","workerId":"w2","kind":"turn","text":"Tests written","at":${now - 400_000},"seq":1,
            "status":"new","requestId":null,"ask":null,"outcome":null,"by":null,"settledAt":null,"task":null}]}
    """.trimIndent()

    private fun decoded() = AppJson.decodeFromString(OrchestratorPayload.serializer(), payload)

    @Test
    fun `the payload decodes, nulls and all`() {
        val p = decoded()
        assertTrue(p.enabled)
        assertEquals("Ship CSV export", p.summary?.goal)
        assertEquals(listOf(212, 999), p.summary?.mergeOrder)
        assertNull(p.settings.usageStop.limits.seven_day_opus)
        assertEquals("agent", p.settings.usageStop.autoResumeBy?.by)
        assertEquals("draft", p.workers[1].origin?.from)
        assertEquals("Write the docs", p.openTasks[0].displayTitle)
        assertEquals("r1", p.inbox[0].requestId)
    }

    @Test
    fun `a session that is not one decodes to a disabled payload`() {
        val p = AppJson.decodeFromString(OrchestratorPayload.serializer(), """{"orchestratorId":"s","enabled":false}""")
        assertFalse(p.enabled)
        assertTrue(p.workers.isEmpty())
    }

    @Test
    fun `the orchestrator event carries the whole payload`() {
        val event = SseCodec.decode("orchestrator", payload)
        assertTrue(event is StreamEvent.OrchestratorChanged)
        assertEquals("orch-1", (event as StreamEvent.OrchestratorChanged).payload.orchestratorId)
    }

    @Test
    fun `the two orchestrator transcript kinds decode`() {
        val events = AppJson.decodeFromString(
            ListSerializer(TranscriptEvent.serializer()),
            """[{"kind":"orchestrator-inbox","id":"a","count":3,"counts":{"plan":1,"turn":2},"summary":"x"},
                {"kind":"orchestrator-message","id":"b","text":"Rebase on main","from":"orch-1","fromTitle":"Export lead"}]""",
        )
        val nudge = events[0] as OrchestratorInboxEvent
        assertEquals("3 items waiting — 1 plan, 2 turn reports", inboxNudgeText(nudge.count, nudge.counts, nudge.summary))
        assertEquals("Export lead", (events[1] as OrchestratorMessageEvent).fromTitle)
    }

    @Test
    fun `a nudge from before counts falls back to its summary`() {
        assertEquals("1 item waiting — 1 plan to approve", inboxNudgeText(1, null, "1 plan to approve"))
    }

    @Test
    fun `board cards learn who an ask is for and which orchestrator carries them`() {
        val card = AppJson.decodeFromString(
            BoardCard.serializer(),
            """{"sessionId":"w1","reason":"ask","askFor":"orchestrator","orchestratorId":"orch-1",
                "ask":{"requestId":"r1","kind":"plan"}}""",
        )
        assertTrue(card.askForOrchestrator)
        assertTrue(card.why.endsWith("its orchestrator has it"))
        val host = AppJson.decodeFromString(
            BoardCard.serializer(),
            """{"sessionId":"orch-1","reason":"here","workers":{"ids":["w1"],"queued":[{"id":"q","title":null,"at":0}],
                "idle":1,"finished":2,"own":false,"paused":false,
                "quiet":[{"id":"w9","title":"Old","state":"finished","at":null,"standing":null}]}}""",
        )
        assertEquals(listOf("w1"), host.workers?.ids)
        assertFalse(host.workers!!.own)
    }

    // ------------------------------------------------------------- needs you

    @Test
    fun `decisions come unanswered first, oldest first, and answered ones are not counted`() {
        val needs = needsYou(decoded(), orchState = "busy", orchAsk = null, now = now)
        val decisions = needs.filterIsInstance<NeedItem.Decision>().map { it.decision.id }
        assertEquals(listOf("d3", "d2", "d1"), decisions)
        assertEquals(2, needsCount(needs))
    }

    @Test
    fun `a worker's ask is yours once the orchestrator has not got to it in three minutes`() {
        val idle = needsYou(decoded(), orchState = "idle", orchAsk = null, now = now)
        assertTrue(idle.any { it is NeedItem.Stuck && it.worker.id == "w1" })

        // Busy on it: still the orchestrator's.
        val busy = needsYou(decoded(), orchState = "busy", orchAsk = null, now = now)
        assertFalse(busy.any { it is NeedItem.Stuck })

        // Busy but itself waiting on an ask is not working on anything.
        val asking = needsYou(decoded(), orchState = "busy", orchAsk = Ask(requestId = "x", kind = "plan"), now = now)
        assertTrue(asking.first() is NeedItem.OrchestratorAsk)
        assertTrue(asking.any { it is NeedItem.Stuck })

        // Not old enough yet.
        val early = needsYou(decoded(), orchState = "idle", orchAsk = null, now = now - 2_000)
        assertFalse(early.any { it is NeedItem.Stuck })
    }

    @Test
    fun `a pause leads`() {
        val paused = decoded().copy(paused = com.twentygx.tgxcode.data.model.OrchestratorPause(reason = "five_hour at 91%"))
        assertTrue(needsYou(paused, "idle", null, now).first() is NeedItem.Paused)
    }

    // ---------------------------------------------------------------- stages

    @Test
    fun `a worker waiting on its plan is at Plan, waiting`() {
        val p = decoded()
        val stage = workerStage(p.workers.first { it.id == "w1" }, p.inbox)
        assertEquals(2, stage.at) // the plan is filed (an inbox item) and waits for approval
        assertEquals(Tone.Wait, stage.tone)
        assertEquals(StepState.Done, stage.steps[0])
        assertEquals(StepState.Now, stage.steps[1])
    }

    @Test
    fun `a merged PR is the last stage, whatever else the worker is doing`() {
        val p = decoded()
        val stage = workerStage(p.workers.first { it.id == "w2" }, p.inbox)
        assertEquals(6, stage.at)
        // Still running: the tone says so, the stage says where it has got.
        assertEquals(Tone.Run, stage.tone)
        assertEquals(StepState.Skipped, stage.steps[0])
    }

    @Test
    fun `progress counts finished, running and queued`() {
        val pr = progress(decoded())
        assertEquals(3, pr.total)
        assertEquals(1, pr.finished)
        assertEquals(1, pr.running)
        assertEquals(1, pr.queued)
        assertEquals(3, pr.max)
    }

    // ----------------------------------------------------------- merge order

    @Test
    fun `the brief's merge order wins, and a PR no worker owns is drawn by number`() {
        val order = mergeOrder(decoded())
        assertTrue(order.fromBrief)
        assertEquals(listOf(212, 999), order.rows.map { it.number })
        assertEquals("w2", order.rows[0].workerId)
        assertEquals("Add tests", order.rows[0].title)
        assertNull(order.rows[1].workerId)
        assertEquals(listOf("w1"), order.rest.map { it.id })
    }

    @Test
    fun `without one, the workers' PRs go in the order they started`() {
        val p = decoded()
        val order = mergeOrder(p.copy(summary = p.summary?.copy(mergeOrder = emptyList())))
        assertFalse(order.fromBrief)
        assertEquals(listOf(212), order.rows.map { it.number })
    }

    // ----------------------------------------------------------------- wire

    @Test
    fun `an answer is sent as Re and the question`() {
        val d = decoded().summary!!.decisions.first { it.id == "d2" }
        assertEquals("Re: Which delimiter?\n\ntab", decisionMessage(d, " tab "))
    }

    @Test
    fun `a settings patch sends only what changed, and a cleared limit as null`() {
        val json = AppJson.encodeToString(
            OrchestratorPut.serializer(),
            OrchestratorPut(settings = SettingsPatch(usageStop = UsageStopPatch(limits = JsonObject(mapOf("seven_day" to JsonNull))))),
        )
        assertEquals("""{"settings":{"usageStop":{"limits":{"seven_day":null}}}}""", json)
    }

    @Test
    fun `a task from the form leaves out what was left on inherit`() {
        val json = AppJson.encodeToString(
            TaskFiling.serializer(),
            TaskFiling(draftId = "dr1", fields = TaskFields(cwd = "/p", prompt = "Do it", permissionMode = "plan", test = false)),
        )
        assertEquals("""{"draftId":"dr1","fields":{"cwd":"/p","prompt":"Do it","permissionMode":"plan","test":false}}""", json)
    }

    // ---------------------------------------------------------------- usage

    @Test
    fun `meters show the weekly model windows only when the cutoff watches them`() {
        val single = usageMeters(UsageStop(enabled = true, percent = 80, window = "five_hour"), mapOf("five_hour" to 75.0))
        assertEquals(listOf("5h", "Week"), single.map { it.label })
        assertEquals("near", single[0].tone)
        assertNull(single[1].cutoff)

        val all = usageMeters(
            UsageStop(enabled = true, window = "all", limits = com.twentygx.tgxcode.data.model.UsageLimits(five_hour = 90, seven_day_opus = 50)),
            mapOf("seven_day_opus" to 60.0),
        )
        assertEquals(listOf("5h", "Week", "Week Opus"), all.map { it.label })
        assertEquals("over", all[2].tone)
    }

    @Test
    fun `short ages`() {
        assertEquals("now", shortAge(30_000))
        assertEquals("4m", shortAge(4 * 60_000))
        assertEquals("3h", shortAge(3 * 3_600_000))
        assertEquals("2d", shortAge(49 * 3_600_000))
    }
}

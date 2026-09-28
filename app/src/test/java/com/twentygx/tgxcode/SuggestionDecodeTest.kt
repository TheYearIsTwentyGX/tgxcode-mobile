package com.twentygx.tgxcode

import com.twentygx.tgxcode.core.AppJson
import com.twentygx.tgxcode.data.model.SuggestedTask
import com.twentygx.tgxcode.data.model.SuggestionActRequest
import com.twentygx.tgxcode.data.model.SuggestionEvent
import com.twentygx.tgxcode.data.model.SuggestionsResponse
import com.twentygx.tgxcode.data.model.TaskStatus
import com.twentygx.tgxcode.data.model.TranscriptEvent
import com.twentygx.tgxcode.data.repo.openDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suggested follow-ups, in the shapes the bridge actually sends.
 *
 * The one worth a test rather than a read-through is [undo encodes as an empty object]:
 * `docs/api.md:1246` says a decision is undone by a body with the status *absent*, and
 * nothing in the type system distinguishes an absent field from a null one — only
 * `explicitNulls = false` does, and it is set three files away in `AppJson`. A model
 * change that turned that off would break undo silently.
 */
class SuggestionDecodeTest {

    private val listPayload = """
        {"ready":true,
         "suggestions":[
           {"id":"toolu_01A","kind":"suggestion","sessionId":"s-open",
            "ts":"2026-08-19T15:53:51.009Z",
            "title":"Task persistence","why":"The board rescans from scratch.",
            "prompt":"Cache the scanned meta under CACHE_VERSION.",
            "cwd":"/home/dylan_hays/Other/claude-sessions",
            "status":"open","startedId":null,"at":0,"archived":false,
            "session":{"title":"Board perf","projectName":"claude-sessions",
                       "projectCwd":"/home/dylan_hays/Other/claude-sessions",
                       "worktree":null,"test":false}},
           {"id":"toolu_01B","kind":"suggestion","sessionId":"s-started",
            "ts":"2026-08-19T14:00:00.000Z",
            "prompt":"Chase the flaky import test.\nIt fails about one run in five.",
            "cwd":"/home/dylan_hays/Other/tgxcode-mobile",
            "status":"started","startedId":"s-new","at":1787161000629,"archived":false,
            "session":{"title":"CI","projectName":"tgxcode-mobile",
                       "worktree":{"name":"worktree-flake"},"test":false}},
           {"id":"toolu_01C","kind":"suggestion","sessionId":"s-gone",
            "ts":"2026-08-18T09:00:00.000Z","title":"Old idea",
            "prompt":"Something from an archived conversation.",
            "cwd":"/home/dylan_hays/Other/claude-sessions",
            "status":"dismissed","startedId":null,"at":1787000000000,"archived":true,
            "session":{"title":"Filed away","projectName":"claude-sessions","test":false}}
         ]}
    """.trimIndent()

    @Test
    fun `a suggestions payload decodes with its session join`() {
        val response = AppJson.decodeFromString(SuggestionsResponse.serializer(), listPayload)

        assertTrue(response.ready)
        assertEquals(3, response.suggestions.size)

        val open = response.suggestions[0]
        assertEquals(TaskStatus.OPEN, open.status)
        assertTrue(open.isOpen)
        assertEquals("Task persistence", open.displayTitle)
        assertEquals("claude-sessions", open.session?.projectName)
        assertNull(open.startedId)
    }

    @Test
    fun `an absent title falls back to the first line of the prompt`() {
        val response = AppJson.decodeFromString(SuggestionsResponse.serializer(), listPayload)
        val started = response.suggestions[1]

        assertNull(started.title)
        assertEquals("Chase the flaky import test.", started.displayTitle)
        assertEquals("s-new", started.startedId)
    }

    /** Worktree beats project name, as it does on every other row in this app. */
    @Test
    fun `place prefers the worktree name`() {
        val response = AppJson.decodeFromString(SuggestionsResponse.serializer(), listPayload)

        assertEquals("claude-sessions", response.suggestions[0].place)
        assertEquals("worktree-flake", response.suggestions[1].place)
    }

    /**
     * A task from an archived session is still returned, and must survive the decode
     * carrying its flag — the UI dims it rather than dropping it, because dismissing is
     * already the gesture for "not this".
     */
    @Test
    fun `an archived session's task still decodes`() {
        val response = AppJson.decodeFromString(SuggestionsResponse.serializer(), listPayload)
        val archived = response.suggestions[2]

        assertTrue(archived.archived)
        assertEquals(TaskStatus.DISMISSED, archived.status)
        assertFalse(archived.isOpen)
    }

    /**
     * The offer and the transcript event are promised to be the same fields
     * (`docs/api.md:824`). If that stops being true, this is where it shows.
     */
    @Test
    fun `a suggestion transcript event converts to the same task`() {
        val json = """
            {"kind":"suggestion","id":"toolu_01A","ts":"2026-08-19T15:53:51.009Z",
             "title":"Task persistence","why":"The board rescans from scratch.",
             "prompt":"Cache the scanned meta under CACHE_VERSION.",
             "cwd":"/home/dylan_hays/Other/claude-sessions"}
        """.trimIndent()

        val event = AppJson.decodeFromString(TranscriptEvent.serializer(), json)
        assertTrue("a suggestion must not fall through to UnknownEvent", event is SuggestionEvent)

        // An event knows only the tail it arrived on, so the id has to be supplied.
        val task = (event as SuggestionEvent).toTask("s-open")
        val row = AppJson.decodeFromString(SuggestionsResponse.serializer(), listPayload)
            .suggestions[0]

        assertEquals(row.id, task.id)
        assertEquals(row.title, task.title)
        assertEquals(row.why, task.why)
        assertEquals(row.prompt, task.prompt)
        assertEquals(row.cwd, task.cwd)
        assertEquals(row.ts, task.ts)
        // The decision half is the part an event cannot know; absent means open.
        assertEquals(TaskStatus.OPEN, task.status)
    }

    /** **`{}`, never `{"status": null}`** — an absent status is what "undo" means. */
    @Test
    fun `undo encodes as an empty object`() {
        val body = AppJson.encodeToString(
            SuggestionActRequest.serializer(),
            SuggestionActRequest(),
        )

        assertEquals("{}", body)
    }

    @Test
    fun `starting encodes both the status and the id it started`() {
        val body = AppJson.encodeToString(
            SuggestionActRequest.serializer(),
            SuggestionActRequest(status = TaskStatus.STARTED, startedId = "s-new"),
        )

        assertTrue(body.contains("\"status\":\"started\""))
        assertTrue(body.contains("\"startedId\":\"s-new\""))
    }

    @Test
    fun `dismissing sends no startedId`() {
        val body = AppJson.encodeToString(
            SuggestionActRequest.serializer(),
            SuggestionActRequest(status = TaskStatus.DISMISSED),
        )

        assertEquals("{\"status\":\"dismissed\"}", body)
    }

    /** A row with no title and no prompt still has to name itself somehow. */
    @Test
    fun `an empty task still has a display title`() {
        assertEquals("Suggested task", SuggestedTask(id = "toolu_x").displayTitle)
    }
}

/**
 * The open-task badge, which is the count on the Tasks tab.
 *
 * It moves by a delta rather than being recounted, because a decision is written
 * optimistically and the list it would be counted from has not come back yet. Getting
 * the delta from the direction of travel instead of the row's real previous status is
 * the bug this pins: undo on something already open would then add one to a count
 * nothing had left.
 */
class OpenTaskBadgeTest {

    @Test
    fun `taking an open task off the list costs one`() {
        assertEquals(-1, openDelta(TaskStatus.OPEN, TaskStatus.DISMISSED))
        assertEquals(-1, openDelta(TaskStatus.OPEN, TaskStatus.STARTED))
    }

    @Test
    fun `undoing a decision puts one back`() {
        assertEquals(1, openDelta(TaskStatus.DISMISSED, TaskStatus.OPEN))
        assertEquals(1, openDelta(TaskStatus.STARTED, TaskStatus.OPEN))
    }

    @Test
    fun `a no-op moves nothing`() {
        assertEquals(0, openDelta(TaskStatus.OPEN, TaskStatus.OPEN))
        assertEquals(0, openDelta(TaskStatus.DISMISSED, TaskStatus.DISMISSED))
        // Started to dismissed never passes through open, so the count is unaffected.
        assertEquals(0, openDelta(TaskStatus.STARTED, TaskStatus.DISMISSED))
    }

    /** Not in the current filter's list: the direction of travel is all there is. */
    @Test
    fun `an unknown previous status falls back to the direction of travel`() {
        assertEquals(-1, openDelta(null, TaskStatus.DISMISSED))
        assertEquals(-1, openDelta(null, TaskStatus.STARTED))
        assertEquals(1, openDelta(null, TaskStatus.OPEN))
    }
}

package com.twentygx.tgxcode

import com.twentygx.tgxcode.ui.markdown.markdownToPlainText
import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {

    private fun plain(md: String) = markdownToPlainText(md.trimIndent())

    @Test
    fun `paragraphs keep a blank line between them`() {
        assertEquals("One.\n\nTwo.", plain("One.\n\n\n\nTwo."))
    }

    @Test
    fun `a soft break reads as a space`() {
        assertEquals("one line two", plain("one line\n  two"))
    }

    @Test
    fun `a hard break stays a line break`() {
        assertEquals("one\ntwo", plain("one  \ntwo"))
    }

    @Test
    fun `every bullet becomes a dash`() {
        assertEquals("- a\n- b\n- c", plain("* a\n* b\n* c"))
        assertEquals("- a\n- b", plain("+ a\n+ b"))
    }

    @Test
    fun `ordered items keep their numbers`() {
        assertEquals("1. first\n2. second", plain("1. first\n2. second"))
    }

    @Test
    fun `nested lists indent under their parent`() {
        assertEquals(
            "- outer\n  - inner\n  - inner two\n- next",
            plain(
                """
                - outer
                  * inner
                  * inner two
                - next
                """,
            ),
        )
    }

    @Test
    fun `a loose list keeps its blank lines`() {
        assertEquals("- a\n\n- b", plain("- a\n\n- b"))
    }

    @Test
    fun `task items keep their boxes`() {
        assertEquals("- [x] done\n- [ ] todo", plain("- [x] done\n- [ ] todo"))
    }

    @Test
    fun `emphasis markers are dropped`() {
        assertEquals("bold, italic and gone", plain("**bold**, _italic_ and ~~gone~~"))
    }

    @Test
    fun `inline code loses its backticks`() {
        assertEquals("run npm test now", plain("run `npm test` now"))
    }

    @Test
    fun `fenced code is kept verbatim without fences`() {
        assertEquals(
            "Run:\n\nif (x) {\n    y()\n}\n\nDone.",
            plain(
                """
                Run:

                ```kotlin
                if (x) {
                    y()
                }
                ```

                Done.
                """,
            ),
        )
    }

    @Test
    fun `headings lose their hashes`() {
        assertEquals("Title\n\nBody", plain("## Title\nBody"))
        assertEquals("Title\n\nBody", plain("Title\n=====\n\nBody"))
    }

    @Test
    fun `links show their target`() {
        assertEquals("see the docs (https://x.io)", plain("see [the docs](https://x.io)"))
        assertEquals("https://x.io", plain("[https://x.io](https://x.io)"))
        assertEquals("go to https://x.io now", plain("go to <https://x.io> now"))
        assertEquals("go to https://x.io now", plain("go to https://x.io now"))
    }

    @Test
    fun `reference links resolve`() {
        assertEquals("see docs (https://x.io)", plain("see [docs][d]\n\n[d]: https://x.io"))
    }

    @Test
    fun `an unresolved bracket stays as written`() {
        assertEquals("array[0] is fine", plain("array[0] is fine"))
    }

    @Test
    fun `images become their alt text`() {
        assertEquals("a cat", plain("![a cat](cat.png)"))
    }

    @Test
    fun `quotes and rules`() {
        // Two quoted lines are one paragraph, so they join as they render.
        assertEquals("> quoted still\n\n---\n\nafter", plain("> quoted\n> still\n\n***\n\nafter"))
        assertEquals("> one\n>\n> two", plain("> one\n>\n> two"))
    }

    @Test
    fun `tables become pipe-joined rows`() {
        assertEquals(
            "Name | Value\na | 1\nb | 2",
            plain(
                """
                | Name | Value |
                |------|-------|
                | a    | 1     |
                | b    | **2** |
                """,
            ),
        )
    }

    @Test
    fun `escapes and entities are decoded`() {
        assertEquals("*not bold* & <tag>", plain("\\*not bold\\* &amp; &lt;tag&gt;"))
    }

    @Test
    fun `a typical reply`() {
        assertEquals(
            """
            Summary

            I changed two files:

            - PlainText.kt: the converter
            - EventItems.kt: wires it up
              1. assistant rows
              2. user bubbles

            Run it with:

            ./gradlew test

            That's it. See PR #12 (https://github.com/o/r/pull/12).
            """.trimIndent(),
            plain(
                """
                ### Summary

                I changed **two** files:

                - `PlainText.kt`: the converter
                - `EventItems.kt`: wires it up
                  1. assistant rows
                  2. user bubbles

                Run it with:

                ```bash
                ./gradlew test
                ```

                That's it. See [PR #12](https://github.com/o/r/pull/12).
                """,
            ),
        )
    }
}

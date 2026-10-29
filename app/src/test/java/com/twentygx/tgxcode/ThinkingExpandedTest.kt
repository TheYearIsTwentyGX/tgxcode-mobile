package com.twentygx.tgxcode

import com.twentygx.tgxcode.data.settings.DEFAULT_THINKING_EXPANDED
import com.twentygx.tgxcode.data.settings.thinkingExpandedOr
import com.twentygx.tgxcode.ui.conversation.thinkingOpen
import com.twentygx.tgxcode.ui.settings.SettingsUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingExpandedTest {

    @Test
    fun `expand thinking is on until the user turns it off`() {
        assertTrue(DEFAULT_THINKING_EXPANDED)
        assertTrue(thinkingExpandedOr(null))
        assertTrue(SettingsUiState().thinkingExpanded)
        assertFalse(thinkingExpandedOr(false))
    }

    @Test
    fun `a tapped block goes the other way from the setting`() {
        assertTrue(thinkingOpen(expandByDefault = true, toggled = false))
        assertFalse(thinkingOpen(expandByDefault = true, toggled = true))
        assertFalse(thinkingOpen(expandByDefault = false, toggled = false))
        assertTrue(thinkingOpen(expandByDefault = false, toggled = true))
    }
}

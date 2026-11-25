package com.tamimarafat.ferngeist.feature.chat.ui.composer.model

import com.tamimarafat.ferngeist.core.model.ChatConfigChoice
import com.tamimarafat.ferngeist.core.model.ChatConfigChoiceGroup
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelMenuSectionsTest {
    private fun choice(value: String) = ChatConfigChoice(id = value, label = value.uppercase(), value = value)

    private val anthropic = ChatConfigChoiceGroup("anthropic", "Anthropic", (1..6).map { choice("claude-$it") })
    private val openai = ChatConfigChoiceGroup("openai", "OpenAI", (1..6).map { choice("gpt-$it") })
    private val models = ChatConfigOption.Select(id = "model", name = "Model", groups = listOf(anthropic, openai))

    @Test
    fun blankQuery_listsStillOfferedRecentsFirstInRecencyOrder() {
        val sections = models.modelMenuSections(query = " ", recentValues = listOf("gpt-2", "retired", "claude-1"))

        assertEquals(listOf("gpt-2", "claude-1"), sections.recent.map { it.value })
        assertEquals(listOf("anthropic", "openai"), sections.groups.map { it.id })
    }

    @Test
    fun query_matchesLabelsCaseInsensitively_dropsRecentsAndGroupsLeftEmpty() {
        val sections = models.modelMenuSections(query = "GPT-3", recentValues = listOf("claude-1"))

        assertEquals(emptyList<ChatConfigChoice>(), sections.recent)
        assertEquals(listOf("openai"), sections.groups.map { it.id })
        assertEquals(
            listOf("gpt-3"),
            sections.groups
                .single()
                .choices
                .map { it.value },
        )
    }

    @Test
    fun shortUngroupedList_skipsRecentsAndListsEveryChoiceInOneGroup() {
        val few = ChatConfigOption.Select(id = "model", name = "Model", choices = listOf(choice("a"), choice("b")))

        val sections = few.modelMenuSections(query = "", recentValues = listOf("b"))

        assertEquals(emptyList<ChatConfigChoice>(), sections.recent)
        assertEquals(
            listOf("a", "b"),
            sections.groups
                .single()
                .choices
                .map { it.value },
        )
    }
}

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
    fun query_matchesEveryWordAcrossProviderAndPunctuation() {
        val sections = models.modelMenuSections(query = "openai gpt3", recentValues = emptyList())

        assertEquals(listOf("gpt-3"), sections.groups.flatMap { group -> group.choices.map { it.value } })
    }

    @Test
    fun query_ranksLabelPrefixThenWordPrefixThenAnywhere_andLeadsWithTheBestGroup() {
        fun named(label: String) = ChatConfigChoice(id = label, label = label, value = label)
        val mixed =
            ChatConfigOption.Select(
                id = "model",
                name = "Model",
                groups =
                    listOf(
                        ChatConfigChoiceGroup("other", "Other", listOf(named("Turbomini"))),
                        ChatConfigChoiceGroup(
                            "openai",
                            "OpenAI",
                            listOf(named("Turbomini 2"), named("GPT Mini"), named("Mini")),
                        ),
                    ),
            )

        val sections = mixed.modelMenuSections(query = "mini", recentValues = emptyList())

        assertEquals(listOf("openai", "other"), sections.groups.map { it.id })
        assertEquals(
            listOf("Mini", "GPT Mini", "Turbomini 2"),
            sections.groups
                .first()
                .choices
                .map { it.value },
        )
    }

    private fun flat(vararg labels: String) =
        ChatConfigOption.Select(
            id = "model",
            name = "Model",
            choices = labels.map { ChatConfigChoice(id = it, label = it, value = it) },
        )

    private fun ModelMenuSections.values() = groups.flatMap { group -> group.choices.map { it.value } }

    @Test
    fun query_fuzzyMatchesLettersInOrder_belowExactHits() {
        val models = flat("DeepSeek V4.1 Pro", "DeepSeek V4.1 Flash", "DSV4F Lab")

        val sections = models.modelMenuSections(query = "dsv4f", recentValues = emptyList())

        assertEquals(listOf("DSV4F Lab", "DeepSeek V4.1 Flash"), sections.values())
    }

    @Test
    fun query_fuzzyMatchMustStartAtAWord() {
        val sections = flat("DeepSeek V4.1 Flash").modelMenuSections(query = "eekf", recentValues = emptyList())

        assertEquals(emptyList<String>(), sections.values())
    }

    @Test
    fun shortUngroupedList_showsRecentPicksAndListsEveryChoiceInOneGroup() {
        val few = ChatConfigOption.Select(id = "model", name = "Model", choices = listOf(choice("a"), choice("b")))

        val sections = few.modelMenuSections(query = "", recentValues = listOf("b"))

        assertEquals(listOf("b"), sections.recent.map { it.value })
        assertEquals(
            listOf("a", "b"),
            sections.groups
                .single()
                .choices
                .map { it.value },
        )
    }
}

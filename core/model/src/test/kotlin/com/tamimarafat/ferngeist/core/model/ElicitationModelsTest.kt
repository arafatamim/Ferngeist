package com.tamimarafat.ferngeist.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElicitationModelsTest {
    private fun textField(required: Boolean = true) =
        ChatElicitationField(
            key = "name",
            title = "Name",
            required = required,
            kind = ElicitationFieldKind.Text(minLength = 2, maxLength = 8),
        )

    @Test
    fun requiredTextRejectsBlankAndOutOfRange() {
        val field = textField()
        assertFalse(isElicitationValueValid(field, null))
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.TextValue("")))
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.TextValue("x")))
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.TextValue("toolongname")))
        assertTrue(isElicitationValueValid(field, ChatElicitationValue.TextValue("ada")))
    }

    @Test
    fun optionalTextAcceptsNullButValidatesLengthWhenPresent() {
        val field = textField(required = false)
        assertTrue(isElicitationValueValid(field, null))
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.TextValue("x")))
        assertTrue(isElicitationValueValid(field, ChatElicitationValue.TextValue("ada")))
    }

    @Test
    fun patternConstrainedTextRejectsMismatch() {
        val field =
            ChatElicitationField(
                key = "code",
                required = true,
                kind = ElicitationFieldKind.Text(pattern = "[a-z]+"),
            )
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.TextValue("ABC")))
        assertTrue(isElicitationValueValid(field, ChatElicitationValue.TextValue("abc")))
    }

    @Test
    fun unparseablePatternFailsOpen() {
        val field =
            ChatElicitationField(
                key = "code",
                required = true,
                kind = ElicitationFieldKind.Text(pattern = "([a-z"),
            )
        assertTrue(isElicitationValueValid(field, ChatElicitationValue.TextValue("anything")))
    }

    @Test
    fun singleSelectAcceptsOnlyListedOptions() {
        val field =
            ChatElicitationField(
                key = "strategy",
                required = true,
                kind =
                    ElicitationFieldKind.SingleSelect(
                        options = listOf(ElicitationOption("a"), ElicitationOption("b")),
                    ),
            )
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.TextValue("c")))
        assertTrue(isElicitationValueValid(field, ChatElicitationValue.TextValue("a")))
    }

    @Test
    fun integerFieldEnforcesRange() {
        val field =
            ChatElicitationField(
                key = "count",
                required = true,
                kind = ElicitationFieldKind.IntegerField(minimum = 1, maximum = 5),
            )
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.IntegerValue(0)))
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.IntegerValue(6)))
        assertTrue(isElicitationValueValid(field, ChatElicitationValue.IntegerValue(3)))
    }

    @Test
    fun multiSelectEnforcesMembershipAndCount() {
        val field =
            ChatElicitationField(
                key = "tags",
                required = true,
                kind =
                    ElicitationFieldKind.MultiSelect(
                        options = listOf(ElicitationOption("a"), ElicitationOption("b")),
                        minItems = 1,
                        maxItems = 2,
                    ),
            )
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.StringListValue(emptyList())))
        assertFalse(isElicitationValueValid(field, ChatElicitationValue.StringListValue(listOf("z"))))
        assertTrue(isElicitationValueValid(field, ChatElicitationValue.StringListValue(listOf("a", "b"))))
    }

    @Test
    fun defaultValuesPrefillFromSchema() {
        val request =
            ChatElicitationRequest.Form(
                key = "k",
                message = "m",
                sessionId = "s",
                fields =
                    listOf(
                        ChatElicitationField("a", required = false, kind = ElicitationFieldKind.Text(default = "hi")),
                        ChatElicitationField(
                            "b",
                            required = false,
                            kind = ElicitationFieldKind.BooleanField(default = true),
                        ),
                        ChatElicitationField("c", required = false, kind = ElicitationFieldKind.Text()),
                    ),
            )
        assertEquals(
            mapOf(
                "a" to ChatElicitationValue.TextValue("hi"),
                "b" to ChatElicitationValue.BooleanValue(true),
            ),
            request.defaultValues(),
        )
    }
}

package com.tamimarafat.ferngeist.core.model

/**
 * Structured input request from an agent (ACP `elicitation/create`, form or URL mode).
 *
 * Session-scoped only: request-scoped elicitations (outside any session, e.g. auth phase)
 * are declined at the bridge and never reach the UI.
 */
sealed interface ChatElicitationRequest {
    val key: String
    val message: String
    val sessionId: String
    val toolCallId: String?

    data class Form(
        override val key: String,
        override val message: String,
        override val sessionId: String,
        override val toolCallId: String? = null,
        val title: String? = null,
        val description: String? = null,
        val fields: List<ChatElicitationField> = emptyList(),
    ) : ChatElicitationRequest

    data class Url(
        override val key: String,
        override val message: String,
        override val sessionId: String,
        override val toolCallId: String? = null,
        val elicitationId: String,
        val url: String,
    ) : ChatElicitationRequest
}

data class ChatElicitationField(
    val key: String,
    val title: String? = null,
    val description: String? = null,
    val required: Boolean = false,
    val kind: ElicitationFieldKind = ElicitationFieldKind.Text(),
)

/** Display label: schema title, falling back to the raw property key. */
val ChatElicitationField.label: String get() = title?.takeIf { it.isNotBlank() } ?: key

sealed interface ElicitationFieldKind {
    data class Text(
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val pattern: String? = null,
        val default: String? = null,
    ) : ElicitationFieldKind

    data class SingleSelect(
        val options: List<ElicitationOption> = emptyList(),
        val default: String? = null,
    ) : ElicitationFieldKind

    data class BooleanField(
        val default: Boolean? = null,
    ) : ElicitationFieldKind

    data class IntegerField(
        val minimum: Long? = null,
        val maximum: Long? = null,
        val default: Long? = null,
    ) : ElicitationFieldKind

    data class NumberField(
        val minimum: Double? = null,
        val maximum: Double? = null,
        val default: Double? = null,
    ) : ElicitationFieldKind

    data class MultiSelect(
        val options: List<ElicitationOption> = emptyList(),
        val default: List<String> = emptyList(),
        val minItems: Int? = null,
        val maxItems: Int? = null,
    ) : ElicitationFieldKind
}

data class ElicitationOption(
    val value: String,
    val title: String? = null,
    val description: String? = null,
)

val ElicitationOption.label: String get() = title?.takeIf { it.isNotBlank() } ?: value

sealed interface ChatElicitationValue {
    data class TextValue(
        val value: String,
    ) : ChatElicitationValue

    data class BooleanValue(
        val value: Boolean,
    ) : ChatElicitationValue

    data class IntegerValue(
        val value: Long,
    ) : ChatElicitationValue

    data class NumberValue(
        val value: Double,
    ) : ChatElicitationValue

    data class StringListValue(
        val values: List<String>,
    ) : ChatElicitationValue
}

/** Default values from the schema, for pre-populating the form. */
fun ChatElicitationRequest.Form.defaultValues(): Map<String, ChatElicitationValue> {
    val entries =
        fields.mapNotNull { field ->
            val default =
                when (val kind = field.kind) {
                    is ElicitationFieldKind.Text -> kind.default?.let { ChatElicitationValue.TextValue(it) }
                    is ElicitationFieldKind.SingleSelect -> kind.default?.let { ChatElicitationValue.TextValue(it) }
                    is ElicitationFieldKind.BooleanField -> kind.default?.let { ChatElicitationValue.BooleanValue(it) }
                    is ElicitationFieldKind.IntegerField -> kind.default?.let { ChatElicitationValue.IntegerValue(it) }
                    is ElicitationFieldKind.NumberField -> kind.default?.let { ChatElicitationValue.NumberValue(it) }
                    is ElicitationFieldKind.MultiSelect ->
                        kind.default.takeIf { it.isNotEmpty() }?.let { ChatElicitationValue.StringListValue(it) }
                }
            default?.let { field.key to it }
        }
    return entries.toMap()
}

/**
 * True when [value] satisfies [field] (presence for required fields, plus length,
 * pattern, range, item-count and option-membership checks). Null value is valid
 * only for optional fields.
 */
fun isElicitationValueValid(
    field: ChatElicitationField,
    value: ChatElicitationValue?,
): Boolean {
    if (value == null) return !field.required
    return when (val kind = field.kind) {
        is ElicitationFieldKind.Text -> isTextValid(field, kind, value)
        is ElicitationFieldKind.SingleSelect -> isSingleSelectValid(field, kind, value)
        is ElicitationFieldKind.BooleanField -> value is ChatElicitationValue.BooleanValue
        is ElicitationFieldKind.IntegerField -> isIntegerValid(kind, value)
        is ElicitationFieldKind.NumberField -> isNumberValid(kind, value)
        is ElicitationFieldKind.MultiSelect -> isMultiSelectValid(field, kind, value)
    }
}

private fun isTextValid(
    field: ChatElicitationField,
    kind: ElicitationFieldKind.Text,
    value: ChatElicitationValue,
): Boolean =
    isTextFieldValid(field, value) { text ->
        sizeWithin(text.length, kind.minLength, kind.maxLength) && matchesPattern(text, kind.pattern)
    }

private fun isSingleSelectValid(
    field: ChatElicitationField,
    kind: ElicitationFieldKind.SingleSelect,
    value: ChatElicitationValue,
): Boolean =
    isTextFieldValid(field, value) { text ->
        kind.options.isEmpty() || kind.options.any { it.value == text }
    }

/** A text answer: blank is rejected when required, empty is accepted otherwise, anything else is [rule]. */
private fun isTextFieldValid(
    field: ChatElicitationField,
    value: ChatElicitationValue,
    rule: (String) -> Boolean,
): Boolean {
    val text = (value as? ChatElicitationValue.TextValue)?.value ?: return false
    return when {
        field.required && text.isBlank() -> false
        text.isEmpty() -> true
        else -> rule(text)
    }
}

private fun isIntegerValid(
    kind: ElicitationFieldKind.IntegerField,
    value: ChatElicitationValue,
): Boolean {
    val number = (value as? ChatElicitationValue.IntegerValue)?.value ?: return false
    return kind.minimum?.let { number < it } != true && kind.maximum?.let { number > it } != true
}

// Comparisons stay on Double's primitive operators: a generic compareTo orders NaN differently.
private fun isNumberValid(
    kind: ElicitationFieldKind.NumberField,
    value: ChatElicitationValue,
): Boolean {
    val number = (value as? ChatElicitationValue.NumberValue)?.value ?: return false
    return kind.minimum?.let { number < it } != true && kind.maximum?.let { number > it } != true
}

private fun isMultiSelectValid(
    field: ChatElicitationField,
    kind: ElicitationFieldKind.MultiSelect,
    value: ChatElicitationValue,
): Boolean {
    val items = (value as? ChatElicitationValue.StringListValue)?.values ?: return false
    return when {
        field.required && items.isEmpty() -> false
        else ->
            sizeWithin(items.size, kind.minItems, kind.maxItems) &&
                (kind.options.isEmpty() || items.all { item -> kind.options.any { it.value == item } })
    }
}

private fun sizeWithin(
    size: Int,
    min: Int?,
    max: Int?,
): Boolean = size >= (min ?: 0) && size <= (max ?: Int.MAX_VALUE)

private fun matchesPattern(
    text: String,
    pattern: String?,
): Boolean =
    // Fail open on unparseable patterns: a buggy agent schema must not
    // trap the user with a Submit that can never enable.
    pattern == null || runCatching { text.matches(Regex(pattern)) }.getOrDefault(true)

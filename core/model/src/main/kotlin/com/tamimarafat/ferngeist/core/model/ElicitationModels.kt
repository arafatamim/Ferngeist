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
) {
    /** Display label: schema title, falling back to the raw property key. */
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: key
}

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
) {
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: value
}

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
@Suppress("CyclomaticComplexMethod", "ReturnCount")
fun isElicitationValueValid(
    field: ChatElicitationField,
    value: ChatElicitationValue?,
): Boolean {
    if (value == null) return !field.required
    return when (val kind = field.kind) {
        is ElicitationFieldKind.Text -> {
            val text = (value as? ChatElicitationValue.TextValue)?.value ?: return false
            if (field.required && text.isBlank()) return false
            if (text.isEmpty()) return true
            kind.minLength?.let { if (text.length < it) return false }
            kind.maxLength?.let { if (text.length > it) return false }
            val pattern = kind.pattern
            if (pattern != null) {
                // Fail open on unparseable patterns: a buggy agent schema must not
                // trap the user with a Submit that can never enable.
                val matches = runCatching { text.matches(Regex(pattern)) }.getOrDefault(true)
                if (!matches) return false
            }
            true
        }
        is ElicitationFieldKind.SingleSelect -> {
            val text = (value as? ChatElicitationValue.TextValue)?.value ?: return false
            if (field.required && text.isBlank()) return false
            if (text.isEmpty()) return true
            kind.options.isEmpty() || kind.options.any { it.value == text }
        }
        is ElicitationFieldKind.BooleanField -> value is ChatElicitationValue.BooleanValue
        is ElicitationFieldKind.IntegerField -> {
            val number = (value as? ChatElicitationValue.IntegerValue)?.value ?: return false
            kind.minimum?.let { if (number < it) return false }
            kind.maximum?.let { if (number > it) return false }
            true
        }
        is ElicitationFieldKind.NumberField -> {
            val number = (value as? ChatElicitationValue.NumberValue)?.value ?: return false
            kind.minimum?.let { if (number < it) return false }
            kind.maximum?.let { if (number > it) return false }
            true
        }
        is ElicitationFieldKind.MultiSelect -> {
            val items = (value as? ChatElicitationValue.StringListValue)?.values ?: return false
            if (field.required && items.isEmpty()) return false
            kind.minItems?.let { if (items.size < it) return false }
            kind.maxItems?.let { if (items.size > it) return false }
            kind.options.isEmpty() || items.all { item -> kind.options.any { it.value == item } }
        }
    }
}

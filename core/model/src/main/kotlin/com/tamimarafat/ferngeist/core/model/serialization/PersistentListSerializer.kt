package com.tamimarafat.ferngeist.core.model.serialization

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes a [PersistentList] as a plain list; the format has no persistent form. */
class PersistentListSerializer<T>(
    element: KSerializer<T>,
) : KSerializer<PersistentList<T>> {
    private val list = ListSerializer(element)
    override val descriptor = list.descriptor

    override fun serialize(
        encoder: Encoder,
        value: PersistentList<T>,
    ) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): PersistentList<T> = list.deserialize(decoder).toPersistentList()
}

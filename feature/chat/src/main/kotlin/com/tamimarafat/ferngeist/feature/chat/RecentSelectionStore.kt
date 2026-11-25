package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.ChatConfigValue

typealias RecentSelectionStore = com.tamimarafat.ferngeist.core.model.store.RecentSelectionStore

/**
 * Where one config option's recent picks live. The trailing-colon server segment is what lets
 * deleting a server clear them with `clearByPrefix("config_option:$serverId:")`.
 */
internal fun configOptionRecentsKey(
    serverId: String,
    optionId: String,
): String = "config_option:$serverId:$optionId"

/** Every select pick, from any picker, feeds that option's recents; toggles have none. */
internal suspend fun RecentSelectionStore.recordConfigPick(
    serverId: String,
    optionId: String,
    value: ChatConfigValue,
) {
    if (value is ChatConfigValue.StringValue) addSelection(configOptionRecentsKey(serverId, optionId), value.value)
}

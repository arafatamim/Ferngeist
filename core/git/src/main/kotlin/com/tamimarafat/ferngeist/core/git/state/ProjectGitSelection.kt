package com.tamimarafat.ferngeist.core.git.state

import com.tamimarafat.ferngeist.core.model.git.ProjectGitReaderFactory
import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import kotlinx.coroutines.CoroutineScope

/** Replacing the checkout retires its entire state owner, so late replies stay with the old owner. */
class ProjectGitSelection(
    private val scope: CoroutineScope,
    private val factory: ProjectGitReaderFactory,
) {
    private var target: ProjectGitTarget? = null
    private var controller: ProjectGitController? = null

    fun select(selected: ProjectGitTarget?): ProjectGitController? {
        if (selected == target) return controller
        target = selected
        controller = selected?.let { ProjectGitController(scope, factory.create(it)) }
        return controller
    }
}

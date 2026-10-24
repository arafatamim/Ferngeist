package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import kotlin.random.Random

private val mOODS =
    (
        "brave calm clever cosy eager gentle happy jolly keen kind lively lucky merry mellow " +
            "nimble plucky proud quiet quick sleepy snappy sunny swift tidy witty zesty"
    ).split(' ')

private val cOLOURS =
    (
        "amber azure coral crimson golden hazel indigo ivory jade lilac maroon mint ochre olive " +
            "peach plum rose ruby russet sage silver teal violet"
    ).split(' ')

private val cREATURES =
    (
        "badger beetle crane dingo falcon ferret finch gecko heron ibis koala lemur lynx marten " +
            "moth newt otter owl panda puffin quokka raven robin salmon stoat tapir walrus wombat " +
            "wren yak"
    ).split(' ')

/**
 * A friendly branch name for a new worktree, like `ferngeist/quiet-amber-otter`, so the form shows one
 * before the chat opens instead of leaving the field blank. About 18k combinations: a clash is
 * rare, the gateway reports it inline, and shuffle draws another.
 */
internal fun newWorktreeBranchName(random: Random = Random.Default): String =
    "ferngeist/" + listOf(mOODS, cOLOURS, cREATURES).joinToString("-") { it.random(random) }

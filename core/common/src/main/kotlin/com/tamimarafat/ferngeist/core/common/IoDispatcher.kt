package com.tamimarafat.ferngeist.core.common

import javax.inject.Qualifier

/** The dispatcher for blocking I/O; tests swap in their scheduler so `advanceUntilIdle` can wait on it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

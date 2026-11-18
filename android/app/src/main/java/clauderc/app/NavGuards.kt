package clauderc.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

/**
 * Opens [route] only while the page asking for it, [from], is resumed. A page stops being resumed
 * as soon as the next one starts sliding in, so a second tap during the slide is dropped instead
 * of stacking a second page.
 */
fun NavHostController.navigateFrom(from: LifecycleOwner, route: Any) {
    if (from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) navigate(route)
}

/**
 * Leaves [entry] when it is still the current page and has a page under it. Back may come while
 * the page is still sliding in, so it is not dropped; a second tap finds another page current
 * and does nothing, and the first page is never popped.
 */
fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id && previousBackStackEntry != null) popBackStack()
}

// What the sessions page does on each failure. The page shows which exception
// maps to which action, not the actions themselves.
fun promptReauth() { }
fun retryAfter(seconds: Long?) { }
fun showOffline() { }
fun showUnsupportedNumber(message: String?) { }
fun showSameCountryOnly(message: String?) { }

/**
 * Shown when the workspace is at its proxy number limit. Not a retry: the
 * condition clears when a session ends or when more numbers are approved.
 */
fun showAtNumberLimit(detail: String?) {
    println(detail ?: "At the number limit.")
}

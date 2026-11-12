package com.moonetix.offerwall

/**
 * Receives offerwall lifecycle events. All callbacks run on the main thread.
 *
 * Every method has an empty default implementation, so you only override what you need
 * (this also applies to Java, the methods are compiled as Java default methods).
 *
 * The listener is held in a static field by [Moonetix.setListener]. If it references an
 * Activity, call `Moonetix.setListener(null)` in that Activity's `onDestroy()` to avoid
 * leaking it, or use a listener that only references application-scoped objects.
 *
 * These events are for UI purposes only. Never credit rewards from them: rewards must be
 * credited only from the signed server-to-server postback.
 */
interface MoonetixListener {

    /** The offerwall finished loading its first page. */
    fun onOpened() {}

    /** The offerwall screen was closed (close button, back, or finished by the system). Fired once per [Moonetix.open]. */
    fun onClosed() {}

    /** Something went wrong. See [MoonetixError.code]. The SDK never throws for these cases. */
    fun onError(error: MoonetixError) {}
}

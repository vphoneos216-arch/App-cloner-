package com.akash.appcloner.engine.perf

/**
 * A value that was measured, or an explicit statement that it could not be.
 *
 * The whole perf layer is typed in terms of this rather than in plain numbers. A dashboard field
 * that cannot be read on this device shows why, and there is no code path that can substitute a
 * plausible-looking number for a missing measurement.
 */
sealed interface Metric<out T> {

    /** [source] names where the value came from, so a reading can be traced back. */
    data class Value<T>(val value: T, val source: String) : Metric<T>

    /** [reason] is shown to the operator verbatim. [remedy] is how to make it measurable. */
    data class Unavailable(val reason: String, val remedy: String? = null) : Metric<Nothing>

    companion object {
        /** Needs a privileged shell. The single most common reason on a stock device. */
        fun needsRoot(what: String) = Unavailable(
            reason = "$what cannot be read by an ordinary app on this Android release.",
            remedy = "Grant root, or run the collector from an adb shell.",
        )
    }
}

fun <T> Metric<T>.valueOrNull(): T? = (this as? Metric.Value<T>)?.value

inline fun <T, R> Metric<T>.map(transform: (T) -> R): Metric<R> = when (this) {
    is Metric.Value -> Metric.Value(transform(value), source)
    is Metric.Unavailable -> this
}

package dev.nish.nessa.whisker

/** Everything that can go wrong talking to Whisker. Messages never contain credentials. */
sealed class WhiskerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The email, password or refresh token was rejected. */
    class BadCredentials : WhiskerException("Whisker rejected the sign-in")

    /** Whisker could not be reached at all. */
    class Network(cause: Throwable) : WhiskerException("Could not reach Whisker", cause)

    /** Whisker answered with an error we don't specifically handle. */
    class Server(val code: Int, val type: String?) : WhiskerException("Whisker error $code${type?.let { " ($it)" } ?: ""}")

    /** The saved sign-in no longer works and the owner has to sign in again. */
    class NeedsReconnect : WhiskerException("Whisker sign-in is needed again")
}

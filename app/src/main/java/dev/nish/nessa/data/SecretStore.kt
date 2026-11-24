package dev.nish.nessa.data

/** The owner's Whisker sign-in, kept so Nessa can renew its session without asking again. */
data class Secrets(val email: String, val password: String, val refreshToken: String) {
    override fun toString() = "Secrets(redacted)"
}

interface SecretStore {
    suspend fun load(): Secrets?
    suspend fun save(s: Secrets)
    suspend fun clear()
}

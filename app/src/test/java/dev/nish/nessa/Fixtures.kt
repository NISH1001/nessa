package dev.nish.nessa

object Fixtures {
    fun text(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }
}

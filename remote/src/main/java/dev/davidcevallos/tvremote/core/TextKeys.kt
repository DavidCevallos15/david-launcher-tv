package dev.davidcevallos.tvremote.core

import java.text.Normalizer

/**
 * Plan B para escribir: texto → teclas Android, una por carácter.
 * Funciona con cualquier teclado en pantalla del TV aunque el TV no anuncie su campo de texto
 * por el protocolo IME (algunos TCL no lo hacen). Limitación: sin tildes ni ñ (á→a, ñ→n),
 * porque Android TV no tiene keycodes para ellas.
 */
object TextKeys {

    private const val A = 29
    private const val ZERO = 7

    private val SYMBOLS = mapOf(
        ' ' to 62,  // SPACE
        '.' to 56,  // PERIOD
        ',' to 55,  // COMMA
        '-' to 69,  // MINUS
        '=' to 70,  // EQUALS
        '/' to 76,  // SLASH
        '@' to 77,  // AT
        '\'' to 75, // APOSTROPHE
        ';' to 74,  // SEMICOLON
        '+' to 81,  // PLUS
        '#' to 18,  // POUND
        '*' to 17,  // STAR
        '\n' to Keys.ENTER,
    )

    fun toKeyCodes(text: String): List<Int> {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase().mapNotNull { c ->
            when (c) {
                in 'a'..'z' -> A + (c - 'a')
                in '0'..'9' -> ZERO + (c - '0')
                else -> SYMBOLS[c]
            }
        }
    }
}

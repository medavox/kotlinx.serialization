/*
 * Copyright 2017-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.json.internal.lexer

import kotlinx.serialization.json.internal.*

internal class Json5Lexer(source: String): StringJsonLexer(source, allowLeadingPlusSign = true) {

    override fun startString(): Char {
        val source = source
        while (currentPosition != -1 && currentPosition < source.length) {
            val c = source[currentPosition++]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue
            if (c != STRING && c != STRING_SQUOTE) {
                val s = if (currentPosition == source.length || currentPosition < 0) "EOF" else source[currentPosition].toString()
                fail("Expected start of the string: \" or ', but had '$s' instead", false)
            }
            return c
        }
        currentPosition = -1 // for correct EOF reporting
        fail("Expected start of the string: \" or ', but had EOF instead", false) // EOF
    }

    override fun consumeKeyString(): String {
        return consumeUnquotedString()
    }

    override fun peekString(isLenient: Boolean): String? {
        skipWhitespaces()
        val cur = source[currentPosition]
        if (cur != STRING && cur != STRING_SQUOTE && charToTokenClass(cur) != TC_OTHER) {
            // not a string or unquoted string
            return null
        }
        // we use peek() for both keys and values.
        val string = consumeUnquotedString()
        peekedString = string
        return string
    }

    // Should be a basic building block for Json5
    // (while the basic building block for Json is consumeKeyString)
    // because we can express unquoted strings via parser for quoted.
    override fun consumeValueString(): String {
        if (peekedString != null) {
            return takePeeked()
        }

        return consumeQuotedStringBase()
    }

    override fun consumeUnquotedString(): String {
        if (peekedString != null) {
            return takePeeked()
        }
        var current = skipWhitespaces()
        if (current >= source.length || current == -1) fail("EOF", current)
        val c = source[current]
        if (c == STRING || c == STRING_SQUOTE) {
            return consumeValueString()
        }
        val token = charToTokenClass(c)
        if (token != TC_OTHER) {
            fail("Expected beginning of the string, but got ${source[current]}")
        }
        var usedAppend = false
        // Todo: unify with consumeStringRest to get rid of copypasta and support escape sequences.
        // However, `while` condition there is completely different, so some performance evaluation is required.
        while (charToTokenClass(source[current]) == TC_OTHER) {
            ++current
            if (current >= source.length) {
                usedAppend = true
                appendRange(currentPosition, current)
                val eof = prefetchOrEof(current)
                if (eof == -1) {
                    // to handle plain lenient strings, such as top-level
                    currentPosition = current
                    return decodedString(0, 0)
                } else {
                    current = eof
                }
            }
        }
        val result = if (!usedAppend) {
            substring(currentPosition, current)
        } else {
            decodedString(currentPosition, current)
        }
        currentPosition = current
        return result
    }
}
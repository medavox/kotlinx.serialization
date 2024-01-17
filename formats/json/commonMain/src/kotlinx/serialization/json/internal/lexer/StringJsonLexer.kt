/*
 * Copyright 2017-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.json.internal

internal open class StringJsonLexer(override val source: String, allowLeadingPlusSign: Boolean = false) :
    AbstractJsonLexer(allowLeadingPlusSign) {

    override fun prefetchOrEof(position: Int): Int = if (position < source.length) position else -1

    override fun consumeNextToken(): Byte {
        val source = source
        while (currentPosition != -1 && currentPosition < source.length) {
            val ch = source[currentPosition++]
            return when (val tc = charToTokenClass(ch)) {
                TC_WHITESPACE -> continue
                else -> tc
            }
        }
        return TC_EOF
    }

    override fun canConsumeValue(): Boolean {
        var current = currentPosition
        if (current == -1) return false
        while (current < source.length) {
            val c = source[current]
            // Inlined skipWhitespaces without field spill and nested loop. Also faster then char2TokenClass
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                ++current
                continue
            }
            currentPosition = current
            return isValidValueStart(c)
        }
        currentPosition = current
        return false
    }

    override fun skipWhitespaces(): Int {
        var current = currentPosition
        if (current == -1) return current
        // Skip whitespaces
        while (current < source.length) {
            val c = source[current]
            // Faster than char2TokenClass actually
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                ++current
            } else {
                break
            }
        }
        currentPosition = current
        return current
    }

    override fun consumeNextToken(expected: Char) {
        if (currentPosition == -1) unexpectedToken(expected)
        val source = source
        while (currentPosition < source.length) {
            val c = source[currentPosition++]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue
            if (c == expected) return
            unexpectedToken(expected)
        }
        currentPosition = -1 // for correct EOF reporting
        unexpectedToken(expected) // EOF
    }

    fun consumeQuotedString(): String {
        /*
         * For strings we assume that escaped symbols are rather an exception, so firstly
         * we optimistically scan for closing quote via intrinsified and blazing-fast 'indexOf',
         * than do our pessimistic check for backslash and fallback to slow-path if necessary.
         */
        val start = startString()
        val current = currentPosition
        val closingQuote = source.indexOf(start, current)
        if (closingQuote == -1) {
            // advance currentPosition to a token after the end of the string to guess position in the error msg
            // (not always correct, as `:`/`,` are valid contents of the string, but good guess anyway)
            consumeUnquotedString()
            unexpectedToken("end of the string: '$start'", wasConsumed = false)
        }
        // Now we _optimistically_ know where the string ends (it might have been an escaped quote)
        for (i in current until closingQuote) {
            // Encountered escape sequence, should fallback to "slow" path and symbolic scanning
            if (source[i] == STRING_ESC) {
                return consumeQuotedStringRest(source, currentPosition, i, start)
            }
        }
        this.currentPosition = closingQuote + 1
        return source.substring(current, closingQuote)
    }

    open fun startString(): Char {
        consumeNextToken(STRING)
        return '"'
    }

    override fun consumeKeyString(): String {
        return consumeQuotedString()
    }

    override fun consumeStringChunked(isLenient: Boolean, consumeChunk: (stringChunk: String) -> Unit) {
        (if (isLenient) consumeUnquotedString() else consumeValueString()).chunked(BATCH_SIZE).forEach(consumeChunk)
    }

    override fun peekLeadingMatchingValue(keyToMatch: String, isLenient: Boolean): String? {
        val positionSnapshot = currentPosition
        try {
            if (consumeNextToken() != TC_BEGIN_OBJ) return null // Malformed JSON, bailout
            val firstKey = peekString(isLenient, isKey = true)
            if (firstKey != keyToMatch) return null
            discardPeeked() // consume firstKey
            if (consumeNextToken() != TC_COLON) return null
            return peekString(isLenient, isKey = false)
        } finally {
            // Restore the position
            currentPosition = positionSnapshot
            discardPeeked()
        }
    }
}


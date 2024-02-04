package com.applepeacock.emoji

import io.ktor.util.*
import kotlin.test.Test
import kotlin.test.assertEquals

internal class AsciiTest: EmojiTestSuite() {
    @Test
    fun testAscii() {
        val smile = emojiManager.asciiToUnicode(":)")
        assertEquals("1F642".getExpectedEmojiVal(), smile)
    }

    @Test
    fun testIndividual() {
        val errors = mutableListOf<String>()
        emojiManager.asciiMap.forEach { (ascii, hex) ->
            val result = emojiManager.asciiToUnicode(ascii)
            if (hex.getExpectedEmojiVal() != result) errors.add("$ascii <> $result")
        }

        assert(errors.isEmpty()) { "Failed to match ${errors.size} ascii to emoji:\n${errors.joinToString("\n")}" }
    }

    @Test
    fun testThreeSpaced() {
        // 10 sets of 3 random ascii values separated by spaces
        val template = "%s %s %s"
        val asciiEntries = emojiManager.asciiMap.entries
        for (i in 1..100) {
            val e1 = asciiEntries.random()
            val e2 = asciiEntries.random()
            val e3 = asciiEntries.random()
            val result = emojiManager.asciiToUnicode(template.format(e1.key, e2.key, e3.key))
            assertEquals(template.formatEmojis(e1.value, e2.value, e3.value), result)
        }
    }

    @Test
    fun testWords() {
        // 10 sets of 3 random ascii values
        val template = "hey, i am words %s words words %s words"
        val asciiEntries = emojiManager.asciiMap.entries
        for (i in 1..100) {
            val e1 = asciiEntries.random()
            val e2 = asciiEntries.random()
            val result = emojiManager.asciiToUnicode(template.format(e1.key, e2.key))
            assertEquals(template.formatEmojis(e1.value, e2.value), result)
        }
    }

    @Test
    fun testEscaped() {
        val errors = mutableListOf<String>()
        emojiManager.asciiMap.forEach { (ascii, hex) ->
            val escapedAscii = ascii.escapeHTML()
            val result = emojiManager.asciiToUnicode(escapedAscii)
            if (result != hex.getExpectedEmojiVal()) errors.add("$escapedAscii <> $result")
        }
        assert(errors.isEmpty()) { "Failed to match ${errors.size} ascii to emoji:\n${errors.joinToString("\n")}" }
    }

    @Test
    fun testWithTags() {
        // 10 sets of 3 random ascii values
        // the penultimate should be IGNORED, since its involved with the innards of an <a>
        val template = "<em>this is important %s %s</em> %s %s <a href=\"http://wowee.com/%s\">wowee %s</a>"
        val asciiEntries = emojiManager.asciiMap.entries
        for (i in 1..100) {
            val e1 = asciiEntries.random()
            val e2 = asciiEntries.random()
            val result = emojiManager.asciiToUnicode(template.format(e1.key, e2.key, e1.key, e2.key, e1.key, e1.key))
            assertEquals(
                template.format(
                    e1.value.getExpectedEmojiVal(),
                    e2.value.getExpectedEmojiVal(),
                    e1.value.getExpectedEmojiVal(),
                    e2.value.getExpectedEmojiVal(),
                    e1.key,
                    e1.value.getExpectedEmojiVal()
                ), result
            )
        }
    }
}
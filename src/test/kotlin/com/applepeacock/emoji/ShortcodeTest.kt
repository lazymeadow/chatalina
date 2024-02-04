package com.applepeacock.emoji

import io.ktor.util.*
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals

class ShortcodeTest {
    private fun String.asShortcode() = ":$this:"
    private fun String.getExpectedEmojiVal() = emojiManager.emojiData.find { it.hexcode == this }?.emoji
    private fun String.formatEmojis(vararg args: String) = this.format(*args.map { it.getExpectedEmojiVal() }.toTypedArray())

    @Test
    fun testShortcode() {
        val joy = emojiManager.shortcodeToUnicode(":joy:")
        assertEquals("1F602".getExpectedEmojiVal(), joy)
    }

    @org.junit.Test
    fun testIndividual() {
        val errors = mutableListOf<String>()
        emojiManager.shortcodeMap.forEach { (hex, shortcodes) ->
            shortcodes.forEach {
                val result = emojiManager.shortcodeToUnicode(":$it:")
                if (hex.getExpectedEmojiVal() != result) errors.add(":$it: <> $result")
            }
        }
        assert(errors.isEmpty()) { "Failed to match ${errors.size} shortcodes to emoji:\n${errors.joinToString("\n")}" }
    }

    @org.junit.Test
    fun testThreeSpaced() {
        // 10 sets of 3 random ascii values separated by spaces
        val template = "%s %s %s"
        val shortcodeEntries = emojiManager.shortcodeMap.entries
        for (i in 1..1000) {
            val e1 = shortcodeEntries.random()
            val e2 = shortcodeEntries.random()
            val e3 = shortcodeEntries.random()
            val result =
                emojiManager.shortcodeToUnicode(
                    template.format(
                        e1.value.random().asShortcode(),
                        e2.value.random().asShortcode(),
                        e3.value.random().asShortcode()
                    )
                )
            assertEquals(template.formatEmojis(e1.key, e2.key, e3.key), result)
        }
    }

    @org.junit.Test
    fun testWords() {
        // 10 sets of 3 random ascii values
        val template = "hey, i am words %s words words %s words"
        val shortcodeEntries = emojiManager.shortcodeMap.entries
        for (i in 1..1000) {
            val e1 = shortcodeEntries.random()
            val e2 = shortcodeEntries.random()
            val result = emojiManager.shortcodeToUnicode(template.format(e1.value.random().asShortcode(), e2.value.random().asShortcode()))
            assertEquals(template.formatEmojis(e1.key, e2.key), result)
        }
    }

    @org.junit.Test
    fun testEscaped() {
        val errors = mutableListOf<String>()
        emojiManager.shortcodeMap.forEach { (hex, codes) ->
            codes.forEach {code ->
                val escapedCode = code.asShortcode().escapeHTML()
                val result = emojiManager.shortcodeToUnicode(escapedCode)
                if (result != hex.getExpectedEmojiVal()) errors.add("$escapedCode <> $result")
            }
        }
        assert(errors.isEmpty()) { "Failed to match ${errors.size} ascii to emoji:\n${errors.joinToString("\n")}" }
    }

    @org.junit.Test
    fun testWithTags() {
        // 10 sets of 3 random ascii values
        // the penultimate should be IGNORED, since its involved with the innards of an <a>
        val template = "<em>this is important %s %s</em> %s %s <a href=\"http://wowee.com/%s\">wowee %s</a>"
        val shortcodeEntries = emojiManager.shortcodeMap.entries
        for (i in 1..1000) {
            val e1 = shortcodeEntries.random()
            val e2 = shortcodeEntries.random()
            val s1 = e1.value.random().asShortcode()
            val s2 = e2.value.random().asShortcode()
            val result = emojiManager.shortcodeToUnicode(template.format(s1, s2, s1, s2, s1, s1))
            assertEquals(
                template.format(
                    e1.key.getExpectedEmojiVal(),
                    e2.key.getExpectedEmojiVal(),
                    e1.key.getExpectedEmojiVal(),
                    e2.key.getExpectedEmojiVal(),
                    s1,
                    e1.key.getExpectedEmojiVal()
                ), result
            )
        }
    }

    companion object {
        val emojiManager = EmojiManager

        @JvmStatic
        @BeforeClass
        fun ready() {
            EmojiManager.configure()
        }
    }
}
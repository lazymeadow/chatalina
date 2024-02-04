package com.applepeacock.emoji

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversionTest:EmojiTestSuite() {
    @Test
    fun testConvert() {
        val result = emojiManager.convertHexToUnicode("1F602")
        assertEquals("\uD83D\uDE02", result)
    }

    @Test
    fun testLibrary() {
        val errors = mutableListOf<String>()
        emojiManager.emojiData.forEachIndexed { index, it ->
            val result = emojiManager.convertHexToUnicode(it.hexcode)
            if (result != it.emoji) {
                // a TON of the emojis in the library are mismatched between the hexcode and the emoji having 1 vs 2 bytes.
                // if the hexcode is missing the second byte (FE0F) then the unicode character won't be the exact same.
                // we just want to know our conversion is working, so add the second byte and see if it matches now.
                // ultimately, the frontend parser will sort out the difference between a 1 and 2 byte emoji.
                val secondTry = emojiManager.convertHexToUnicode(it.hexcode + "-FE0F")
                if (secondTry != it.emoji) {
                    errors.add("${it.hexcode} <> ${it.emoji}")
                }
            }
        }
        assert(errors.isEmpty()) { "Failed to convert ${errors.size} hexcodes to emoji:\n${errors.joinToString("\n")}" }
    }
}
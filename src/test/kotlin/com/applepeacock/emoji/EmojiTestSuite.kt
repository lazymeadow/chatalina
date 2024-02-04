package com.applepeacock.emoji

import org.junit.BeforeClass
import java.io.PrintStream

abstract class EmojiTestSuite {
    val out = PrintStream(System.out, true, "UTF-8")

    internal fun String.getExpectedEmojiVal() = emojiManager.emojiData.find { it.hexcode == this }?.emoji
    internal fun String.formatEmojis(vararg args: String) =
        this.format(*args.map { it.getExpectedEmojiVal() }.toTypedArray())


    companion object {
        val emojiManager = EmojiManager

        @JvmStatic
        @BeforeClass
        fun ready() {
            EmojiManager.configure()
        }
    }
}
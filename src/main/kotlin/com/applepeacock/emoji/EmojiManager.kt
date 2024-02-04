package com.applepeacock.emoji

import com.applepeacock.plugins.defaultMapper
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.server.config.*
import org.slf4j.LoggerFactory
import org.unbescape.html.HtmlEscape

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class EmojiDefinition(
    val emoji: String,
    val hexcode: String,
    val group: String,
    val subgroups: String,
    val annotation: String,
    val tags: String,
    val skintone: String,
    @JsonProperty("skintone_combination") val skintoneCombination: String,
    @JsonProperty("skintone_base_emoji") val skintoneBaseEmoji: String,
    @JsonProperty("skintone_base_hexcode") val skintoneBaseHexcode: String,
    val unicode: Any,
    val order: Int
)

object EmojiManager {
    private val logger = LoggerFactory.getLogger("Emoji")
    internal lateinit var emojiData: List<EmojiDefinition>
    internal lateinit var shortcodeMap: Map<String, List<String>>
    internal lateinit var asciiMap: Map<String, String>

    val curatedEmojis: ArrayList<String> = arrayListOf(
        "😀", "😁", "😂", "😃", "😄", "😅", "😆", "😉", "😊", "😋", "🤤", "😌", "😍", "😎", "😏", "😐", "😑", "😒",
        "😓", "😔", "😕", "😖", "😗", "😘", "😙", "😚", "😛", "😜", "😝", "😞", "😟", "😠", "😡", "😢", "😣", "😤",
        "😥", "😦", "😧", "😨", "😩", "😪", "😫", "😬", "😭", "😮", "😯", "😰", "🤮", "🤢", "😱", "😲", "😳", "😴",
        "😵", "😶", "😷", "🙁", "🙂", "🙃", "🙄", "🤖", "🧠", "🚽", "🚲", "🐢", "🐉", "🐅", "🐄", "🐐", "🐑", "🐏",
        "🐒", "🐈", "🐕", "🐬", "🐳", "🦄", "🦈", "👌", "👍", "👎", "🖕", "🖖", "✌", "🤘", "🤙", "🤚", "🤛", "🤜",
        "🤝", "🤞", "💪", "🚀", "🥓", "🥒", "🥞", "🥔", "🍌", "🍍", "🍣", "🍺", "🍷", "🍸", "🍹", "💩", "🔥", "💨"
    )

    private val ignoredRegexPart =
        """<(?:object|embed|svg|img|div|span|p|a)[^>]*>|<\/(?:object|embed|svg|img|div|span|p|a)>"""
    private val asciiRegexPart =
        """(\A|(?<=[\s|>]))(([0O>']?[:=;B*#8X%]'?-?[\\/\(\)D*#${'$'}|\]\[@o0OXPpbSL])|([\(\[D][-]?[:=])|([oO>-](.|_+)?[oO<-])|(<[\\/]?3))(\Z|(?=[\s|<]))"""
    private val shortcodeRegexPart = """:[-+\w]+:"""
    private val asciiRegex = "$ignoredRegexPart|($asciiRegexPart)".toRegex()
    private val shortcodeRegex = "$ignoredRegexPart|($shortcodeRegexPart)".toRegex()

    fun configure() {
        logger.debug("Initializing Emoji Manager...")
        val emojiDataFile = this::class.java.getResource("/emoji/openmoji-15.0.json")
        emojiData = emojiDataFile?.let { defaultMapper.readValue(it) }
                ?: throw ApplicationConfigurationException("No emoji data files available")
        val shortcodeDataFile = this::class.java.getResource("/emoji/emojibase-15.0-shortcodes.json")
        shortcodeMap = shortcodeDataFile?.let { defaultMapper.readValue(it) }
                ?: throw ApplicationConfigurationException("No shortcode files available")
        val asciiDataFile = this::class.java.getResource("/emoji/ascii-emoji-map.json")
        asciiMap = asciiDataFile?.let { defaultMapper.readValue(it) }
                ?: throw ApplicationConfigurationException("No ascii mapping available")

        logger.debug("Emoji Manager initialized.")
    }

    fun convertEmojis(text: String): String {
        var msg = shortcodeToUnicode(text)
        msg = asciiToUnicode(msg)
        return msg
    }

    internal fun convertHexToUnicode(hex: String): String {
        return hex.split('-').joinToString("") { Character.toString(it.toInt(16)) }
    }

    internal fun shortcodeToUnicode(text: String): String {
        return shortcodeRegex.replace(HtmlEscape.unescapeHtml(text)) { match ->
            shortcodeMap.entries.find { it.value.contains(match.value.trim(':')) }
                ?.let { convertHexToUnicode(it.key) }
                    ?: match.value
        }
    }

    internal fun asciiToUnicode(text: String): String {
        return asciiRegex.replace(HtmlEscape.unescapeHtml(text)) { match ->
            asciiMap[match.value]?.let { convertHexToUnicode(it) } ?: match.value
        }
    }
}

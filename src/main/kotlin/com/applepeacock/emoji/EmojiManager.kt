package com.applepeacock.emoji

import com.applepeacock.database.emojiDbConnection
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.unbescape.html.HtmlEscape

private object EmojiDefinitions : Table("definitions") {
    val emoji = text("emoji")
    val hex = text("hex")
    val annotation = text("annotation")
    val tags = array<String>("tags")
    val baseEmoji = text("base_emoji").nullable()
    val skintones = array<Int>("skintones")

    override val primaryKey = PrimaryKey(emoji)

    object DAO {
        fun search(query: String) = transaction(emojiDbConnection) {
            this.exec(
                """
                        with d as (select e.emoji,
                                          e.hex,
                                          unnest(coalesce(be.tags, e.tags)) tag,
                                          coalesce(s.code, '')              shortcode,
                                          coalesce(a.ascii, '')             emoticon
                                   from emoji.definitions e
                                            left join emoji.definitions be on e.emoji <> be.emoji and e.base_emoji = be.emoji
                                            left join emoji.shortcodes s on e.emoji = s.emoji
                                            left join emoji.emoticons a on e.emoji = a.emoji),
                             q as (select ? t),
                             r as (select d.*,
                                          d.emoji = q.t                                                          e_match,
                                          d.shortcode = q.t                                                      s_match,
                                          d.emoticon = q.t                                                       a_match,
                                          d.tag = q.t                                                            t_match,
                                          d.shortcode <> '' and d.shortcode like concat('%', q.t, '%')           s_partial,
                                          d.shortcode <> '' and d.shortcode like concat(q.t, '%')                s_prefix,
                                          d.shortcode <> '' and plainto_tsquery(q.t) @@ to_tsvector(d.shortcode) s_ts_match,
                                          d.emoticon <> '' and d.emoticon like concat(q.t, '%')                  a_prefix,
                                          d.emoticon <> '' and d.emoticon like concat('%', q.t, '%')             a_partial
                                   from d,
                                        q)
                        select emoji,
                               min(case
                                       when e_match then 1
                                       when a_match then 2
                                       when s_match then 3
                                       when a_partial then 4
                                       when s_prefix then 5
                                       when (s_ts_match and t_match) then 6
                                       when s_ts_match then 7
                                       when t_match then 8
                                       when s_partial then 9
                                       else 0 end) rank
                        from r
                        where e_match or s_match    or a_match or t_match or s_partial or s_prefix or a_partial or s_ts_match
                        group by r.emoji
                        order by rank
                        limit 108
                    """.trimIndent(), listOf(Pair(VarCharColumnType(), query)), StatementType.SELECT
            ) {
                val results = mutableSetOf<String>()
                while (it.next()) {
                    results.add(it.getString("emoji"))
                }
                results.toList()
            } ?: emptyList()
        }

        fun getEmojiFromShortcode(shortcode: String) =
            transaction(emojiDbConnection) { EmojiShortcodes.getEmoji(shortcode) }

        fun getEmojiFromEmoticon(emoticon: String) =
            transaction(emojiDbConnection) { EmojiEmoticons.getEmoji(emoticon) }
    }
}

private object EmojiShortcodes : Table("shortcodes") {
    val code = text("code")
    val emoji = text("emoji")

    override val primaryKey = PrimaryKey(code)

    fun getEmoji(shortcode: String) =
        EmojiShortcodes.select(emoji).where { code eq shortcode }.firstOrNull()?.getOrNull(emoji)
}

private object EmojiEmoticons : Table("emoticons") {
    val ascii = text("ascii")
    val emoji = text("emoji")

    override val primaryKey = PrimaryKey(ascii)

    fun getEmoji(emoticon: String) =
        EmojiEmoticons.select(emoji).where { ascii eq emoticon }.firstOrNull()?.getOrNull(emoji)
}

object EmojiManager {
    private val logger = LoggerFactory.getLogger("Emoji")

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
            EmojiDefinitions.DAO.getEmojiFromShortcode(match.value.trim(':')) ?: match.value
        }
    }

    internal fun asciiToUnicode(text: String): String {
        return asciiRegex.replace(HtmlEscape.unescapeHtml(text)) { match ->
            EmojiDefinitions.DAO.getEmojiFromEmoticon(match.value) ?: match.value
        }
    }

    fun search(query: String): List<String> {
        return EmojiDefinitions.DAO.search(query)
    }
}

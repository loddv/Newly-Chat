package com.newlychat.app.ui.chat

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Thinking(val text: String) : ContentBlock
    data class Code(val language: String, val code: String) : ContentBlock
}

private val THINK_REGEX = Regex("<(think|thinking)>(.*?)(</\\1>|$)", RegexOption.DOT_MATCHES_ALL)
private val FENCE_REGEX = Regex("```([\\w+#.-]*)[ \\t]*\\n?(.*?)(```|$)", RegexOption.DOT_MATCHES_ALL)

/** Splits a message into thinking blocks, fenced code blocks and markdown text. */
fun parseContent(content: String): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    var last = 0
    for (match in THINK_REGEX.findAll(content)) {
        parseFences(content.substring(last, match.range.first), blocks)
        val thought = match.groupValues[2].trim()
        if (thought.isNotEmpty()) blocks += ContentBlock.Thinking(thought)
        last = match.range.last + 1
    }
    parseFences(content.substring(last), blocks)
    return blocks
}

private fun parseFences(text: String, out: MutableList<ContentBlock>) {
    var last = 0
    for (match in FENCE_REGEX.findAll(text)) {
        addText(text.substring(last, match.range.first), out)
        out += ContentBlock.Code(match.groupValues[1].lowercase(), match.groupValues[2].trimEnd('\n'))
        last = match.range.last + 1
    }
    addText(text.substring(last), out)
}

private fun addText(text: String, out: MutableList<ContentBlock>) {
    if (text.isNotBlank()) out += ContentBlock.Text(text.trim('\n'))
}

sealed interface InlineSpan {
    data class Plain(val text: String) : InlineSpan
    data class Bold(val text: String) : InlineSpan
    data class Italic(val text: String) : InlineSpan
    data class Code(val text: String) : InlineSpan
    data class Link(val label: String, val url: String) : InlineSpan
}

private val INLINE_REGEX = Regex(
    "`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|\\*([^*\\s][^*]*?)\\*|(?<![\\w])_([^_\\s][^_]*?)_(?![\\w])"
)

fun parseInline(text: String): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    var last = 0
    for (m in INLINE_REGEX.findAll(text)) {
        if (m.range.first > last) spans += InlineSpan.Plain(text.substring(last, m.range.first))
        val g = m.groupValues
        spans += when {
            g[1].isNotEmpty() -> InlineSpan.Code(g[1])
            g[2].isNotEmpty() -> InlineSpan.Bold(g[2])
            g[3].isNotEmpty() -> InlineSpan.Bold(g[3])
            g[4].isNotEmpty() -> InlineSpan.Link(g[4], g[5])
            g[6].isNotEmpty() -> InlineSpan.Italic(g[6])
            else -> InlineSpan.Italic(g[7])
        }
        last = m.range.last + 1
    }
    if (last < text.length) spans += InlineSpan.Plain(text.substring(last))
    return spans
}

sealed interface TextLine {
    data class Heading(val level: Int, val text: String) : TextLine
    data class Bullet(val text: String, val marker: String) : TextLine
    data class Paragraph(val text: String) : TextLine
    object Blank : TextLine
}

fun parseLines(text: String): List<TextLine> = text.lines().map { raw ->
    val line = raw.trimEnd()
    val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
    val bullet = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)$").find(line)
    when {
        line.isBlank() -> TextLine.Blank
        heading != null -> TextLine.Heading(heading.groupValues[1].length, heading.groupValues[2])
        bullet != null -> TextLine.Bullet(
            bullet.groupValues[2],
            bullet.groupValues[1].let { if (it[0].isDigit()) it else "•" }
        )
        else -> TextLine.Paragraph(line)
    }
}

enum class TokenType { PLAIN, KEYWORD, STRING, COMMENT, NUMBER }

data class Token(val text: String, val type: TokenType)

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do",
    "return", "import", "package", "private", "public", "protected", "override", "null", "true", "false",
    "def", "lambda", "from", "as", "in", "is", "not", "and", "or", "with", "try", "except", "catch",
    "finally", "throw", "throws", "new", "this", "self", "const", "let", "function", "async", "await",
    "static", "void", "int", "string", "bool", "boolean", "float", "double", "struct", "enum", "type",
    "switch", "case", "break", "continue", "yield", "pass", "None", "True", "False", "export", "default"
)

private val TOKEN_REGEX = Regex(
    "(?<comment>//[^\\n]*|#[^\\n]*|/\\*.*?\\*/)|" +
        "(?<string>\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`[^`]*`)|" +
        "(?<number>\\b\\d+(?:\\.\\d+)?\\b)|" +
        "(?<word>\\b[A-Za-z_][A-Za-z0-9_]*\\b)",
    RegexOption.DOT_MATCHES_ALL
)

/** Lightweight, language-agnostic tokenizer used for syntax highlighting. */
fun tokenize(code: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var last = 0
    for (m in TOKEN_REGEX.findAll(code)) {
        val type = when {
            m.groups["comment"] != null -> TokenType.COMMENT
            m.groups["string"] != null -> TokenType.STRING
            m.groups["number"] != null -> TokenType.NUMBER
            m.value in KEYWORDS -> TokenType.KEYWORD
            else -> continue
        }
        if (m.range.first > last) tokens += Token(code.substring(last, m.range.first), TokenType.PLAIN)
        tokens += Token(m.value, type)
        last = m.range.last + 1
    }
    if (last < code.length) tokens += Token(code.substring(last), TokenType.PLAIN)
    return tokens
}

package com.newlychat.app.ui

import com.newlychat.app.ui.chat.ContentBlock
import com.newlychat.app.ui.chat.InlineSpan
import com.newlychat.app.ui.chat.TokenType
import com.newlychat.app.ui.chat.parseContent
import com.newlychat.app.ui.chat.parseInline
import com.newlychat.app.ui.chat.tokenize
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageParserTest {
    @Test
    fun splitsThinkingTextAndCode() {
        val blocks = parseContent("<think>hmm</think>Hello\n```kotlin\nval x = 1\n```\nbye")
        assertEquals(
            listOf(
                ContentBlock.Thinking("hmm"),
                ContentBlock.Text("Hello"),
                ContentBlock.Code("kotlin", "val x = 1"),
                ContentBlock.Text("bye")
            ),
            blocks
        )
    }

    @Test
    fun handlesUnclosedFence() {
        assertEquals(listOf(ContentBlock.Code("", "abc")), parseContent("```\nabc"))
    }

    @Test
    fun parsesInlineMarkdown() {
        assertEquals(
            listOf(
                InlineSpan.Bold("b"), InlineSpan.Plain(" "), InlineSpan.Italic("i"), InlineSpan.Plain(" "),
                InlineSpan.Code("c"), InlineSpan.Plain(" "), InlineSpan.Link("l", "https://x.io")
            ),
            parseInline("**b** *i* `c` [l](https://x.io)")
        )
    }

    @Test
    fun tokenizesKeywordsStringsAndComments() {
        val types = tokenize("val s = \"a\" // c").filter { it.type != TokenType.PLAIN }.map { it.type }
        assertEquals(listOf(TokenType.KEYWORD, TokenType.STRING, TokenType.COMMENT), types)
    }
}

package com.newlychat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MessageContent(content: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val blocks = remember(content) { parseContent(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Text -> MarkdownText(block.text, color)
                is ContentBlock.Thinking -> ThinkingBlock(block.text)
                is ContentBlock.Code -> CodeBlock(block.language, block.code)
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = (if (expanded) "▾ " else "▸ ") + "Pensamento",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(4.dp)
            )
            if (expanded) {
                MarkdownText(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val colors = codeColors()
    val highlighted = remember(code, colors) {
        buildAnnotatedString {
            tokenize(code).forEach { token ->
                val color = when (token.type) {
                    TokenType.KEYWORD -> colors.keyword
                    TokenType.STRING -> colors.string
                    TokenType.COMMENT -> colors.comment
                    TokenType.NUMBER -> colors.number
                    TokenType.PLAIN -> colors.plain
                }
                withStyle(SpanStyle(color = color)) { append(token.text) }
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.background, MaterialTheme.shapes.small)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(language.ifEmpty { "code" }, style = MaterialTheme.typography.labelSmall, color = colors.comment)
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(code))
                copied = true
            }) { Text(if (copied) "Copiado ✓" else "Copiar", color = colors.plain) }
        }
        Text(
            text = highlighted,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        )
    }
}

private class CodeColors(
    val background: Color, val plain: Color, val keyword: Color,
    val string: Color, val comment: Color, val number: Color
)

@Composable
private fun codeColors(): CodeColors {
    val dark = MaterialTheme.colorScheme.background.red < 0.5f
    return if (dark) {
        CodeColors(Color(0xFF111015), Color(0xFFE6E1E6), Color(0xFFCF8EFF), Color(0xFF9CD08F), Color(0xFF8E8A94), Color(0xFFF0B27A))
    } else {
        CodeColors(Color(0xFFF1EDF6), Color(0xFF1C1B1F), Color(0xFF7B2FBE), Color(0xFF2E7D32), Color(0xFF79747E), Color(0xFFB45309))
    }
}

@Composable
private fun MarkdownText(text: String, color: Color = Color.Unspecified) {
    val lines = remember(text) { parseLines(text) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { line ->
            when (line) {
                TextLine.Blank -> Text("", style = MaterialTheme.typography.bodySmall)
                is TextLine.Heading -> InlineText(
                    line.text,
                    when (line.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.copy(fontWeight = FontWeight.Bold),
                    color
                )
                is TextLine.Bullet -> Row {
                    Text("${line.marker} ", style = MaterialTheme.typography.bodyMedium, color = color)
                    InlineText(line.text, MaterialTheme.typography.bodyMedium, color)
                }
                is TextLine.Paragraph -> InlineText(line.text, MaterialTheme.typography.bodyMedium, color)
            }
        }
    }
}

@Composable
private fun InlineText(text: String, style: TextStyle, color: Color) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.outlineVariant
    val annotated = remember(text, linkColor, codeBackground) {
        buildAnnotatedString {
            parseInline(text).forEach { span ->
                when (span) {
                    is InlineSpan.Plain -> append(span.text)
                    is InlineSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                    is InlineSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                    is InlineSpan.Code -> withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                    ) { append(span.text) }
                    is InlineSpan.Link -> {
                        pushStringAnnotation("URL", span.url)
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(span.label)
                        }
                        pop()
                    }
                }
            }
        }
    }
    ClickableText(
        text = annotated,
        style = style.copy(color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                runCatching { uriHandler.openUri(it.item) }
            }
        }
    )
}

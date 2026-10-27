package com.ideaforge.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/*
 * A deliberately small Markdown renderer for deliverables: headings, bullets, task-list
 * checkboxes (display only), numbered lines, fenced code, horizontal rules, **bold**, *italic*
 * and `inline code`. Anything else is shown as plain text, never dropped.
 */

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val indent: Int, val marker: String, val text: String) : MdBlock
    data class Task(val indent: Int, val checked: Boolean, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val TASK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d+[.)])\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

/** Splits Markdown into blocks. Consecutive plain lines are joined into one paragraph. */
internal fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
    }
    var inCode = false
    val code = StringBuilder()
    for (line in source.lines()) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MdBlock.Code(code.toString().trimEnd('\n'))
                code.clear()
                inCode = false
            } else {
                flush()
                inCode = true
            }
            continue
        }
        if (inCode) {
            code.append(line).append('\n')
            continue
        }
        when {
            line.isBlank() -> flush()
            HEADING.matches(line) -> {
                flush()
                val m = HEADING.matchEntire(line)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
            }
            RULE.matches(line) -> { flush(); blocks += MdBlock.Rule }
            TASK.matches(line) -> {
                flush()
                val m = TASK.matchEntire(line)!!
                blocks += MdBlock.Task(m.groupValues[1].length / 2, m.groupValues[2].isNotBlank(), m.groupValues[3])
            }
            BULLET.matches(line) -> {
                flush()
                val m = BULLET.matchEntire(line)!!
                blocks += MdBlock.Bullet(m.groupValues[1].length / 2, "•", m.groupValues[2])
            }
            NUMBERED.matches(line) -> {
                flush()
                val m = NUMBERED.matchEntire(line)!!
                blocks += MdBlock.Bullet(m.groupValues[1].length / 2, m.groupValues[2], m.groupValues[3])
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
    }
    if (inCode) blocks += MdBlock.Code(code.toString().trimEnd('\n')) // unterminated fence: still show it
    flush()
    return blocks
}

/** Applies **bold**, *italic* and `code` spans; unmatched markers stay as literal text. */
internal fun inlineMarkdown(text: String, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && !text[i + 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/** Renders parsed blocks. Wrap in a SelectionContainer to make the text selectable. */
@Composable
internal fun MarkdownContent(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text, codeBg),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp),
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
                    Text(inlineMarkdown(block.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Task -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    // Display only: the file is the source of truth, ticking here would not be saved.
                    Text(if (block.checked) "☑" else "☐", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
                    Text(inlineMarkdown(block.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                }
                is MdBlock.Code -> CodeBlock(block.text)
                is MdBlock.Paragraph -> Text(inlineMarkdown(block.text, codeBg), style = MaterialTheme.typography.bodyLarge)
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

/** Monospace text on a surface; scrolls sideways rather than wrapping so code keeps its shape. */
@Composable
internal fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
        )
    }
}

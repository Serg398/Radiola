package com.example.sdr

import com.example.sdr.ui.decoders.CwTranscript
import org.junit.Assert.*
import org.junit.Test

class CwTranscriptTest {
    @Test fun rollingDecoderHistoryDoesNotReflowCompletedLines() {
        val transcript = CwTranscript()
        val input = (1..300).joinToString(" ") { "CALL$it" }
        var previous = emptyList<CwTranscript.Line>()
        for (end in 1..input.length) {
            val rows = transcript.update(input.substring(0, end).takeLast(512), 40)
            for (line in previous.dropLast(1)) assertEquals(line, rows.first { it.id == line.id })
            assertTrue(rows.all { it.text.length <= 40 })
            previous = rows
        }
        assertTrue(previous.size > 512 / 40)
        assertTrue(previous.last().text.endsWith("CALL300"))
    }
    @Test fun wrapsWordsAndLongGroupsAndClearsHistory() {
        val transcript = CwTranscript()
        assertEquals(listOf("CQ CQ DE", "TEST"), transcript.update("CQ CQ DE TEST", 8).map { it.text })
        assertTrue(transcript.update("", 8).isEmpty())
        assertEquals(listOf("12345678", "90"), transcript.update("1234567890", 8).map { it.text })
    }
}

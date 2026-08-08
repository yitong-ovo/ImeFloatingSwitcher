package ng.yito.imefloatingswitcher.parser

import android.os.SystemClock
import ng.yito.imefloatingswitcher.model.ImeSnapshot
import ng.yito.imefloatingswitcher.model.ImeStatus
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parser for the CRITICAL text dump of InputMethodManagerService.
 *
 * The parser deliberately returns UNKNOWN when the ROM does not expose a
 * recognized visibility field. It never treats a missing field as HIDDEN.
 */
class TextImeDumpParser(private val preferredUserId: Int? = null) {
    private val userMarkerPattern = Pattern.compile(
        "(?i)(?:^|\\b)user\\s*#?\\s*(\\d+)|\\bmUserId\\s*[=:]\\s*(\\d+)"
    )
    private val boolPattern = Pattern.compile(
        "(?im)^\\s*(?:.*?\\b)?(mInputShown|inputShown|mShowRequested|showRequested)\\s*[=:]\\s*(true|false)\\b"
    )
    private val visibilityPattern = Pattern.compile(
        "(?im)^\\s*(?:.*?\\b)?(mImeWindowVis|imeWindowVis)\\s*[=:]\\s*(0[xX][0-9a-fA-F]+|-?\\d+)\\b"
    )
    private val currentImePattern = Pattern.compile(
        "(?im)^\\s*(?:.*?\\b)?(mCurMethodId|mCurId|curMethodId|curId)\\s*[=:]\\s*([^\\s,}]+)"
    )

    fun parse(text: String, elapsedMs: Long = 0L): ImeSnapshot {
        // Unit tests call the parser without an Android clock. In production the
        // UserService supplies elapsedMs, so only use SystemClock for that path.
        val started = if (elapsedMs > 0L) SystemClock.elapsedRealtime() else 0L
        val selected = selectUserSection(text)
        val keywordLines = selected.lineSequence()
            .filter { line -> phase0KeywordPattern.containsMatchIn(line) }
            .take(64)
            .joinToString("\n")
            .take(8 * 1024)
            .takeIf { it.isNotBlank() }
        val shownValues = mutableListOf<Boolean>()
        val requestedValues = mutableListOf<Boolean>()
        val boolMatcher = boolPattern.matcher(selected)
        while (boolMatcher.find()) {
            val value = boolMatcher.group(2)?.toBoolean() ?: continue
            if (boolMatcher.group(1)?.lowercase(Locale.ROOT)?.contains("showrequested") == true) {
                requestedValues += value
            } else {
                shownValues += value
            }
        }

        val visibility = visibilityPattern.matcher(selected).let { matcher ->
            var last: Int? = null
            while (matcher.find()) last = parseInt(matcher.group(2))
            last
        }
        val currentImeId = currentImePattern.matcher(selected).let { matcher ->
            var last: String? = null
            while (matcher.find()) {
                last = matcher.group(2)
                    ?.trim()
                    ?.trimEnd(';', ',', '}', ']')
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() }
            }
            last
        }

        val inputShown = shownValues.lastOrNull()
        val status = when {
            inputShown != null -> if (inputShown) ImeStatus.SHOWN else ImeStatus.HIDDEN
            visibility != null -> if ((visibility and IME_VISIBLE) != 0) ImeStatus.SHOWN else ImeStatus.HIDDEN
            else -> ImeStatus.UNKNOWN
        }

        val conflicts = shownValues.distinct().size > 1
        val parserError = when {
            conflicts -> "CONFLICTING_INPUT_SHOWN"
            status == ImeStatus.UNKNOWN -> "VISIBILITY_FIELDS_MISSING"
            else -> null
        }
        val parserMessage = when (parserError) {
            "CONFLICTING_INPUT_SHOWN" -> "Multiple inputShown values were found; the last value won."
            "VISIBILITY_FIELDS_MISSING" -> "No recognized inputShown or mImeWindowVis field was found."
            else -> null
        }

        return ImeSnapshot(
            status = status,
            inputShown = inputShown,
            imeWindowVisibility = visibility,
            currentImeIdFromDump = currentImeId,
            keywordLines = keywordLines,
            showRequested = requestedValues.lastOrNull(),
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            rawSizeBytes = text.toByteArray(StandardCharsets.UTF_8).size,
            dumpTruncated = false,
            errorCode = parserError,
            errorMessage = parserMessage,
            timestampElapsedRealtime = if (started > 0L) SystemClock.elapsedRealtime() else 0L
        )
    }

    private fun selectUserSection(text: String): String {
        val preferred = preferredUserId ?: return text
        val lines = text.lines()
        val sections = mutableListOf<Pair<Int, MutableList<String>>>()
        var current: Pair<Int, MutableList<String>>? = null
        for (line in lines) {
            val marker = userMarkerPattern.matcher(line)
            if (marker.find()) {
                val id = marker.group(1)?.toIntOrNull() ?: marker.group(2)?.toIntOrNull()
                if (id != null) {
                    current = id to mutableListOf(line)
                    sections += current
                    continue
                }
            }
            current?.second?.add(line)
        }
        return sections.firstOrNull { it.first == preferred }?.second?.joinToString("\n") ?: text
    }

    private fun parseInt(raw: String?): Int? = runCatching {
        val value = raw ?: return@runCatching null
        if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toLong(16).toInt()
        else value.toLong().toInt()
    }.getOrNull()

    private companion object {
        const val IME_VISIBLE = 0x2
        val phase0KeywordPattern = Regex(
            "(?i)(InputShown|ImeWindowVis|CurMethod|CurId|ShowRequested)"
        )
    }
}

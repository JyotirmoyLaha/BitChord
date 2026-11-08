package com.music.bitchord.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.res.ResourcesCompat
import com.music.bitchord.R
import com.music.bitchord.ui.components.AppBrand
import com.music.bitchord.ui.replay.Fonts
import com.music.bitchord.ui.replay.drawArtwork
import com.music.bitchord.ui.replay.drawBackdrop
import com.music.bitchord.ui.replay.drawLogo
import com.music.bitchord.ui.replay.ellipsised
import com.music.bitchord.ui.replay.loadBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picked lines as one picture, drawn to a bitmap you can send — laid out
 * the way Apple Music lays out its own lyric cards.
 *
 * ## The layout
 *
 * A frosted panel floating on the sleeve, blurred until only its colour is left:
 *
 * - on top, the passage in semibold white, hung off the left edge;
 * - under it, a slightly lighter band holding the sleeve, the title, the artist
 *   and, on the last row, the mark of the brand picked in Settings.
 *
 * ## Why this is drawn rather than screenshotted
 *
 * The same reason the Replay is. A screenshot of the lyrics panel carries the
 * scrubber, the glow falloff, whichever lines happened to be on screen and the
 * status bar of that particular phone. A card laid out here is the verse, the
 * sleeve and the marks, and nothing else.
 *
 * ## Why it isn't Compose
 *
 * `Bitmap` + `android.graphics.Canvas`, exactly as
 * [com.music.bitchord.ui.replay.ReplayPoster] does it: Compose offscreen on
 * minSdk 26 is a cascade of hardware bitmaps and layers that fails differently
 * per device, while a software canvas draws identically from API 26 up.
 *
 * ## Fitting the type
 *
 * The layout is retried down a fixed ladder of sizes until the rows stop
 * overflowing, and the panel is then grown to exactly the room those rows take.
 * The bottom rung is a floor: a passage too long for the frame lengthens the
 * picture instead of being cut or shrunk past legibility.
 */
internal suspend fun renderLyricsShareCard(
    context: Context,
    card: LyricsShareRequest,
    /** Pad out to the full 1080×1920 frame, the panel centred in it. */
    story: Boolean = false,
): Bitmap = withContext(Dispatchers.Default) {
    val type = Fonts(context)
    val cover = card.artworkUrl?.let { loadBitmap(context, it) }

    // ── Sizing ──────────────────────────────────────────────────────────────
    val furniture = PANEL_TOP + GAP_BODY_TO_BAND + BAND_H
    val frameRoom = STORY_H - STORY_MARGIN * 2 - furniture
    val plan = fittestPlan(type, card.lines, frameRoom)

    val panelH = furniture + plan.content
    val imageH = if (story) max(STORY_H, panelH + STORY_MARGIN * 2) else panelH + IMAGE_MARGIN * 2
    val panelTop = (imageH - panelH) / 2f
    val panel = RectF(PANEL_X, panelTop, IMAGE_W - PANEL_X, panelTop + panelH)

    val bitmap = Bitmap.createBitmap(IMAGE_W, imageH.roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // ── Behind and under the words ──────────────────────────────────────────
    drawBlurredSleeve(canvas, cover, imageH)
    canvas.drawColor(BACKDROP_DIM)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_TINT }
    canvas.drawRoundRect(panel, PANEL_RADIUS, PANEL_RADIUS, fill)

    val bandTop = panel.bottom - BAND_H
    canvas.save()
    canvas.clipRect(panel.left, bandTop, panel.right, panel.bottom)
    fill.color = BAND_TINT
    canvas.drawRoundRect(panel, PANEL_RADIUS, PANEL_RADIUS, fill)
    canvas.restore()

    // ── The passage ─────────────────────────────────────────────────────────
    val left = panel.left + PANEL_PAD
    val right = panel.right - PANEL_PAD
    var y = panel.top + PANEL_TOP
    for (row in plan.rows) {
        val baseline = y + row.gapBefore - row.paint.ascent() +
            if (row.isGap) row.paint.textSize * GAP_MARKER_OFFSET else 0f
        canvas.drawText(row.text, left, baseline, row.paint)
        y += row.gapBefore + row.height
    }

    // ── The band: sleeve, title, artist, marks ──────────────────────────────
    val artTop = bandTop + (BAND_H - ART) / 2f
    drawArtwork(canvas, cover, card.song.title, left, artTop, ART, false)
    val textX = left + ART + ART_GAP
    val room = right - textX
    val title = type.body(BAND_TEXT, Color.WHITE, bold = true)
    val artist = type.body(BAND_TEXT, 0xE6FFFFFF.toInt())
    canvas.drawText(ellipsised(card.song.title, title, room), textX, artTop + TITLE_DROP, title)
    canvas.drawText(ellipsised(card.song.artist, artist, room), textX, artTop + ARTIST_DROP, artist)

    val markBaseline = artTop + MARK_DROP
    if (AppBrand.isApple) {
        drawAppleMusicMark(canvas, context, type, textX, markBaseline)
    } else {
        drawLogo(
            canvas,
            context,
            textX + BRAND_LOGO_W,
            markBaseline,
            width = BRAND_LOGO_W,
            height = BRAND_LOGO_H,
            tint = MARK_COLOR,
        )
        val word = type.body(MARK_TEXT, MARK_COLOR, bold = true)
        canvas.drawText(AppBrand.name, textX + BRAND_LOGO_W + BRAND_LOGO_GAP, markBaseline, word)
    }

    bitmap
}

// ── Pieces ─────────────────────────────────────────────────────────────────

/**
 * The sleeve filling the picture, blurred until only its colour is left.
 *
 * Shrunk to a few dozen pixels, box-blurred there and drawn back up with
 * filtering: a software canvas has no blur of its own that works across every
 * API level, and at this size the blur costs nothing.
 */
private fun drawBlurredSleeve(canvas: Canvas, cover: Bitmap?, height: Float) {
    if (cover == null) {
        drawBackdrop(canvas, null, 0f, height)
        return
    }
    val filtered = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val smallH = max(1, (BLUR_W * height / IMAGE_W).roundToInt())
    val small = Bitmap.createBitmap(BLUR_W, smallH, Bitmap.Config.ARGB_8888)

    // Centre-crop the sleeve to the shape of the picture, so it is not stretched.
    val scale = min(cover.width / IMAGE_W.toFloat(), cover.height / height)
    val cropW = (IMAGE_W * scale).roundToInt()
    val cropH = (height * scale).roundToInt()
    val cropX = (cover.width - cropW) / 2
    val cropY = (cover.height - cropH) / 2
    Canvas(small).drawBitmap(
        cover,
        Rect(cropX, cropY, cropX + cropW, cropY + cropH),
        Rect(0, 0, BLUR_W, smallH),
        filtered,
    )
    boxBlur(small)
    canvas.drawBitmap(small, null, RectF(0f, 0f, IMAGE_W.toFloat(), height), filtered)
    small.recycle()
}

/** Repeated box passes, which together come out as near a gaussian as matters. */
private fun boxBlur(bitmap: Bitmap) {
    val w = bitmap.width
    val h = bitmap.height
    var pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    fun pass(horizontal: Boolean) {
        val out = IntArray(pixels.size)
        val n = BLUR_RADIUS * 2 + 1
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (offset in -BLUR_RADIUS..BLUR_RADIUS) {
                    val sx = if (horizontal) (x + offset).coerceIn(0, w - 1) else x
                    val sy = if (horizontal) y else (y + offset).coerceIn(0, h - 1)
                    val p = pixels[sy * w + sx]
                    r += Color.red(p)
                    g += Color.green(p)
                    b += Color.blue(p)
                }
                out[y * w + x] = Color.rgb(r / n, g / n, b / n)
            }
        }
        pixels = out
    }
    repeat(BLUR_PASSES) {
        pass(horizontal = true)
        pass(horizontal = false)
    }
    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
}

/** The apple and "Music", sitting on [baseline], starting at [x]. */
private fun drawAppleMusicMark(canvas: Canvas, context: Context, type: Fonts, x: Float, baseline: Float) {
    val apple = runCatching {
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_apple_logo, null)
    }.getOrNull()?.mutate()
    // The glyph leaves about a tenth of its box spare on the left of the viewport.
    val glyphLeft = x - APPLE_SIZE * 0.09f
    val glyphTop = baseline - APPLE_SIZE
    apple?.setTint(MARK_COLOR)
    apple?.setBounds(
        glyphLeft.toInt(),
        glyphTop.toInt(),
        (glyphLeft + APPLE_SIZE).toInt(),
        (glyphTop + APPLE_SIZE).toInt(),
    )
    apple?.draw(canvas)
    val word = type.body(MARK_TEXT, MARK_COLOR, bold = true).apply { letterSpacing = -0.01f }
    canvas.drawText("Music", x + APPLE_SIZE * 0.98f, baseline, word)
}

// ── Fitting ────────────────────────────────────────────────────────────────

private class Row(
    val text: String,
    val paint: Paint,
    val height: Float,
    val gapBefore: Float,
    val isGap: Boolean = false,
)

/** The rows at one size, with everything needed to place them. */
private class Plan(val rows: List<Row>) {
    /** Distance from the top of the first line to the bottom of the last. */
    val content: Float
        get() = rows.fold(0f) { total, row -> total + row.gapBefore + row.height }
}

/**
 * The largest size on [FIT_LADDER] whose rows still fit in [room], or the
 * bottom rung when none of them do — under it the type stops being worth
 * sharing, so the frame grows instead. That the player budgets characters at
 * the pick is what keeps this branch from ever being reached in practice.
 */
private fun fittestPlan(
    type: Fonts,
    lines: List<LyricsShareLine>,
    room: Float,
): Plan {
    for (size in FIT_LADDER) {
        val plan = planAt(type, lines, size)
        if (plan.content <= room) return plan
    }
    return planAt(type, lines, FIT_LADDER.last())
}

/**
 * The rows at one [size], set in the face the lyrics panel draws with — SF Pro
 * Display Semibold with the panel's tight tracking and line advance, carried over
 * as ratios of the panel's 34sp headline.
 */
private fun planAt(type: Fonts, lines: List<LyricsShareLine>, size: Float): Plan {
    val primary = type.body(size, Color.WHITE, bold = true).apply { letterSpacing = TRACKING }
    val subSize = (size * SUB_RATIO).coerceAtLeast(SUB_FLOOR)
    val secondary = type.body(subSize, SUB_COLOR, bold = true).apply { letterSpacing = SUB_TRACKING }

    val rows = mutableListOf<Row>()
    lines.forEachIndexed { index, line ->
        if (line.isGap) {
            // The skipped lines, as "(...)" — the conventional editorial mark
            // for an omission, smaller and a dim grey.
            val marker = type.body(size * 0.65f, 0x66FFFFFF.toInt())
            val markerHeight = marker.descent() - marker.ascent()
            rows += Row("(...)", marker, markerHeight, 0f, isGap = true)
            return@forEachIndexed
        }
        var firstOfLine = true
        fun add(text: String, paint: Paint, height: Float) {
            // One verse line is one paragraph: its first row carries the space
            // above it and its wraps sit tight underneath.
            val gap = when {
                rows.isEmpty() -> 0f
                firstOfLine && index > 0 -> size * 0.22f
                else -> 0f
            }
            rows += Row(text, paint, height, gap)
            firstOfLine = false
        }
        wrap(line.text, primary, LYRICS_W).forEach { add(it, primary, size * LEAD) }
        val sub = line.subText
        if (!sub.isNullOrBlank() && sub != line.text) {
            wrap(sub, secondary, LYRICS_W).forEach { add(it, secondary, subSize * SUB_LEAD) }
        }
    }
    return Plan(rows)
}

// ── Text ───────────────────────────────────────────────────────────────────

/**
 * Greedy wrap at [width], breaking wherever the line has to.
 *
 * Not a plain word-wrap: Japanese, Chinese and Korean lyrics carry no spaces at
 * all. `breakText` finds the character that fits, and only then is the break
 * walked back to a space — so scripts that keep their words apart lose nothing,
 * and scripts that don't still get several lines.
 */
private fun wrap(text: String, paint: Paint, width: Float): List<String> {
    val source = text.trim()
    if (source.isEmpty()) return listOf("")

    val out = mutableListOf<String>()
    var rest = source
    while (rest.isNotEmpty()) {
        val fit = paint.breakText(rest, true, width, null)
        if (fit <= 0) {
            out += rest
            break
        }
        val space = rest.lastIndexOf(' ', fit - 1)
        val take = if (space > 0 && fit < rest.length) space else fit
        if (take <= 0) {
            out += rest
            break
        }
        out += rest.substring(0, take).trimEnd()
        rest = rest.substring(take).trimStart()
    }
    return out.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
}

// ── Geometry ───────────────────────────────────────────────────────────────

private const val IMAGE_W = 1080

/** The story frame, and the least backdrop kept above and below the panel in it. */
private const val STORY_H = 1920f
private const val STORY_MARGIN = 140f

/** Backdrop showing above and below the panel when it is not a story. */
private const val IMAGE_MARGIN = 170f

/** The panel inset on both sides, so the backdrop stays visible around it. */
private const val PANEL_X = 130f
private const val PANEL_PAD = 44f
private const val PANEL_TOP = 39f
private const val PANEL_RADIUS = 33f
private const val LYRICS_W = IMAGE_W - PANEL_X * 2 - PANEL_PAD * 2

private const val GAP_BODY_TO_BAND = 39f

/** The band under the words: its height, the sleeve in it, the three text rows. */
private const val BAND_H = 252f
private const val ART = 156f
private const val ART_GAP = 32f
private const val BAND_TEXT = 46f
private const val TITLE_DROP = 40f
private const val ARTIST_DROP = 94f
private const val MARK_DROP = 154f

/** The row of marks. */
private const val MARK_TEXT = 42f
private const val APPLE_SIZE = 41f
private val MARK_COLOR = 0x99FFFFFF.toInt()
private const val BRAND_LOGO_W = 42f
private const val BRAND_LOGO_H = 28f
private const val BRAND_LOGO_GAP = 12f

/** Tints: the whole backdrop, the frosted panel over it, the band over that. */
private const val BACKDROP_DIM = 0x40000000
private const val PANEL_TINT = 0x66000000
private const val BAND_TINT = 0x14FFFFFF

/** The blur: the width it runs at, its box radius and how many passes. */
private const val BLUR_W = 48
private const val BLUR_RADIUS = 5
private const val BLUR_PASSES = 3

/** Smallest the translation line is allowed to get before it stops being legible. */
private const val SUB_FLOOR = 24f

/** Small visual correction so the omission marker sits below the line above. */
private const val GAP_MARKER_OFFSET = 0.18f

/**
 * The sizes tried, largest first. Stepping by a visible amount keeps two cards
 * with adjacent line counts from coming out nearly the same size.
 */
private val FIT_LADDER = floatArrayOf(68f, 62f, 56f, 50f, 44f, 38f, 32f)

// ── The panel's type, as ratios ────────────────────────────────────────────

/** Tracking of the panel's headline: -0.7sp against its 34sp. */
private const val TRACKING = -0.7f / 34f

/** The same -0.7sp, against the 20sp it sets the line underneath in. */
private const val SUB_TRACKING = -0.7f / 20f

/** Line advance: 41sp over 34sp for the words, 25sp over 20sp for the sub. */
private const val LEAD = 41f / 34f
private const val SUB_LEAD = 25f / 20f

/** The sub-line against the words above it, and the white it is drawn in. */
private const val SUB_RATIO = 20f / 34f
private val SUB_COLOR = 0xD9FFFFFF.toInt()

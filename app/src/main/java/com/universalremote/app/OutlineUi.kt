package com.universalremote.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.universalremote.app.connection.ConnectionMethod

internal val Ink = Color.Black
internal val Paper = Color.White
internal val Dim = Color(0xFF8F8F8F)
internal val Hairline = Color(0xFF555555)

internal enum class Glyph {
    BLUETOOTH, PHONE, WIFI, REMOTE, MOUSE, HOME, MENU, BACK, PLUS, TV, YOUTUBE,
    REFRESH, KEYBOARD, LEFT_CLICK, RIGHT_CLICK, CHECK, IP, CLOSE, POINTER
}

@Composable
internal fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, if (enabled) Paper else Hairline, RoundedCornerShape(30.dp))
            .clip(RoundedCornerShape(30.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 5.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        UiText(
            text,
            if (compact) 13.sp else 10.sp,
            if (enabled) Paper else Dim,
            FontWeight.Medium,
            letterSpacing = 1.3.sp
        )
    }
}

@Composable
internal fun OutlineIconButton(glyph: Glyph, onClick: () -> Unit, size: Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        OutlineIcon(glyph, size * 0.58f)
    }
}

@Composable
internal fun OutlineIcon(glyph: Glyph, size: Dp, color: Color = Paper) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (w.coerceAtMost(h) * 0.075f).coerceAtLeast(1.5.dp.toPx())
        fun line(
            first: Offset,
            second: Offset,
            third: Offset? = null,
            fourth: Offset? = null,
            width: Float = stroke
        ) {
            val points = listOfNotNull(first, second, third, fourth)
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun roundRect(x: Float, y: Float, width: Float, height: Float, radius: Float = w * 0.12f) {
            drawRoundRect(
                color,
                topLeft = Offset(x, y),
                size = Size(width, height),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        when (glyph) {
            Glyph.BLUETOOTH -> {
                line(Offset(w * .5f, h * .08f), Offset(w * .5f, h * .92f))
                line(Offset(w * .5f, h * .08f), Offset(w * .83f, h * .34f), Offset(w * .2f, h * .74f))
                line(Offset(w * .5f, h * .92f), Offset(w * .83f, h * .66f), Offset(w * .2f, h * .26f))
            }
            Glyph.PHONE -> {
                roundRect(w * .25f, h * .06f, w * .5f, h * .88f, w * .12f)
                drawCircle(color, stroke * .45f, Offset(w * .5f, h * .84f), style = Stroke(stroke * .65f))
            }
            Glyph.WIFI -> {
                drawArc(color, 220f, 100f, false, Offset(w * .08f, h * .16f), Size(w * .84f, h * .72f), style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(color, 225f, 90f, false, Offset(w * .25f, h * .34f), Size(w * .5f, h * .48f), style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(color, stroke * .55f, Offset(w * .5f, h * .83f))
            }
            Glyph.REMOTE -> {
                roundRect(w * .31f, h * .06f, w * .38f, h * .88f, w * .16f)
                drawCircle(color, w * .045f, Offset(w * .5f, h * .23f), style = Stroke(stroke * .8f))
                line(Offset(w * .42f, h * .52f), Offset(w * .58f, h * .52f))
                line(Offset(w * .42f, h * .65f), Offset(w * .58f, h * .65f))
                line(Offset(w * .42f, h * .78f), Offset(w * .58f, h * .78f))
            }
            Glyph.TV -> {
                roundRect(w * .09f, h * .13f, w * .82f, h * .65f, w * .09f)
                line(Offset(w * .37f, h * .81f), Offset(w * .63f, h * .81f))
                line(Offset(w * .5f, h * .78f), Offset(w * .5f, h * .9f))
            }
            Glyph.MOUSE, Glyph.POINTER -> {
                line(Offset(w * .22f, h * .08f), Offset(w * .22f, h * .9f), Offset(w * .46f, h * .68f), Offset(w * .61f, h * .94f))
                line(Offset(w * .46f, h * .68f), Offset(w * .62f, h * .56f), Offset(w * .22f, h * .08f))
            }
            Glyph.HOME -> {
                line(Offset(w * .08f, h * .47f), Offset(w * .5f, h * .1f), Offset(w * .92f, h * .47f))
                line(Offset(w * .2f, h * .4f), Offset(w * .2f, h * .9f), Offset(w * .8f, h * .9f), Offset(w * .8f, h * .4f))
                line(Offset(w * .43f, h * .9f), Offset(w * .43f, h * .63f), Offset(w * .57f, h * .63f), Offset(w * .57f, h * .9f))
            }
            Glyph.MENU -> {
                repeat(3) { index ->
                    val y = h * (.23f + index * .27f)
                    drawCircle(color, stroke * 1.1f, Offset(w * .2f, y), style = Stroke(stroke * .72f))
                    line(Offset(w * .42f, y), Offset(w * .9f, y), width = stroke * .85f)
                }
            }
            Glyph.BACK -> {
                line(Offset(w * .83f, h * .18f), Offset(w * .36f, h * .5f), Offset(w * .83f, h * .82f))
                line(Offset(w * .38f, h * .5f), Offset(w * .9f, h * .5f))
            }
            Glyph.PLUS -> {
                line(Offset(w * .5f, h * .15f), Offset(w * .5f, h * .85f))
                line(Offset(w * .15f, h * .5f), Offset(w * .85f, h * .5f))
            }
            Glyph.YOUTUBE -> {
                roundRect(w * .07f, h * .2f, w * .86f, h * .6f, w * .16f)
                line(Offset(w * .43f, h * .35f), Offset(w * .66f, h * .5f), Offset(w * .43f, h * .65f), Offset(w * .43f, h * .35f))
            }
            Glyph.REFRESH -> {
                drawArc(color, 40f, 275f, false, Offset(w * .15f, h * .15f), Size(w * .7f, h * .7f), style = Stroke(stroke, cap = StrokeCap.Round))
                line(Offset(w * .72f, h * .12f), Offset(w * .88f, h * .15f), Offset(w * .84f, h * .32f))
            }
            Glyph.KEYBOARD -> {
                roundRect(w * .08f, h * .2f, w * .84f, h * .6f, w * .08f)
                repeat(3) { row ->
                    repeat(4) { col ->
                        drawCircle(color, stroke * .32f, Offset(w * (.22f + col * .18f), h * (.37f + row * .13f)))
                    }
                }
            }
            Glyph.LEFT_CLICK, Glyph.RIGHT_CLICK -> {
                drawCircle(color, w * .35f, Offset(w * .5f, h * .5f), style = Stroke(stroke))
                line(Offset(w * .5f, h * .16f), Offset(w * .5f, h * .5f))
                if (glyph == Glyph.RIGHT_CLICK) line(Offset(w * .5f, h * .5f), Offset(w * .78f, h * .5f))
            }
            Glyph.CHECK -> {
                line(Offset(w * .15f, h * .52f), Offset(w * .4f, h * .78f), Offset(w * .87f, h * .2f))
            }
            Glyph.IP -> {
                roundRect(w * .12f, h * .12f, w * .76f, h * .76f, w * .1f)
                line(Offset(w * .28f, h * .35f), Offset(w * .72f, h * .35f))
                line(Offset(w * .28f, h * .52f), Offset(w * .72f, h * .52f))
                line(Offset(w * .28f, h * .69f), Offset(w * .58f, h * .69f))
            }
            Glyph.CLOSE -> {
                line(Offset(w * .2f, h * .2f), Offset(w * .8f, h * .8f))
                line(Offset(w * .8f, h * .2f), Offset(w * .2f, h * .8f))
            }
        }
    }
}

@Composable
internal fun BrandMark() {
    Column {
        UiText("UNIVERSAL", 20.sp, Paper, FontWeight.Light, letterSpacing = 4.sp)
        UiText("REMOTE CONTROL", 8.sp, Dim, FontWeight.Medium, letterSpacing = 2.2.sp)
    }
}

@Composable
internal fun MorphingMark() {
    Canvas(Modifier.size(40.dp)) {
        val side = size.minDimension
        val stroke = 2.6.dp.toPx()
        drawArc(Paper, 15f, 285f, false, Offset(2.dp.toPx(), 2.dp.toPx()), Size(side - 4.dp.toPx(), side - 4.dp.toPx()), style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(Paper, side * 0.09f, Offset(side * 0.78f, side * 0.18f))
    }
}

@Composable
internal fun ThinDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
internal fun UiText(
    text: String,
    size: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.sp,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = letterSpacing,
            textAlign = textAlign,
            lineHeight = size * 1.35f
        )
    )
}

@Composable
internal fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .border(1.dp, Hairline, RoundedCornerShape(22.dp))
            .background(Ink, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UiText(text, 11.sp, Paper, textAlign = TextAlign.Center)
    }
}

internal fun connectionGlyph(method: ConnectionMethod): Glyph = when (method) {
    ConnectionMethod.BLUETOOTH -> Glyph.BLUETOOTH
    ConnectionMethod.HOTSPOT -> Glyph.PHONE
    ConnectionMethod.WIFI -> Glyph.WIFI
}

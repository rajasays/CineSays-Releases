package com.cineglass.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter

@Composable
internal fun TorrentPuzzleLoading(title: String, poster: String?, status: String) {
    val artworkUrl = poster?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w780$it"
    }
    val painter = rememberAsyncImagePainter(artworkUrl)
    val ready = painter.state is AsyncImagePainter.State.Success
    val assembly = remember(artworkUrl) { Animatable(0f) }
    LaunchedEffect(artworkUrl, ready) {
        if (ready) {
            assembly.snapTo(0f)
            assembly.animateTo(1f, tween(2400))
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF090E19))) {
        if (ready) {
            Box(Modifier.fillMaxSize().drawWithCache {
                val columns = if (size.width > size.height) 5 else 3
                val rows = if (columns == 5) 3 else 5
                val width = size.width / columns
                val height = size.height / rows
                val pieces = List(columns * rows) { index ->
                    val col = index % columns
                    val row = index / columns
                    puzzlePiece(col, row, columns, rows, width, height)
                }
                val artwork = painter.intrinsicSize
                val scale = if (artwork.width > 0f && artwork.height > 0f &&
                    artwork.width.isFinite() && artwork.height.isFinite())
                    maxOf(size.width / artwork.width, size.height / artwork.height) else 0f
                val imageSize = if (scale > 0f) Size(artwork.width * scale, artwork.height * scale) else size
                val imageX = (size.width - imageSize.width) / 2f
                val imageY = (size.height - imageSize.height) / 2f
                onDrawBehind {
                    val time = assembly.value
                    pieces.forEachIndexed { index, path ->
                        val progress = FastOutSlowInEasing.transform(
                            ((time - index * .039f) / .44f).coerceIn(0f, 1f)
                        )
                        if (progress > 0f) {
                            val x = if (index % 2 == 0) -.48f * width else .48f * width
                            val y = if (index % 3 == 0) -.45f * height else .45f * height
                            translate(x * (1f - progress), y * (1f - progress)) {
                                clipPath(path) {
                                    translate(imageX, imageY) {
                                        with(painter) { draw(imageSize, alpha = progress) }
                                    }
                                }
                                drawPath(path, Color(0xFF090E19).copy(alpha = .48f * progress),
                                    style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
            })
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = .16f),
            .55f to Color.Black.copy(alpha = .24f),
            1f to Color.Black.copy(alpha = .91f)
        )))
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 540.dp)
                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(Modifier.width(132.dp), color = Cs.accent)
            Text(status, color = Color.White.copy(alpha = .86f), fontSize = 14.sp)
        }
    }
}

private fun puzzlePiece(col: Int, row: Int, columns: Int, rows: Int, width: Float, height: Float): Path {
    val x = col * width
    val y = row * height
    val depth = minOf(width, height) * .18f
    val direction = if ((row + col) % 2 == 0) 1f else -1f
    return Path().apply {
        moveTo(x, y)
        edge(x, y, width, 0f, if (row == 0) 0f else depth * direction)
        edge(x + width, y, 0f, height, if (col == columns - 1) 0f else depth * direction)
        edge(x + width, y + height, -width, 0f, if (row == rows - 1) 0f else depth * direction)
        edge(x, y + height, 0f, -height, if (col == 0) 0f else depth * direction)
        close()
    }
}

/** The symmetric tab lets adjoining pieces share exactly the same curve in reverse. */
private fun Path.edge(x: Float, y: Float, dx: Float, dy: Float, depth: Float) {
    if (depth == 0f) {
        lineTo(x + dx, y + dy)
        return
    }
    val nx = kotlin.math.sign(dy) * depth
    val ny = -kotlin.math.sign(dx) * depth
    fun point(t: Float, h: Float) = Offset(x + dx * t + nx * h, y + dy * t + ny * h)
    val start = point(.38f, 0f)
    val a = point(.52f, 0f)
    val b = point(.28f, 1f)
    val top = point(.50f, 1f)
    val c = point(.72f, 1f)
    val d = point(.48f, 0f)
    val end = point(.62f, 0f)
    lineTo(start.x, start.y)
    cubicTo(a.x, a.y, b.x, b.y, top.x, top.y)
    cubicTo(c.x, c.y, d.x, d.y, end.x, end.y)
    lineTo(x + dx, y + dy)
}

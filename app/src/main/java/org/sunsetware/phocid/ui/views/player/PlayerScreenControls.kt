package org.sunsetware.phocid.ui.views.player

import androidx.compose.animation.*
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.sunsetware.phocid.R
import org.sunsetware.phocid.Strings
import org.sunsetware.phocid.TNUM
import org.sunsetware.phocid.data.*
import org.sunsetware.phocid.format
import org.sunsetware.phocid.ui.components.*
import org.sunsetware.phocid.ui.theme.INACTIVE_ALPHA
import org.sunsetware.phocid.ui.theme.Typography
import org.sunsetware.phocid.ui.theme.contentColor
import org.sunsetware.phocid.ui.theme.contentColorVariant
import org.sunsetware.phocid.ui.theme.darken

@Immutable
sealed class PlayerScreenControls {
    @Composable
    abstract fun Compose(
        currentTrack: Track,
        currentTrackIsFavorite: Boolean,
        isPlaying: Boolean,
        repeat: Int,
        shuffle: Boolean,
        currentPosition: () -> Long,
        overflowMenuItems: List<MenuItem>,
        dragModifier: Modifier,
        containerColor: Color,
        contentColor: Color,
        onSeekToFraction: (Float) -> Unit,
        onToggleRepeat: () -> Unit,
        onSeekToPreviousSmart: () -> Unit,
        onTogglePlay: () -> Unit,
        onSeekToNext: () -> Unit,
        onToggleShuffle: () -> Unit,
        onTogglePlayQueue: () -> Unit,
        onToggleCurrentTrackIsFavorite: () -> Unit,
    )
}

val PlayerScreenControlsDefault =
    PlayerScreenControlsDefaultBase(
        fillMaxSize = false,
        verticalArrangement = Arrangement.Top,
        currentTrackInfoFirst = false,
        currentTrackInfo = {
            currentTrack: Track,
            currentTrackIsFavorite: Boolean,
            containerColor: Color,
            overflowMenuItems: List<MenuItem>,
            onTogglePlayQueue: () -> Unit,
            onToggleCurrentTrackIsFavorite: () -> Unit ->
            val color = containerColor.darken()
            Surface(
                color = color,
                contentColor = color.contentColor(),
                modifier = Modifier.clickable(onClick = onTogglePlayQueue),
            ) {
                LibraryListItemHorizontal(
                    title = currentTrack.displayTitle,
                    subtitle = currentTrack.displayArtistWithAlbum,
                    lead = {
                        IconButton(onClick = onToggleCurrentTrackIsFavorite) {
                            AnimatedContent(currentTrackIsFavorite) { animatedIsFavorite ->
                                if (animatedIsFavorite) {
                                    Icon(
                                        Icons.Filled.Favorite,
                                        Strings[R.string.player_now_playing_remove_favorites],
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.FavoriteBorder,
                                        Strings[R.string.player_now_playing_add_favorites],
                                    )
                                }
                            }
                        }
                    },
                    actions = { OverflowMenu(overflowMenuItems) },
                    marquee = true,
                )
            }
        },
    )

val PlayerScreenControlsNoQueue =
    PlayerScreenControlsDefaultBase(
        fillMaxSize = true,
        verticalArrangement = Arrangement.SpaceEvenly,
        currentTrackInfoFirst = true,
        currentTrackInfo = {
            currentTrack: Track,
            currentTrackIsFavorite: Boolean,
            containerColor: Color,
            overflowMenuItems: List<MenuItem>,
            onTogglePlayQueue: () -> Unit,
            onToggleCurrentTrackIsFavorite: () -> Unit ->
            Surface(
                color = containerColor,
                contentColor = containerColor.contentColor(),
                modifier = Modifier.clickable(onClick = onTogglePlayQueue),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SingleLineText(
                            currentTrack.displayTitle,
                            style = Typography.titleLarge,
                            modifier = Modifier.weight(1f).padding(start = 16.dp).basicMarquee(),
                        )

                        IconButton(onClick = onToggleCurrentTrackIsFavorite) {
                            AnimatedContent(currentTrackIsFavorite) { animatedIsFavorite ->
                                if (animatedIsFavorite) {
                                    Icon(
                                        Icons.Filled.Favorite,
                                        Strings[R.string.player_now_playing_remove_favorites],
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.FavoriteBorder,
                                        Strings[R.string.player_now_playing_add_favorites],
                                    )
                                }
                            }
                        }
                        OverflowMenu(overflowMenuItems)
                    }
                    SingleLineText(
                        currentTrack.displayArtist,
                        style = Typography.labelLarge,
                        color = contentColorVariant(),
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleLineText(
                        currentTrack.album ?: "",
                        style = Typography.labelLarge,
                        color = contentColorVariant(),
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        },
    )

@Immutable
class PlayerScreenControlsDefaultBase(
    private val fillMaxSize: Boolean,
    private val verticalArrangement: Arrangement.Vertical,
    private val currentTrackInfoFirst: Boolean,
    private val currentTrackInfo:
        @Composable
        (
            currentTrack: Track,
            currentTrackIsFavorite: Boolean,
            containerColor: Color,
            overflowMenuItems: List<MenuItem>,
            onTogglePlayQueue: () -> Unit,
            onToggleCurrentTrackIsFavorite: () -> Unit,
        ) -> Unit,
) : PlayerScreenControls() {
    @Composable
    override fun Compose(
        currentTrack: Track,
        currentTrackIsFavorite: Boolean,
        isPlaying: Boolean,
        repeat: Int,
        shuffle: Boolean,
        currentPosition: () -> Long,
        overflowMenuItems: List<MenuItem>,
        dragModifier: Modifier,
        containerColor: Color,
        contentColor: Color,
        onSeekToFraction: (Float) -> Unit,
        onToggleRepeat: () -> Unit,
        onSeekToPreviousSmart: () -> Unit,
        onTogglePlay: () -> Unit,
        onSeekToNext: () -> Unit,
        onToggleShuffle: () -> Unit,
        onTogglePlayQueue: () -> Unit,
        onToggleCurrentTrackIsFavorite: () -> Unit,
    ) {
        val context = LocalContext.current
        var progress by remember { mutableFloatStateOf(0f) }
        val progressSeconds by
            remember(currentTrack) {
                derivedStateOf {
                    (progress * (currentTrack.duration.inWholeSeconds))
                        .let { if (it.isNaN()) 0f else it }
                        .roundToInt()
                }
            }
        var isDraggingProgressSlider by remember { mutableStateOf(false) }

        // Update progress
        LaunchedEffect(currentTrack) {
            val frameTime = (1f / context.display.refreshRate).toDouble().milliseconds

            while (isActive) {
                val currentPosition = currentPosition()
                if (!isDraggingProgressSlider) {
                    progress =
                        (currentPosition.toFloat() / (currentTrack.duration.inWholeMilliseconds))
                            .takeIf { !it.isNaN() } ?: 0f
                }
                delay(frameTime)
            }
        }

        Surface(color = containerColor, contentColor = contentColor, modifier = dragModifier) {
            Column(
                modifier =
                    if (fillMaxSize) {
                        Modifier.fillMaxSize()
                    } else Modifier,
                verticalArrangement = verticalArrangement,
            ) {
                if (currentTrackInfoFirst) {
                    currentTrackInfo(
                        currentTrack,
                        currentTrackIsFavorite,
                        containerColor,
                        overflowMenuItems,
                        onTogglePlayQueue,
                        onToggleCurrentTrackIsFavorite,
                    )
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SingleLineText(
                                progressSeconds.seconds.format(),
                                style = Typography.labelMedium.copy(fontFeatureSettings = TNUM),
                                textAlign = TextAlign.Center,
                                modifier =
                                    Modifier.defaultMinSize(
                                        minWidth = 36.dp * LocalDensity.current.fontScale
                                    ),
                            )
                            ProgressSlider(
                                value = progress,
                                onValueChange = {
                                    isDraggingProgressSlider = true
                                    progress = it
                                },
                                onValueChangeFinished = {
                                    isDraggingProgressSlider = false
                                    onSeekToFraction(progress)
                                },
                                animate = isPlaying && !isDraggingProgressSlider,
                                modifier = Modifier.padding(horizontal = 16.dp).weight(1f),
                            )
                            SingleLineText(
                                currentTrack.duration.format(),
                                style = Typography.labelMedium.copy(fontFeatureSettings = TNUM),
                                textAlign = TextAlign.Center,
                                modifier =
                                    Modifier.defaultMinSize(
                                        minWidth = 36.dp * LocalDensity.current.fontScale
                                    ),
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onToggleRepeat) {
                                when (repeat) {
                                    Player.REPEAT_MODE_ALL ->
                                        Icon(
                                            Icons.Filled.Repeat,
                                            contentDescription =
                                                Strings[R.string.player_repeat_mode_all],
                                        )

                                    Player.REPEAT_MODE_ONE ->
                                        Icon(
                                            Icons.Filled.RepeatOne,
                                            contentDescription =
                                                Strings[R.string.player_repeat_mode_one],
                                        )

                                    else ->
                                        Icon(
                                            Icons.Filled.Repeat,
                                            contentDescription =
                                                Strings[R.string.player_repeat_mode_off],
                                            modifier = Modifier.alpha(INACTIVE_ALPHA),
                                        )
                                }
                            }
                            IconButton(onClick = onSeekToPreviousSmart) {
                                Icon(
                                    Icons.Filled.SkipPrevious,
                                    contentDescription = Strings[R.string.player_previous],
                                )
                            }
                            FloatingActionButton(
                                onClick = onTogglePlay,
                                containerColor = contentColor,
                                contentColor = containerColor,
                            ) {
                                AnimatedContent(targetState = isPlaying) { animatedIsPlaying ->
                                    if (animatedIsPlaying) {
                                        Icon(
                                            Icons.Filled.Pause,
                                            contentDescription = Strings[R.string.player_pause],
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = Strings[R.string.player_play],
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = onSeekToNext) {
                                Icon(
                                    Icons.Filled.SkipNext,
                                    contentDescription = Strings[R.string.player_next],
                                )
                            }

                            IconButton(onClick = onToggleShuffle) {
                                if (shuffle) {
                                    Icon(
                                        Icons.Filled.Shuffle,
                                        contentDescription = Strings[R.string.player_shuffle_on],
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Shuffle,
                                        contentDescription = Strings[R.string.player_shuffle_off],
                                        modifier = Modifier.alpha(INACTIVE_ALPHA),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                if (!currentTrackInfoFirst) {
                    currentTrackInfo(
                        currentTrack,
                        currentTrackIsFavorite,
                        containerColor,
                        overflowMenuItems,
                        onTogglePlayQueue,
                        onToggleCurrentTrackIsFavorite,
                    )
                }
            }
        }
    }
}

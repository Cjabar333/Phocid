@file:OptIn(ExperimentalEncodingApi::class)

package org.sunsetware.phocid.data

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.palette.graphics.Palette
import androidx.palette.graphics.Target
import com.ibm.icu.text.Collator
import com.ibm.icu.util.CaseInsensitiveString
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.apache.commons.io.FilenameUtils
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.KeyNotFoundException
import org.jaudiotagger.tag.TagTextField
import org.sunsetware.phocid.R
import org.sunsetware.phocid.READ_PERMISSION
import org.sunsetware.phocid.Strings
import org.sunsetware.phocid.UNKNOWN
import org.sunsetware.phocid.utils.*

@Immutable
@Serializable
data class Track(
    val id: Long,
    val path: String,
    val fileName: String,
    val dateAdded: Long,
    val version: Long,
    val title: String?,
    val artists: List<String>,
    val album: String?,
    val albumArtist: String?,
    val genres: List<String>,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val duration: Duration,
    val size: Long,
    val format: String,
    val sampleRate: Int,
    val bitRate: Long,
    val bitDepth: Int,
    val hasArtwork: Boolean,
    @Serializable(with = ColorSerializer::class) val vibrantColor: Color?,
    @Serializable(with = ColorSerializer::class) val mutedColor: Color?,
    val unsyncedLyrics: String?,
    val comment: String?,
) : Searchable, Sortable {
    val uri
        get() = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id)

    val displayTitle
        get() = title ?: FilenameUtils.getBaseName(path)

    val displayArtistOrNull
        get() = if (artists.any()) Strings.conjoin(artists) else null

    val displayArtist
        get() = displayArtistOrNull ?: UNKNOWN

    val displayAlbum
        get() = album ?: UNKNOWN

    val displayAlbumArtist
        get() = albumArtist ?: UNKNOWN

    val displayGenre
        get() = if (genres.any()) Strings.conjoin(genres) else UNKNOWN

    val displayYear
        get() = year?.toString() ?: UNKNOWN

    val displayNumber
        get() =
            if (trackNumber != null) {
                if (discNumber != null) {
                    Strings[R.string.track_number_with_disc].icuFormat(discNumber, trackNumber)
                } else {
                    Strings[R.string.track_number_without_disc].icuFormat(trackNumber)
                }
            } else {
                if (discNumber != null) {
                    Strings[R.string.track_disc_without_number].icuFormat(discNumber)
                } else {
                    Strings[R.string.track_number_not_available]
                }
            }

    val displayArtistWithAlbum
        get() = Strings.separate(displayArtist, album)

    @Transient
    override val searchableStrings =
        listOfNotNull(displayTitle, displayArtist, album, albumArtist, fileName)

    override val sortTitle
        get() = displayTitle

    override val sortArtist
        get() = Strings.conjoin(artists)

    override val sortAlbum
        get() = album ?: ""

    override val sortAlbumArtist
        get() = albumArtist ?: album?.let { artists.firstOrNull() } ?: ""

    override val sortDiscNumber
        get() = discNumber ?: 0

    override val sortTrackNumber
        get() = trackNumber ?: 0

    override val sortGenre
        get() = Strings.conjoin(genres)

    override val sortYear
        get() = year ?: 0

    override val sortIsFolder
        get() = false

    override val sortFilename
        get() = fileName

    override val sortDateAdded
        get() = dateAdded

    override val sortDateModified
        get() = version

    companion object {
        val SortingOptions =
            mapOf(
                "Title" to
                    SortingOption(
                        R.string.sorting_title,
                        listOf(
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.YEAR,
                            SortingKey.FILE_NAME,
                        ),
                    ),
                "Artist" to
                    SortingOption(
                        R.string.sorting_artist,
                        listOf(
                            SortingKey.ARTIST,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.YEAR,
                            SortingKey.FILE_NAME,
                        ),
                    ),
                "Album" to
                    SortingOption(
                        R.string.sorting_album,
                        listOf(
                            SortingKey.ALBUM,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.YEAR,
                            SortingKey.FILE_NAME,
                        ),
                    ),
                "Album artist" to
                    SortingOption(
                        R.string.sorting_album_artist,
                        listOf(
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.YEAR,
                            SortingKey.FILE_NAME,
                        ),
                    ),
                "Year" to
                    SortingOption(
                        R.string.sorting_year,
                        listOf(
                            SortingKey.YEAR,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.FILE_NAME,
                        ),
                    ),
                "Date added" to
                    SortingOption(
                        R.string.sorting_date_added,
                        listOf(
                            SortingKey.DATE_ADDED,
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.YEAR,
                            SortingKey.FILE_NAME,
                        ),
                    ),
                "Date modified" to
                    SortingOption(
                        R.string.sorting_date_modified,
                        listOf(
                            SortingKey.DATE_MODIFIED,
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.YEAR,
                            SortingKey.FILE_NAME,
                        ),
                    ),
            )
    }
}

private val cachedScreenSize = AtomicInteger(0)

fun loadArtwork(context: Context, id: Long, sizeLimit: Int? = null): Bitmap? {
    try {
        val thumbnailSize =
            sizeLimit
                ?: cachedScreenSize.get().takeIf { it > 0 }
                ?: run {
                    val screenSize =
                        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                            .maximumWindowMetrics
                            .bounds
                    val limit = min(screenSize.width(), screenSize.height()).coerceAtLeast(256)
                    cachedScreenSize.set(limit)
                    limit
                }
        val bitmap =
            context.contentResolver.loadThumbnail(
                ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id),
                Size(thumbnailSize, thumbnailSize),
                null,
            )
        return bitmap
    } catch (ex: Exception) {
        Log.e("Phocid", "Can't load artwork for $id", ex)
        return null
    }
}

fun loadLyrics(track: Track, charsetName: String?): Lyrics? {
    try {
        val trackFileNameWithoutExtension = FilenameUtils.getBaseName(track.path)
        val trackFileName = FilenameUtils.getName(track.path)
        val files = File(FilenameUtils.getPath(track.path)).listFiles()
        return files
            ?.filter { it.extension.equals("lrc", true) }
            ?.firstOrNull {
                it.nameWithoutExtension.equals(trackFileNameWithoutExtension, true) ||
                    it.nameWithoutExtension.equals(trackFileName, true)
            }
            ?.readBytes()
            ?.let { parseLrc(it, charsetName) }
    } catch (ex: Exception) {
        Log.e("Phocid", "Can't load lyrics for ${track.path}", ex)
        return null
    }
}

val InvalidTrack =
    Track(
        -1,
        "",
        "",
        0,
        0,
        "<error>",
        listOf("<error>"),
        null,
        null,
        emptyList(),
        null,
        null,
        null,
        Duration.ZERO,
        0,
        "<error>",
        0,
        0,
        0,
        false,
        null,
        null,
        null,
        null,
    )

@Immutable
data class Album(
    val name: String,
    val albumArtist: String? = null,
    val year: Int? = null,
    val tracks: List<Track> = emptyList(),
) : Searchable, Sortable {
    val displayAlbumArtist
        get() = albumArtist ?: UNKNOWN

    @Transient override val searchableStrings = listOfNotNull(name, albumArtist)

    override val sortAlbum
        get() = name

    override val sortAlbumArtist
        get() = albumArtist ?: ""

    override val sortYear
        get() = year ?: 0

    companion object {
        val CollectionSortingOptions =
            mapOf(
                "Name" to
                    SortingOption(
                        R.string.sorting_name,
                        listOf(SortingKey.ALBUM, SortingKey.ALBUM_ARTIST, SortingKey.YEAR),
                    ),
                "Album artist" to
                    SortingOption(
                        R.string.sorting_album_artist,
                        listOf(SortingKey.ALBUM_ARTIST, SortingKey.ALBUM, SortingKey.YEAR),
                    ),
                "Year" to
                    SortingOption(
                        R.string.sorting_year,
                        listOf(SortingKey.YEAR, SortingKey.ALBUM_ARTIST, SortingKey.ALBUM),
                    ),
            )

        val TrackSortingOptions =
            mapOf(
                "Number" to
                    SortingOption(
                        R.string.sorting_number,
                        listOf(SortingKey.TRACK, SortingKey.ARTIST, SortingKey.TITLE),
                    ),
                "Title" to
                    SortingOption(
                        R.string.sorting_title,
                        listOf(SortingKey.TITLE, SortingKey.ARTIST, SortingKey.TRACK),
                    ),
                "Artist" to
                    SortingOption(
                        R.string.sorting_artist,
                        listOf(SortingKey.ARTIST, SortingKey.TITLE, SortingKey.TRACK),
                    ),
            )
    }
}

data class AlbumKey(val name: CaseInsensitiveString, val albumArtist: CaseInsensitiveString?) {
    /**
     * This field can't be replaced by [toString]. Since [toString] doesn't escape strings, two
     * different [AlbumKey]s could still theoretically collide.
     *
     * TODO: Find a less hacky hack
     */
    val composeKey =
        Base64.encode(name.string.toByteArray(Charsets.UTF_8)) +
            " " +
            (albumArtist?.string?.let { Base64.encode(it.toByteArray(Charsets.UTF_8)) } ?: "?")

    constructor(
        name: String,
        albumArtist: String?,
    ) : this(CaseInsensitiveString(name), albumArtist?.let { CaseInsensitiveString(it) })
}

val Track.albumKey: AlbumKey?
    get() = album?.let { AlbumKey(it, albumArtist) }
val Album.albumKey: AlbumKey
    get() = AlbumKey(name, albumArtist)

@Immutable
data class Artist(
    val name: String,
    val tracks: List<Track> = emptyList(),
    val albumSlices: List<AlbumSlice> = emptyList(),
) : Searchable, Sortable {
    @Stable
    val displayStatistics
        get() =
            Strings.separate(
                albumSlices.size
                    .takeIf { it != 0 }
                    ?.let { Strings[R.string.count_album].icuFormat(it) },
                Strings[R.string.count_track].icuFormat(tracks.size),
            )

    @Transient override val searchableStrings = listOf(name)

    override val sortArtist
        get() = name

    companion object {
        val CollectionSortingOptions =
            mapOf("Name" to SortingOption(R.string.sorting_name, listOf(SortingKey.ARTIST)))
        val TrackSortingOptions =
            mapOf(
                "Album" to
                    SortingOption(
                        R.string.sorting_album,
                        listOf(
                            SortingKey.ALBUM,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.YEAR,
                        ),
                    ),
                "Title" to
                    SortingOption(
                        R.string.sorting_title,
                        listOf(
                            SortingKey.TITLE,
                            SortingKey.YEAR,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                        ),
                    ),
                "Year" to
                    SortingOption(
                        R.string.sorting_year,
                        listOf(
                            SortingKey.YEAR,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                        ),
                    ),
            )
    }
}

@Immutable
data class AlbumArtist(
    val name: String,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
) : Searchable, Sortable {
    @Stable
    val displayStatistics
        get() =
            Strings.separate(
                Strings[R.string.count_album].icuFormat(albums.size),
                Strings[R.string.count_track].icuFormat(tracks.size),
            )

    @Transient override val searchableStrings = listOf(name)

    override val sortAlbumArtist
        get() = name

    companion object {
        val CollectionSortingOptions =
            mapOf("Name" to SortingOption(R.string.sorting_name, listOf(SortingKey.ALBUM_ARTIST)))
        val TrackSortingOptions =
            mapOf(
                "Album" to
                    SortingOption(
                        R.string.sorting_album,
                        listOf(
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.YEAR,
                        ),
                    ),
                "Title" to
                    SortingOption(
                        R.string.sorting_title,
                        listOf(
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.YEAR,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                        ),
                    ),
                "Artist" to
                    SortingOption(
                        R.string.sorting_artist,
                        listOf(
                            SortingKey.ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.YEAR,
                        ),
                    ),
                "Year" to
                    SortingOption(
                        R.string.sorting_year,
                        listOf(
                            SortingKey.YEAR,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                        ),
                    ),
            )
    }
}

@Immutable
data class Genre(
    val name: String,
    val tracks: List<Track> = emptyList(),
    val artistSlices: List<ArtistSlice> = emptyList(),
) : Searchable, Sortable {
    @Stable
    val displayStatistics
        get() = Strings[R.string.count_track].icuFormat(tracks.size)

    @Transient override val searchableStrings = listOf(name)

    override val sortGenre
        get() = name

    companion object {
        val CollectionSortingOptions =
            mapOf("Name" to SortingOption(R.string.sorting_name, listOf(SortingKey.GENRE)))
        val TrackSortingOptions =
            mapOf(
                "Title" to
                    SortingOption(
                        R.string.sorting_title,
                        listOf(
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.YEAR,
                        ),
                    ),
                "Artist" to
                    SortingOption(
                        R.string.sorting_artist,
                        listOf(
                            SortingKey.ARTIST,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.YEAR,
                        ),
                    ),
                "Album" to
                    SortingOption(
                        R.string.sorting_album,
                        listOf(
                            SortingKey.ALBUM,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                            SortingKey.YEAR,
                        ),
                    ),
                "Year" to
                    SortingOption(
                        R.string.sorting_year,
                        listOf(
                            SortingKey.YEAR,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.ARTIST,
                        ),
                    ),
            )
    }
}

@Immutable
data class Folder(
    val path: String,
    val fileName: String,
    val childFolders: List<String>,
    val childTracks: List<Track>,
    val childTracksCountRecursive: Int,
) : Searchable, Sortable {
    val displayStatistics
        get() =
            Strings.separate(
                childFolders.size
                    .takeIf { it != 0 }
                    ?.let { Strings[R.string.count_folder].icuFormat(it) },
                Strings[R.string.count_track].icuFormat(childTracksCountRecursive),
            )

    fun childTracksRecursive(folderIndex: Map<String, Folder>): List<Track> {
        val stack = mutableListOf(this)
        val tracks = mutableListOf<Track>()
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(0)
            stack.addAll(0, current.childFolders.mapNotNull { folderIndex[it] })
            tracks.addAll(current.childTracks)
        }
        return tracks
    }

    override val searchableStrings: List<String>
        get() = listOf(fileName)

    // Dummy sortable properties are required for compatibility with tracks.

    override val sortTitle
        get() = ""

    override val sortArtist
        get() = ""

    override val sortAlbum
        get() = ""

    override val sortAlbumArtist
        get() = ""

    override val sortDiscNumber
        get() = 0

    override val sortTrackNumber
        get() = 0

    override val sortGenre
        get() = ""

    override val sortYear
        get() = 0

    override val sortIsFolder
        get() = true

    override val sortFilename
        get() = fileName

    override val sortDateAdded
        get() = 0L

    override val sortDateModified
        get() = 0L

    companion object {
        val SortingOptions =
            mapOf(
                "File name" to
                    SortingOption(R.string.sorting_file_name, listOf(SortingKey.FILE_NAME))
            ) + Track.SortingOptions
    }
}

private data class MutableFolder(
    val path: String,
    val childFolders: MutableSet<String> = mutableSetOf(),
    val childTracks: MutableList<Track> = mutableListOf(),
    var childTracksCountRecursive: Int = 0,
) {
    fun toFolder(collator: Collator): Folder {
        return Folder(
            path,
            FilenameUtils.getName(path),
            childFolders
                .map { it to Folder(it, FilenameUtils.getName(it), emptyList(), emptyList(), 0) }
                .sortedBy(collator, Folder.SortingOptions.values.first().keys, true) { it.second }
                .map { it.first },
            childTracks.sorted(collator, Folder.SortingOptions.values.first().keys, true),
            childTracksCountRecursive,
        )
    }
}

@Immutable
data class AlbumSlice(val album: Album, val tracks: List<Track> = emptyList()) {
    companion object {
        val TrackSortingOptions =
            mapOf(
                "Number" to
                    SortingOption(
                        R.string.sorting_number,
                        listOf(SortingKey.TRACK, SortingKey.TITLE),
                    ),
                "Title" to
                    SortingOption(
                        R.string.sorting_title,
                        listOf(SortingKey.TITLE, SortingKey.TRACK),
                    ),
            )
    }
}

@Immutable
data class ArtistSlice(val artist: Artist, val tracks: List<Track> = emptyList()) {
    companion object {
        val TrackSortingOptions =
            mapOf(
                "Title" to
                    SortingOption(
                        R.string.sorting_title,
                        listOf(
                            SortingKey.TITLE,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.YEAR,
                        ),
                    ),
                "Album" to
                    SortingOption(
                        R.string.sorting_album,
                        listOf(
                            SortingKey.ALBUM,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                            SortingKey.YEAR,
                        ),
                    ),
                "Year" to
                    SortingOption(
                        R.string.sorting_year,
                        listOf(
                            SortingKey.YEAR,
                            SortingKey.ALBUM_ARTIST,
                            SortingKey.ALBUM,
                            SortingKey.TRACK,
                            SortingKey.TITLE,
                        ),
                    ),
            )
    }
}

val EmptyTrackIndex = UnfilteredTrackIndex(null, mapOf())

@Immutable
@Serializable
data class UnfilteredTrackIndex(val version: String?, val tracks: Map<Long, Track>)

@Immutable
data class LibraryIndex(
    val version: String?,
    val tracks: Map<Long, Track>,
    val albums: Map<AlbumKey, Album>,
    val artists: CaseInsensitiveMap<Artist>,
    val albumArtists: CaseInsensitiveMap<AlbumArtist>,
    val genres: CaseInsensitiveMap<Genre>,
    val folders: Map<String, Folder>,
    val rootFolder: String,
) {
    companion object {
        fun new(
            unfilteredTrackIndex: UnfilteredTrackIndex,
            collator: Collator,
            blacklist: List<Regex>,
            whitelist: List<Regex>,
        ): LibraryIndex {
            val tracks =
                unfilteredTrackIndex.tracks.filter { (_, track) ->
                    blacklist.none { it.containsMatchIn(track.path) } ||
                        whitelist.any { it.containsMatchIn(track.path) }
                }
            val albums = getAlbums(tracks.values, collator)
            val artists = getArtists(tracks.values, albums, collator)
            val albumArtists = getAlbumArtists(albums, collator)
            val genres = getGenres(tracks.values, artists, collator)
            val folders = getFolders(tracks.values, collator)
            val rootFolder = getRootFolder(folders)
            return LibraryIndex(
                unfilteredTrackIndex.version,
                tracks,
                albums,
                artists,
                albumArtists,
                genres,
                folders,
                rootFolder,
            )
        }

        private fun getAlbums(tracks: Collection<Track>, collator: Collator): Map<AlbumKey, Album> {
            return tracks
                .groupBy { if (it.album != null) AlbumKey(it.album, it.albumArtist) else null }
                .filter { it.key != null }
                .map { (_, tracks) ->
                    val sortedTracks =
                        tracks.sorted(collator, Album.TrackSortingOptions.values.first().keys, true)
                    val name = sortedTracks.mode { it.album!! }
                    val albumArtist = sortedTracks.mode { it.albumArtist }
                    AlbumKey(name, albumArtist) to
                        Album(name, albumArtist, sortedTracks.mode { it.year }, sortedTracks)
                }
                .toMap()
        }

        private fun getArtists(
            tracks: Collection<Track>,
            albums: Map<AlbumKey, Album>,
            collator: Collator,
        ): CaseInsensitiveMap<Artist> {
            return tracks
                .flatMap { it.artists }
                .distinctCaseInsensitive()
                .associateWith { name ->
                    val artistTracks =
                        tracks
                            .filter { track -> track.artists.any { it.equals(name, true) } }
                            .sorted(collator, Artist.TrackSortingOptions.values.first().keys, true)
                    val albumSlices =
                        artistTracks
                            .groupBy {
                                if (it.album != null) AlbumKey(it.album, it.albumArtist) else null
                            }
                            .filter { it.key != null }
                            .map {
                                AlbumSlice(
                                    albums[it.key]!!,
                                    it.value.sorted(
                                        collator,
                                        Album.TrackSortingOptions.values.first().keys,
                                        true,
                                    ),
                                )
                            }
                            .sortedBy(
                                collator,
                                Album.CollectionSortingOptions.values.first().keys,
                                true,
                            ) {
                                it.album
                            }
                    Artist(name, artistTracks, albumSlices)
                }
                .let { CaseInsensitiveMap.noMerge(it) }
        }

        private fun getAlbumArtists(
            albums: Map<AlbumKey, Album>,
            collator: Collator,
        ): CaseInsensitiveMap<AlbumArtist> {
            return albums.values
                .mapNotNull { it.albumArtist }
                .distinctCaseInsensitive()
                .associateWith { name ->
                    val albumArtistAlbums =
                        albums.values
                            .filter { it.albumArtist.equals(name, true) }
                            .sorted(
                                collator,
                                Album.CollectionSortingOptions.values.first().keys,
                                true,
                            )
                    val albumArtistTracks = albumArtistAlbums.flatMap { it.tracks }
                    AlbumArtist(name, albumArtistTracks, albumArtistAlbums)
                }
                .let { CaseInsensitiveMap.noMerge(it) }
        }

        private fun getGenres(
            tracks: Collection<Track>,
            artists: CaseInsensitiveMap<Artist>,
            collator: Collator,
        ): CaseInsensitiveMap<Genre> {
            return tracks
                .flatMap { it.genres }
                .distinctCaseInsensitive()
                .associateWith { name ->
                    val genreTracks =
                        tracks
                            .filter { track -> track.genres.any { it.equals(name, true) } }
                            .sorted(collator, Genre.TrackSortingOptions.values.first().keys, true)
                    val artistSlices =
                        artists.values
                            .map { artist ->
                                ArtistSlice(
                                    artist,
                                    artist.tracks
                                        .filter { track ->
                                            track.genres.any { it.equals(name, true) }
                                        }
                                        .sorted(
                                            collator,
                                            Artist.TrackSortingOptions.values.first().keys,
                                            true,
                                        ),
                                )
                            }
                            .filter { it.tracks.isNotEmpty() }
                            .sortedBy(
                                collator,
                                Artist.CollectionSortingOptions.values.first().keys,
                                true,
                            ) {
                                it.artist
                            }
                    Genre(name, genreTracks, artistSlices)
                }
                .let { CaseInsensitiveMap.noMerge(it) }
        }

        private fun getFolders(tracks: Collection<Track>, collator: Collator): Map<String, Folder> {
            val folders = mutableMapOf<String, MutableFolder>("" to MutableFolder(""))
            tracks.sorted(collator, Folder.SortingOptions.values.first().keys, true).forEach { track
                ->
                val parentPath = FilenameUtils.getPathNoEndSeparator(track.path)
                val parentFolder = folders.getOrPut(parentPath) { MutableFolder(parentPath) }
                parentFolder.childTracks.add(track)
            }
            for (path in folders.keys.toMutableList()) {
                var currentPath = path
                var parentPath = FilenameUtils.getPathNoEndSeparator(path)
                while (currentPath.isNotEmpty()) {
                    val parentFolderExists = folders.containsKey(parentPath)
                    val parentFolder = folders.getOrPut(parentPath) { MutableFolder(parentPath) }
                    parentFolder.childFolders.add(currentPath)
                    if (parentFolderExists) break
                    currentPath = parentPath
                    parentPath = FilenameUtils.getPathNoEndSeparator(parentPath)
                }
            }
            for ((path, folder) in folders) {
                folder.childTracksCountRecursive += folder.childTracks.size
                var currentPath = path
                while (currentPath.isNotEmpty()) {
                    val parentPath = FilenameUtils.getPathNoEndSeparator(currentPath)
                    folders[parentPath]!!.childTracksCountRecursive += folder.childTracks.size
                    currentPath = parentPath
                }
            }
            return folders.mapValues { it.value.toFolder(collator) }
        }

        private fun getRootFolder(folders: Map<String, Folder>): String {
            var root = ""
            while (true) {
                val folder = folders[root]!!
                if (folder.childFolders.size != 1 || folder.childTracks.isNotEmpty()) break
                root = folder.childFolders[0]
            }
            return root
        }
    }
}

private val contentResolverColumns =
    arrayOf(
        Media._ID,
        Media.DATA,
        Media.DATE_ADDED,
        Media.DATE_MODIFIED,
        Media.TITLE,
        Media.ARTIST,
        Media.ALBUM,
        Media.ALBUM_ARTIST,
        Media.GENRE,
        Media.YEAR,
        Media.TRACK,
        Media.DISC_NUMBER,
        Media.DURATION,
        Media.SIZE,
        Media.BITRATE,
    )

fun scanTracks(
    context: Context,
    advancedMetadataExtraction: Boolean,
    disableArtworkColorExtraction: Boolean,
    old: UnfilteredTrackIndex?,
    artistSeparators: List<String>,
    artistSeparatorExceptions: List<String>,
    genreSeparators: List<String>,
    genreSeparatorExceptions: List<String>,
    onProgressReport: (Int, Int) -> Unit,
): UnfilteredTrackIndex? {
    if (
        ContextCompat.checkSelfPermission(context, READ_PERMISSION) ==
            PackageManager.PERMISSION_DENIED
    )
        return null
    val libraryVersion = MediaStore.getVersion(context)

    val query =
        context.contentResolver.query(
            Media.EXTERNAL_CONTENT_URI,
            contentResolverColumns,
            "${Media.IS_MUSIC} AND NOT ${Media.IS_DRM} AND NOT ${Media.IS_TRASHED}",
            null,
            "${Media._ID} ASC",
        )
    val tracks = mutableListOf<Track>()

    query?.use { cursor ->
        val ci = contentResolverColumns.associateWith { cursor.getColumnIndexOrThrow(it) }
        while (cursor.moveToNext()) {
            onProgressReport(cursor.position, cursor.count)
            val id = cursor.getLong(ci[Media._ID]!!)
            val trackVersion = cursor.getLong(ci[Media.DATE_MODIFIED]!!)
            val oldIndex = old?.tracks?.get(id)

            tracks +=
                if (oldIndex?.version == trackVersion) {
                    oldIndex
                } else {
                    // Android MediaStore doesn't recognize multiple fields and some text encodings,
                    // so it's necessary to use a capable third-party library.
                    // This procedure is however ridiculously slow and has its own bugs,
                    // so MediaStore data should be retrieved as a fallback.

                    val path =
                        cursor
                            .getString(ci[Media.DATA]!!)
                            .trimAndNormalize()
                            .let { FilenameUtils.normalize(it) }
                            .let { FilenameUtils.separatorsToUnix(it) }
                    val fileName = FilenameUtils.getName(path)
                    val dateAdded = cursor.getLong(ci[Media.DATE_ADDED]!!)
                    var title = cursor.getStringOrNull(ci[Media.TITLE]!!)?.trimAndNormalize()
                    var artists =
                        listOfNotNull(
                            cursor.getStringOrNull(ci[Media.ARTIST]!!)?.trimAndNormalize()
                        )
                    var album = cursor.getStringOrNull(ci[Media.ALBUM]!!)?.trimAndNormalize()
                    var albumArtist =
                        cursor.getStringOrNull(ci[Media.ALBUM_ARTIST]!!)?.trimAndNormalize()
                    var genres =
                        listOfNotNull(cursor.getStringOrNull(ci[Media.GENRE]!!)?.trimAndNormalize())
                    var year = cursor.getIntOrNull(ci[Media.YEAR]!!)
                    // https://developer.android.com/reference/android/provider/MediaStore.Audio.AudioColumns.html#TRACK
                    var trackNumber = cursor.getIntOrNull(ci[Media.TRACK]!!)?.let { it % 1000 }
                    var discNumber = cursor.getIntOrNull(ci[Media.DISC_NUMBER]!!)
                    val size = cursor.getLong(ci[Media.SIZE]!!)
                    var format = UNKNOWN
                    var sampleRate = 0
                    val bitRate = cursor.getLongOrNull(ci[Media.BITRATE]!!) ?: 0
                    var bitDepth = 0
                    var unsyncedLyrics = null as String?
                    var comment = null as String?

                    if (advancedMetadataExtraction) {
                        try {
                            val extension = FilenameUtils.getExtension(path).lowercase()
                            val file =
                                try {
                                    AudioFileIO.read(File(path))
                                } catch (ex: CannotReadException) {
                                    when (extension) {
                                        "oga" ->
                                            try {
                                                AudioFileIO.readAs(File(path), "ogg")
                                            } catch (_: CannotReadException) {
                                                try {
                                                    AudioFileIO.readAs(File(path), "opus")
                                                } catch (_: Exception) {
                                                    throw ex
                                                }
                                            }

                                        "ogg" -> AudioFileIO.readAs(File(path), "opus")
                                        else -> throw ex
                                    }
                                }
                            try {
                                title = file.tag.getFirst(FieldKey.TITLE)
                            } catch (_: KeyNotFoundException) {}
                            try {
                                artists =
                                    file.tag
                                        .getFields(FieldKey.ARTIST)
                                        .filter { !it.isBinary }
                                        .map { (it as TagTextField).content }
                            } catch (_: KeyNotFoundException) {}
                            try {
                                album = file.tag.getFirst(FieldKey.ALBUM)
                            } catch (_: KeyNotFoundException) {}
                            try {
                                albumArtist = file.tag.getFirst(FieldKey.ALBUM_ARTIST)
                            } catch (_: KeyNotFoundException) {}
                            try {
                                genres =
                                    file.tag
                                        .getFields(FieldKey.GENRE)
                                        .filter { !it.isBinary }
                                        .map { (it as TagTextField).content }
                            } catch (_: KeyNotFoundException) {}
                            try {
                                year = file.tag.getFirst(FieldKey.YEAR).toIntOrNull()
                            } catch (_: KeyNotFoundException) {}
                            try {
                                trackNumber = file.tag.getFirst(FieldKey.TRACK).toIntOrNull()
                            } catch (_: KeyNotFoundException) {}
                            try {
                                discNumber = file.tag.getFirst(FieldKey.DISC_NO).toIntOrNull()
                            } catch (_: KeyNotFoundException) {}
                            try {
                                unsyncedLyrics =
                                    file.tag.getFirst(FieldKey.LYRICS).takeIf { it.isNotEmpty() }
                            } catch (_: KeyNotFoundException) {}
                            try {
                                comment =
                                    file.tag.getFirst(FieldKey.COMMENT).takeIf { it.isNotEmpty() }
                            } catch (_: KeyNotFoundException) {}
                            format = file.audioHeader.format
                            sampleRate = file.audioHeader.sampleRateAsNumber
                            bitDepth = file.audioHeader.bitsPerSample
                        } catch (ex: Exception) {
                            Log.e("Phocid", "Error reading extended metadata for $path", ex)
                        }
                    }

                    // In some cases, missing fields will be masqueraded as empty strings
                    title = title?.takeIf { it.isNotEmpty() }?.trimAndNormalize()
                    artists =
                        splitArtists(title, artists, artistSeparators, artistSeparatorExceptions)
                    album = album?.takeIf { it.isNotEmpty() }?.trimAndNormalize()
                    albumArtist = albumArtist?.takeIf { it.isNotEmpty() }?.trimAndNormalize()
                    genres = splitGenres(genres, genreSeparators, genreSeparatorExceptions)

                    val palette =
                        if (disableArtworkColorExtraction) null
                        else
                            loadArtwork(context, id, 64)
                                ?.let { Palette.from(it) }
                                ?.clearTargets()
                                ?.apply {
                                    addTarget(Target.VIBRANT)
                                    addTarget(Target.MUTED)
                                }
                                ?.generate()
                    val vibrantColor =
                        palette?.getSwatchForTarget(Target.VIBRANT)?.rgb?.let { Color(it) }
                    val mutedColor =
                        palette?.getSwatchForTarget(Target.MUTED)?.rgb?.let { Color(it) }

                    Track(
                        id,
                        path,
                        fileName,
                        dateAdded,
                        trackVersion,
                        title,
                        artists,
                        album,
                        albumArtist,
                        genres,
                        year,
                        trackNumber,
                        discNumber,
                        cursor.getInt(ci[Media.DURATION]!!).toDuration(DurationUnit.MILLISECONDS),
                        size,
                        format,
                        sampleRate,
                        bitRate,
                        bitDepth,
                        palette != null || disableArtworkColorExtraction,
                        vibrantColor,
                        mutedColor,
                        unsyncedLyrics,
                        comment,
                    )
                }
        }
    }
    return UnfilteredTrackIndex(libraryVersion, tracks.associateBy { it.id })
}

private val featuringArtistInTitleRegex =
    Regex("""[( ](feat|ft)\. *(?<artist>.+?)(\(|\)|$)""", RegexOption.IGNORE_CASE)

/**
 * Apparently people invent all kinds of workarounds to represent multiple artists. And JAudioTagger
 * "intelligently" replaces some delimiters with null characters.
 *
 * Also there are mysterious trailing whitespaces.
 */
private fun splitArtists(
    title: String?,
    artists: Iterable<String>,
    separators: Collection<String>,
    exceptions: Iterable<String>,
): List<String> {
    val exceptionSurrogates =
        exceptions.take(6400).mapIndexed { index, exception ->
            exception to (0xe000 + index).toChar().toString()
        }
    fun String.replaceExceptions(): String {
        var replaced = this
        exceptionSurrogates.forEach { (exception, surrogate) ->
            replaced = replaced.replace(exception, surrogate, ignoreCase = true)
        }
        return replaced
    }
    fun String.restoreExceptions(): String {
        var restored = this
        exceptionSurrogates.forEach { (exception, surrogate) ->
            restored = restored.replace(surrogate, exception)
        }
        return restored
    }

    val featuringArtistInTitle =
        title?.let {
            featuringArtistInTitleRegex
                .find(it.replaceExceptions())
                ?.groups
                ?.get("artist")
                ?.value
                ?.restoreExceptions()
        }
    return (artists + listOfNotNull(featuringArtistInTitle))
        .flatMap { string ->
            string
                .replaceExceptions()
                .split(*(arrayOf("\u0000") + separators), ignoreCase = true)
                .map { it.restoreExceptions() }
        }
        .map { it.trimAndNormalize() }
        .filter { it.isNotEmpty() }
        .distinctCaseInsensitive()
}

private fun splitGenres(
    genres: Iterable<String>,
    separators: Collection<String>,
    exceptions: Iterable<String>,
): List<String> {
    val exceptionSurrogates =
        exceptions.take(6400).mapIndexed { index, exception ->
            exception to (0xe000 + index).toChar().toString()
        }
    fun String.replaceExceptions(): String {
        var replaced = this
        exceptionSurrogates.forEach { (exception, surrogate) ->
            replaced = replaced.replace(exception, surrogate, ignoreCase = true)
        }
        return replaced
    }
    fun String.restoreExceptions(): String {
        var restored = this
        exceptionSurrogates.forEach { (exception, surrogate) ->
            restored = restored.replace(surrogate, exception)
        }
        return restored
    }

    return genres
        .flatMap { string ->
            string
                .replaceExceptions()
                .split(*(arrayOf("\u0000") + separators), ignoreCase = true)
                .map { it.restoreExceptions() }
        }
        .map { it.trimAndNormalize() }
        .filter { it.isNotEmpty() }
        .distinctCaseInsensitive()
}

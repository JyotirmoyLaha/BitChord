package com.music.bitchord.data.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.TimeZone

/*
 * The rest of the signed-in account, past the playlists [SpotifyLibrary] reads:
 * the home feed, saved albums, followed artists, catalogue search, and album
 * and artist pages.
 *
 * All of it goes through the web player's own GraphQL endpoint, the one the
 * playlists already use. Spotify's public REST API (api.spotify.com/v1) was
 * tried first and answers this account's web-player token with 429 on every
 * call, so it is not an option.
 *
 * Each operation is pinned to the persisted-query hash the web player sends
 * for it, read out of open.spotify.com's JavaScript (November 2026). Spotify
 * replaces these with web player releases; when one stops answering, its
 * section shows the error and the hash here needs refreshing from the current
 * bundle — search it for `"<operationName>","query","<hash>"`.
 *
 * Nothing here plays from Spotify: every track is matched to YouTube Music the
 * way a playlist's are (see MainViewModel.openSpotifyPage).
 */

/** A page id for a Spotify album: this plus the album id. */
const val SPOTIFY_ALBUM_PREFIX = "spotify:album:"

/** A page id for a Spotify artist's top tracks: this plus the artist id. */
const val SPOTIFY_ARTIST_PREFIX = "spotify:artist:"

/** Every Spotify page id starts with this; see [SPOTIFY_PAGE_PREFIX] and the ids above. */
const val SPOTIFY_ANY_PREFIX = "spotify:"

enum class SpotifyKind { TRACK, ALBUM, ARTIST, PLAYLIST }

/** One card on a shelf or one search hit: what it is, and enough to show and open it. */
data class SpotifyItem(
    val kind: SpotifyKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    /** Only for [SpotifyKind.TRACK]: what matching it to YouTube Music needs. */
    val track: SpotifyTrack? = null,
)

/** One row of the home feed, with Spotify's own title for it. */
data class SpotifyHomeSection(val title: String, val items: List<SpotifyItem>)

data class SpotifySearchResult(
    val tracks: List<SpotifyItem>,
    val albums: List<SpotifyItem>,
    val artists: List<SpotifyItem>,
    val playlists: List<SpotifyItem>,
)

object SpotifyWeb {
    private const val HOME = "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1"
    private const val SEARCH = "eef7cc54888d91bdd6802623477873caa3948ae173a0c34fd86827b267e94c03"
    private const val ALBUM = "0b6ce3511e1b02e4a40fa95440f992acbe85080eb308af9194322d708fc1914f"
    private const val ARTIST = "eafb1620d783ece118e72fbfede2b0064483e029d3c1aafafa1c2de0e25a89e7"

    /** The account's home page: Made For You, recents, mixes — whatever Spotify puts there. */
    suspend fun home(): List<SpotifyHomeSection> {
        val root = SpotifyLibrary.query(
            "home",
            HOME,
            buildJsonObject {
                put("timeZone", TimeZone.getDefault().id)
                put("sp_t", "")
                put("facet", "")
                put("sectionItemsLimit", 15)
                put("includeHomeChapterVideoCards", false)
                put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
                put("includeEpisodeContentRatingsV2", false)
            },
        )
        // The page's sections are the objects carrying `sectionItems`; found by
        // shape rather than by a fixed path, which Spotify has moved before.
        val sections = mutableListOf<JsonObject>()
        collect(root["data"]) { it.containsKey("sectionItems") }.forEach(sections::add)
        return sections.mapNotNull { section ->
            val title = section.obj("data")?.obj("title")?.let { it.str("transformedLabel") ?: it.str("text") }
                ?: return@mapNotNull null
            val items = section.obj("sectionItems")?.arr("items").objects().mapNotNull(::parseItem)
            if (title.isBlank() || items.isEmpty()) null else SpotifyHomeSection(title, items)
        }
    }

    suspend fun savedAlbums(): List<SpotifyItem> =
        SpotifyLibrary.libraryItems("Albums").mapNotNull(::parseItem).filter { it.kind == SpotifyKind.ALBUM }

    suspend fun followedArtists(): List<SpotifyItem> =
        SpotifyLibrary.libraryItems("Artists").mapNotNull(::parseItem).filter { it.kind == SpotifyKind.ARTIST }

    suspend fun search(query: String): SpotifySearchResult {
        val root = SpotifyLibrary.query(
            "searchDesktop",
            SEARCH,
            buildJsonObject {
                put("searchTerm", query)
                put("offset", 0)
                put("limit", 10)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", false)
                put("includeLocalConcertsField", false)
                put("includeAuthors", false)
            },
        )
        val search = root.obj("data")?.obj("searchV2")
            ?: throw IllegalStateException("Spotify search response was empty")
        fun section(vararg keys: String, kind: SpotifyKind): List<SpotifyItem> =
            keys.firstNotNullOfOrNull { search.obj(it) }?.arr("items").objects()
                .mapNotNull(::parseItem).filter { it.kind == kind }
        return SpotifySearchResult(
            tracks = section("tracksV2", "tracks", kind = SpotifyKind.TRACK),
            albums = section("albumsV2", "albums", kind = SpotifyKind.ALBUM),
            artists = section("artists", kind = SpotifyKind.ARTIST),
            playlists = section("playlists", kind = SpotifyKind.PLAYLIST),
        )
    }

    /** An album's cover and its tracks. */
    suspend fun album(albumId: String): Pair<String?, List<SpotifyTrack>> {
        val root = SpotifyLibrary.query(
            "getAlbum",
            ALBUM,
            buildJsonObject {
                put("uri", "spotify:album:$albumId")
                put("locale", "")
                put("offset", 0)
                put("limit", 50)
            },
        )
        val album = root.obj("data")?.obj("albumUnion")
            ?: throw IllegalStateException("Spotify album response was empty")
        val cover = largest(album.obj("coverArt"))
        val name = album.str("name")
        val list = (album.obj("tracksV2") ?: album.obj("tracks"))?.arr("items").objects().mapNotNull { entry ->
            val track = entry.obj("track") ?: entry.obj("item")?.obj("data") ?: entry
            parseTrack(track, albumName = name, albumCover = cover)
        }
        return cover to list
    }

    /** An artist's picture and most popular tracks. */
    suspend fun artist(artistId: String): Pair<String?, List<SpotifyTrack>> {
        val root = SpotifyLibrary.query(
            "queryArtistOverview",
            ARTIST,
            buildJsonObject {
                put("uri", "spotify:artist:$artistId")
                put("locale", "")
                put("includePrerelease", true)
                put("preReleaseV2", false)
                put("includeMusicVideoPreviews", false)
            },
        )
        val artist = root.obj("data")?.obj("artistUnion")
            ?: throw IllegalStateException("Spotify artist response was empty")
        val picture = largest(artist.obj("visuals")?.obj("avatarImage"))
        val list = artist.obj("discography")?.obj("topTracks")?.arr("items").objects().mapNotNull { entry ->
            parseTrack(entry.obj("track") ?: entry, albumName = null, albumCover = null)
        }
        return picture to list
    }

    // ---- Parsing ----

    /**
     * Any card the web player sends — a home shelf entry, a search hit, a
     * library row — down to what it is. They come wrapped to varying depths
     * (`item`, `content`, `data`), so this peels whichever wrappers are there
     * and decides the kind from the entity's own uri.
     */
    private fun parseItem(raw: JsonObject): SpotifyItem? {
        var node = raw
        while (true) node = node.obj("item") ?: node.obj("content") ?: break
        // The wrapper sometimes holds the uri while its `data` holds the fields.
        val data = node.obj("data") ?: node
        val uri = data.str("uri") ?: node.str("_uri") ?: node.str("uri") ?: raw.str("uri") ?: raw.str("_uri")
            ?: return null
        val id = uri.substringAfterLast(":")
        return when {
            uri.startsWith("spotify:track:") -> {
                val track = parseTrack(data, null, null) ?: return null
                SpotifyItem(SpotifyKind.TRACK, id, track.title, track.artist, track.imageUrl, track)
            }
            uri.startsWith("spotify:album:") -> SpotifyItem(
                SpotifyKind.ALBUM,
                id,
                data.str("name") ?: return null,
                artistNames(data.obj("artists")),
                largest(data.obj("coverArt")),
            )
            uri.startsWith("spotify:artist:") -> SpotifyItem(
                SpotifyKind.ARTIST,
                id,
                data.obj("profile")?.str("name") ?: data.str("name") ?: return null,
                "",
                largest(data.obj("visuals")?.obj("avatarImage")),
            )
            uri.startsWith("spotify:playlist:") -> SpotifyItem(
                SpotifyKind.PLAYLIST,
                id,
                data.str("name") ?: return null,
                data.obj("ownerV2")?.obj("data")?.str("name").orEmpty(),
                largest(data.obj("images")?.arr("items").objects().firstOrNull()),
            )
            else -> null
        }
    }

    private fun parseTrack(o: JsonObject, albumName: String?, albumCover: String?): SpotifyTrack? {
        val uri = o.str("uri") ?: o.str("_uri") ?: return null
        if (!uri.startsWith("spotify:track:")) return null
        val title = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val album = o.obj("albumOfTrack")
        return SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = artistNames(o.obj("artists")),
            album = album?.str("name") ?: albumName,
            durationMs = (o.obj("duration") ?: o.obj("trackDuration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = largest(album?.obj("coverArt")) ?: albumCover,
        )
    }

    private fun artistNames(artists: JsonObject?): String =
        artists?.arr("items").objects()
            .mapNotNull { it.obj("profile")?.str("name") ?: it.str("name") }
            .filter { it.isNotBlank() }
            .joinToString(", ")

    /** The widest of an image's `sources`; Spotify lists them in no fixed order. */
    private fun largest(image: JsonObject?): String? =
        image?.arr("sources").objects()
            .mapNotNull { s -> s.str("url")?.let { it to (s.int("width") ?: 0) } }
            .maxByOrNull { it.second }?.first

    /** Every object under [element], at any depth, that [match] accepts — outermost first, not inside a match. */
    private fun collect(element: JsonElement?, match: (JsonObject) -> Boolean): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(e: JsonElement?) {
            when (e) {
                is JsonObject -> if (match(e)) out += e else e.values.forEach(::walk)
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(element)
        return out
    }

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key)?.takeIf { it !is JsonNull } as? JsonObject
    private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key)?.takeIf { it !is JsonNull } as? JsonArray
    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonArray?.objects(): List<JsonObject> = this.orEmpty().mapNotNull { it as? JsonObject }
}

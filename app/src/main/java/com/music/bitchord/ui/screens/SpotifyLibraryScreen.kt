package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SPOTIFY_ALBUM_PREFIX
import com.music.bitchord.data.spotify.SPOTIFY_ARTIST_PREFIX
import com.music.bitchord.data.spotify.SpotifyHomeSection
import com.music.bitchord.data.spotify.SpotifyItem
import com.music.bitchord.data.spotify.SpotifyKind
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifySearchResult
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.spotify.SpotifyWeb
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** One section's contents, or why Spotify wouldn't give them. */
private data class SpotifyShelf<T>(val items: List<T> = emptyList(), val error: String? = null, val loading: Boolean = true)

/**
 * The signed-in Spotify account as a home page: search, Spotify's own home
 * feed for it, its saved albums and followed artists, and its playlists.
 *
 * Every tap ends on an ordinary page or play: albums and artists open through
 * [onOpenPage] with a `spotify:` id, which the detail page resolves and
 * matches to YouTube Music; playlists through [onOpenPlaylist]; a single track
 * goes to [onPlayTrack].
 */
@Composable
fun SpotifyLibraryScreen(
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onOpenPage: (browseId: String, title: String, subtitle: String, imageUrl: String?) -> Unit,
    onPlayTrack: (SpotifyTrack) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTokenSetup: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    var home by remember { mutableStateOf(SpotifyShelf<SpotifyHomeSection>()) }
    var albums by remember { mutableStateOf(SpotifyShelf<SpotifyItem>()) }
    var artists by remember { mutableStateOf(SpotifyShelf<SpotifyItem>()) }
    var playlists by remember { mutableStateOf(SpotifyShelf<SpotifyPlaylist>()) }

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SpotifySearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cookie) {
        if (cookie.isBlank()) return@LaunchedEffect
        // Each section on its own: one Spotify refuses must not blank the rest.
        coroutineScope {
            suspend fun <T> load(block: suspend () -> List<T>): SpotifyShelf<T> =
                runCatching { block() }.fold(
                    onSuccess = { SpotifyShelf(it, loading = false) },
                    onFailure = { SpotifyShelf(error = it.message, loading = false) },
                )
            launch { home = load { SpotifyWeb.home() } }
            launch { albums = load { SpotifyWeb.savedAlbums() } }
            launch { artists = load { SpotifyWeb.followedArtists() } }
            launch { playlists = load { SpotifyLibrary.playlists() } }
        }
    }

    LaunchedEffect(query, cookie) {
        val q = query.trim()
        if (q.isEmpty() || cookie.isBlank()) {
            results = null
            searchError = null
            searching = false
            return@LaunchedEffect
        }
        // Typing restarts this effect, so only a pause actually searches.
        delay(400)
        searching = true
        searchError = null
        runCatching { SpotifyWeb.search(q) }
            .onSuccess { results = it }
            .onFailure { searchError = it.message }
        searching = false
    }

    val spotify = stringResource(R.string.spotify)
    val open: (SpotifyItem) -> Unit = { item ->
        when (item.kind) {
            SpotifyKind.TRACK -> item.track?.let(onPlayTrack)
            SpotifyKind.ALBUM -> onOpenPage(SPOTIFY_ALBUM_PREFIX + item.id, item.title, item.subtitle, item.imageUrl)
            SpotifyKind.ARTIST -> onOpenPage(SPOTIFY_ARTIST_PREFIX + item.id, item.title, spotify, item.imageUrl)
            SpotifyKind.PLAYLIST -> onOpenPlaylist(
                SpotifyPlaylist(id = item.id, name = item.title, owner = item.subtitle.ifBlank { null }, imageUrl = item.imageUrl),
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.spotify_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = spotify,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        if (cookie.isBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = stringResource(R.string.spotify_connect_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenLogin) {
                        Text(stringResource(R.string.spotify_sign_in))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenTokenSetup) {
                        Text(stringResource(R.string.spotify_use_spdc_token))
                    }
                }
            }
            return@LazyColumn
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.spotify_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = null)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (query.isNotBlank()) {
            searchResults(results, searching, searchError, open)
            return@LazyColumn
        }

        // Spotify's own home rows, under Spotify's own titles.
        sectionState(home, emptyRes = null)
        itemsIndexed(home.items, key = { index, section -> "home:$index:${section.title}" }) { _, section ->
            Column {
                SpotifySectionHeader(section.title)
                ItemRow(section.items, open)
            }
        }

        shelf(R.string.spotify_saved_albums, albums, open)
        shelf(R.string.spotify_followed_artists, artists, open)

        item { SpotifySectionHeader(stringResource(R.string.spotify_your_playlists)) }
        sectionState(playlists, emptyRes = R.string.spotify_empty_playlists)
        items(playlists.items, key = { "pl:" + it.id }) { item ->
            LibraryRow(
                title = item.name,
                subtitle = item.owner.orEmpty(),
                imageUrl = item.imageUrl,
                onClick = { onOpenPlaylist(item) },
            )
        }
    }
}

// ---- Sections ----

private fun LazyListScope.shelf(titleRes: Int, shelf: SpotifyShelf<SpotifyItem>, onOpen: (SpotifyItem) -> Unit) {
    // A section that loaded and came back empty is left out rather than shown blank.
    if (!shelf.loading && shelf.error == null && shelf.items.isEmpty()) return
    item(key = "h:$titleRes") { SpotifySectionHeader(stringResource(titleRes)) }
    sectionState(shelf, emptyRes = null)
    if (shelf.items.isNotEmpty()) {
        item(key = "r:$titleRes") { ItemRow(shelf.items, onOpen) }
    }
}

private fun LazyListScope.sectionState(shelf: SpotifyShelf<*>, emptyRes: Int?) {
    when {
        shelf.loading -> item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        shelf.error != null -> item {
            Text(
                text = shelf.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        shelf.items.isEmpty() && emptyRes != null -> item {
            Text(
                text = stringResource(emptyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

// ---- Search ----

private fun LazyListScope.searchResults(
    results: SpotifySearchResult?,
    searching: Boolean,
    error: String?,
    onOpen: (SpotifyItem) -> Unit,
) {
    if (searching && results == null) {
        item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    if (error != null) {
        item {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        return
    }
    val r = results ?: return
    if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.playlists.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.spotify_no_results),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
        return
    }
    if (r.tracks.isNotEmpty()) {
        item { SpotifySectionHeader(stringResource(R.string.spotify_section_songs)) }
        items(r.tracks, key = { "st:" + it.id }) { track ->
            LibraryRow(track.title, track.subtitle, track.imageUrl) { onOpen(track) }
        }
    }
    if (r.artists.isNotEmpty()) {
        item { SpotifySectionHeader(stringResource(R.string.spotify_section_artists)) }
        item { ItemRow(r.artists, onOpen) }
    }
    if (r.albums.isNotEmpty()) {
        item { SpotifySectionHeader(stringResource(R.string.spotify_section_albums)) }
        item { ItemRow(r.albums, onOpen) }
    }
    if (r.playlists.isNotEmpty()) {
        item { SpotifySectionHeader(stringResource(R.string.spotify_section_playlists)) }
        items(r.playlists, key = { "sp:" + it.id }) { playlist ->
            LibraryRow(playlist.title, playlist.subtitle, playlist.imageUrl) { onOpen(playlist) }
        }
    }
}

// ---- Pieces ----

@Composable
private fun SpotifySectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun ItemRow(items: List<SpotifyItem>, onOpen: (SpotifyItem) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items, key = { index, item -> "${item.kind}:${item.id}:$index" }) { _, item ->
            SpotifyCard(item) { onOpen(item) }
        }
    }
}

@Composable
private fun SpotifyCard(item: SpotifyItem, onClick: () -> Unit) {
    val shape = if (item.kind == SpotifyKind.ARTIST) CircleShape else RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(132.dp).clip(shape),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

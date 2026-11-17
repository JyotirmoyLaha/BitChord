package com.music.bitchord.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.Calendar
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** One section's contents, or why Spotify wouldn't give them. */
private data class SpotifyShelf<T>(val items: List<T> = emptyList(), val error: String? = null, val loading: Boolean = true)

private val SpotifyGreen = Color(0xFF1ED760)
private val OnSpotifyGreen = Color(0xFF000000)

/** The three pages Spotify's own app splits itself into. */
private enum class SpotifyTab { HOME, SEARCH, LIBRARY }

/**
 * The signed-in Spotify account laid out the way Spotify's app is: a Home page
 * with shortcuts and Spotify's own home rows, a Search page with genres to
 * browse, and Your Library with its playlists, albums and artists. The three
 * are separate pages under one tab row and can be swiped between.
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
    if (cookie.isBlank()) {
        SpotifyWelcome(onOpenLogin, onOpenTokenSetup, contentPadding, modifier)
        return
    }

    var home by remember { mutableStateOf(SpotifyShelf<SpotifyHomeSection>()) }
    var albums by remember { mutableStateOf(SpotifyShelf<SpotifyItem>()) }
    var artists by remember { mutableStateOf(SpotifyShelf<SpotifyItem>()) }
    var playlists by remember { mutableStateOf(SpotifyShelf<SpotifyPlaylist>()) }

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SpotifySearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cookie) {
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
        if (q.isEmpty()) {
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
    // Playlists come from a different endpoint; as cards they're ordinary items.
    val playlistItems = remember(playlists.items) {
        playlists.items.map {
            SpotifyItem(SpotifyKind.PLAYLIST, it.id, it.name, it.owner.orEmpty(), it.imageUrl)
        }
    }

    var savedTab by rememberSaveable { mutableIntStateOf(0) }
    val pager = rememberPagerState(initialPage = savedTab) { SpotifyTab.entries.size }
    LaunchedEffect(pager.currentPage) { savedTab = pager.currentPage }
    val scope = rememberCoroutineScope()

    val topInset = contentPadding.calculateTopPadding()
    val bottomInset = contentPadding.calculateBottomPadding()
    val tabsHeight = 52.dp
    val pagePadding = PaddingValues(top = topInset + tabsHeight, bottom = bottomInset)

    Box(modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { it },
        ) { page ->
            when (SpotifyTab.entries[page]) {
                SpotifyTab.HOME -> HomeTab(
                    home = home,
                    playlists = playlists,
                    playlistItems = playlistItems,
                    albums = albums,
                    onOpen = open,
                    padding = pagePadding,
                )
                SpotifyTab.SEARCH -> SearchTab(
                    query = query,
                    onQueryChange = { query = it },
                    results = results,
                    searching = searching,
                    error = searchError,
                    onOpen = open,
                    padding = pagePadding,
                )
                SpotifyTab.LIBRARY -> LibraryTab(
                    playlists = playlists,
                    playlistItems = playlistItems,
                    albums = albums,
                    artists = artists,
                    onOpen = open,
                    padding = pagePadding,
                )
            }
        }

        // The tab row stays put while each page scrolls under it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = topInset)
                .height(tabsHeight)
                .padding(horizontal = 16.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.spotify_logo),
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(2.dp))
            SpotifyTab.entries.forEach { tab ->
                val (label, icon) = when (tab) {
                    SpotifyTab.HOME -> stringResource(R.string.spotify_tab_home) to Icons.Rounded.Home
                    SpotifyTab.SEARCH -> stringResource(R.string.spotify_tab_search) to Icons.Filled.Search
                    SpotifyTab.LIBRARY -> stringResource(R.string.spotify_tab_library) to Icons.Rounded.LibraryMusic
                }
                SpotifyChip(
                    label = label,
                    icon = icon,
                    selected = pager.currentPage == tab.ordinal,
                    onClick = { scope.launch { pager.animateScrollToPage(tab.ordinal) } },
                )
            }
        }
    }
}

// ---- Signed out ----

@Composable
private fun SpotifyWelcome(
    onOpenLogin: () -> Unit,
    onOpenTokenSetup: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 32.dp),
    ) {
        Icon(
            painter = painterResource(Res.drawable.spotify_logo),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.spotify_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.spotify_connect_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        PillButton(stringResource(R.string.spotify_sign_in), filled = true, onClick = onOpenLogin)
        Spacer(Modifier.height(12.dp))
        PillButton(stringResource(R.string.spotify_use_spdc_token), filled = false, onClick = onOpenTokenSetup)
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(if (filled) SpotifyGreen else outline.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = if (filled) OnSpotifyGreen else MaterialTheme.colorScheme.onBackground,
        )
    }
}

// ---- Home ----

@Composable
private fun HomeTab(
    home: SpotifyShelf<SpotifyHomeSection>,
    playlists: SpotifyShelf<SpotifyPlaylist>,
    playlistItems: List<SpotifyItem>,
    albums: SpotifyShelf<SpotifyItem>,
    onOpen: (SpotifyItem) -> Unit,
    padding: PaddingValues,
) {
    // Spotify's first home row is its recents; it becomes the shortcut grid on
    // top, as in the app, when it is big enough to fill one. Otherwise the grid
    // is made of the account's own playlists and albums.
    val first = home.items.firstOrNull()
    val firstIsGrid = first != null && first.items.size >= 4
    val shortcuts = if (firstIsGrid) first!!.items.take(8) else (playlistItems + albums.items).take(8)
    val sections = if (firstIsGrid) home.items.drop(1) else home.items

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "greeting") {
            Text(
                text = stringResource(greetingRes()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
            )
        }
        if (shortcuts.isNotEmpty()) {
            item(key = "shortcuts") { ShortcutGrid(shortcuts, onOpen) }
        }

        sectionState(home, emptyRes = null, key = "home")
        itemsIndexed(sections, key = { index, section -> "home:$index:${section.title}" }) { _, section ->
            Column {
                SectionHeader(section.title)
                ItemRow(section.items, onOpen)
            }
        }

        // Without a home feed, the page still has the account's own things on it.
        if (!home.loading && sections.isEmpty()) {
            if (playlistItems.isNotEmpty()) {
                item(key = "fb:pl") {
                    Column {
                        SectionHeader(stringResource(R.string.spotify_your_playlists))
                        ItemRow(playlistItems, onOpen)
                    }
                }
            }
            if (albums.items.isNotEmpty()) {
                item(key = "fb:al") {
                    Column {
                        SectionHeader(stringResource(R.string.spotify_saved_albums))
                        ItemRow(albums.items, onOpen)
                    }
                }
            }
            if (playlists.loading) sectionState(playlists, emptyRes = null, key = "fb")
        }
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
    }
}

private fun greetingRes(): Int = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> R.string.spotify_good_morning
    in 12..17 -> R.string.spotify_good_afternoon
    else -> R.string.spotify_good_evening
}

/** Spotify's two-column grid of compact shortcut tiles at the top of Home. */
@Composable
private fun ShortcutGrid(items: List<SpotifyItem>, onOpen: (SpotifyItem) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
    ) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { item ->
                    ShortcutTile(item, Modifier.weight(1f)) { onOpen(item) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShortcutTile(item: SpotifyItem, modifier: Modifier, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

// ---- Search ----

/** Genres to browse when nothing's typed yet; a tile searches for its name. */
private val BrowseGenres = listOf(
    "Pop" to Color(0xFF8D67AB),
    "Hip-Hop" to Color(0xFFBA5D07),
    "Bollywood" to Color(0xFFE8115B),
    "Rock" to Color(0xFF006450),
    "Indie" to Color(0xFF608108),
    "Dance / Electronic" to Color(0xFF0D73EC),
    "Chill" to Color(0xFF477D95),
    "Workout" to Color(0xFF777777),
    "Party" to Color(0xFFAF2896),
    "Romance" to Color(0xFFDC148C),
    "Focus" to Color(0xFF503750),
    "Sleep" to Color(0xFF1E3264),
    "Jazz" to Color(0xFF8C1932),
    "Lo-Fi" to Color(0xFF148A08),
    "K-Pop" to Color(0xFFE91429),
    "Classical" to Color(0xFF7D4B32),
)

private enum class SearchFilter { ALL, SONGS, ARTISTS, ALBUMS, PLAYLISTS }

@Composable
private fun SearchTab(
    query: String,
    onQueryChange: (String) -> Unit,
    results: SpotifySearchResult?,
    searching: Boolean,
    error: String?,
    onOpen: (SpotifyItem) -> Unit,
    padding: PaddingValues,
) {
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    LaunchedEffect(query) { if (query.isBlank()) filter = SearchFilter.ALL }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.spotify_tab_search),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            )
        }
        item(key = "field") { SearchField(query, onQueryChange) }

        if (query.isBlank()) {
            item(key = "browse") { SectionHeader(stringResource(R.string.spotify_browse_all)) }
            items(BrowseGenres.chunked(2), key = { "g:" + it.first().first }) { pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                ) {
                    pair.forEach { (name, color) ->
                        GenreTile(name, color, Modifier.weight(1f)) { onQueryChange(name) }
                    }
                }
            }
            return@LazyColumn
        }

        val r = results
        if (r != null && error == null) {
            item(key = "filters") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                ) {
                    val available = SearchFilter.entries.filter { f ->
                        when (f) {
                            SearchFilter.ALL -> true
                            SearchFilter.SONGS -> r.tracks.isNotEmpty()
                            SearchFilter.ARTISTS -> r.artists.isNotEmpty()
                            SearchFilter.ALBUMS -> r.albums.isNotEmpty()
                            SearchFilter.PLAYLISTS -> r.playlists.isNotEmpty()
                        }
                    }
                    items(available, key = { it.name }) { f ->
                        SpotifyChip(
                            label = stringResource(filterLabel(f)),
                            selected = filter == f,
                            onClick = { filter = f },
                        )
                    }
                }
            }
        }
        searchResults(r, searching, error, filter, onOpen)
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
    }
}

private fun filterLabel(f: SearchFilter): Int = when (f) {
    SearchFilter.ALL -> R.string.spotify_filter_all
    SearchFilter.SONGS -> R.string.spotify_section_songs
    SearchFilter.ARTISTS -> R.string.spotify_section_artists
    SearchFilter.ALBUMS -> R.string.spotify_section_albums
    SearchFilter.PLAYLISTS -> R.string.spotify_section_playlists
}

/** Spotify's search bar: a solid light pill in dark mode, dark in light. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val fieldColor = MaterialTheme.colorScheme.onBackground
    val textColor = MaterialTheme.colorScheme.background
    val focus = LocalFocusManager.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fieldColor)
            .padding(start = 12.dp, end = 4.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = textColor, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.spotify_search_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(SpotifyGreen),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Clear, contentDescription = null, tint = textColor)
            }
        }
    }
}

/** A coloured "Browse all" tile with a tilted block in its corner, like Spotify's. */
@Composable
private fun GenreTile(name: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 18.dp, y = 6.dp)
                .size(64.dp)
                .rotate(25f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.22f)),
        )
        Text(
            text = name,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp).padding(end = 28.dp),
        )
    }
}

private fun LazyListScope.searchResults(
    results: SpotifySearchResult?,
    searching: Boolean,
    error: String?,
    filter: SearchFilter,
    onOpen: (SpotifyItem) -> Unit,
) {
    if (searching && results == null) {
        item(key = "searching") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpotifyGreen)
            }
        }
        return
    }
    if (error != null) {
        item(key = "error") {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        return
    }
    val r = results ?: return
    if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.playlists.isEmpty()) {
        item(key = "none") {
            Text(
                text = stringResource(R.string.spotify_no_results),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
        return
    }
    fun list(key: String, rows: List<SpotifyItem>) =
        items(rows, key = { "$key:${it.kind}:${it.id}" }) { ItemListRow(it, showKind = true) { onOpen(it) } }

    when (filter) {
        SearchFilter.SONGS -> list("s", r.tracks)
        SearchFilter.ARTISTS -> list("a", r.artists)
        SearchFilter.ALBUMS -> list("b", r.albums)
        SearchFilter.PLAYLISTS -> list("p", r.playlists)
        SearchFilter.ALL -> {
            // A top hit first — the best artist when there is one, else the best song.
            val top = r.artists.firstOrNull() ?: r.tracks.firstOrNull() ?: r.albums.firstOrNull() ?: r.playlists.first()
            item(key = "top") {
                Column {
                    SectionHeader(stringResource(R.string.spotify_top_result))
                    TopResultCard(top) { onOpen(top) }
                }
            }
            if (r.tracks.isNotEmpty()) {
                item(key = "hs") { SectionHeader(stringResource(R.string.spotify_section_songs)) }
                items(r.tracks.filter { it != top }.take(4), key = { "as:" + it.id }) {
                    ItemListRow(it, showKind = false) { onOpen(it) }
                }
            }
            if (r.artists.size > 1 || (r.artists.isNotEmpty() && r.artists.first() != top)) {
                item(key = "ha") {
                    Column {
                        SectionHeader(stringResource(R.string.spotify_section_artists))
                        ItemRow(r.artists.filter { it != top }, onOpen)
                    }
                }
            }
            if (r.albums.isNotEmpty()) {
                item(key = "hb") {
                    Column {
                        SectionHeader(stringResource(R.string.spotify_section_albums))
                        ItemRow(r.albums, onOpen)
                    }
                }
            }
            if (r.playlists.isNotEmpty()) {
                item(key = "hp") {
                    Column {
                        SectionHeader(stringResource(R.string.spotify_section_playlists))
                        ItemRow(r.playlists, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopResultCard(item: SpotifyItem, onClick: () -> Unit) {
    val shape = if (item.kind == SpotifyKind.ARTIST) CircleShape else RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(shape),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(kindLabel(item.kind)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (item.subtitle.isNotBlank() && item.kind != SpotifyKind.ARTIST) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- Your Library ----

private enum class LibraryFilter { PLAYLISTS, ALBUMS, ARTISTS }

@Composable
private fun LibraryTab(
    playlists: SpotifyShelf<SpotifyPlaylist>,
    playlistItems: List<SpotifyItem>,
    albums: SpotifyShelf<SpotifyItem>,
    artists: SpotifyShelf<SpotifyItem>,
    onOpen: (SpotifyItem) -> Unit,
    padding: PaddingValues,
) {
    var filter by rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    var grid by rememberSaveable { mutableStateOf(false) }

    val shown = when (filter) {
        LibraryFilter.PLAYLISTS -> playlistItems
        LibraryFilter.ALBUMS -> albums.items
        LibraryFilter.ARTISTS -> artists.items
        null -> playlistItems + albums.items + artists.items
    }
    val shelves = when (filter) {
        LibraryFilter.PLAYLISTS -> listOf(playlists)
        LibraryFilter.ALBUMS -> listOf(albums)
        LibraryFilter.ARTISTS -> listOf(artists)
        null -> listOf(playlists, albums, artists)
    }
    val loading = shelves.any { it.loading }
    val errors = shelves.mapNotNull { it.error }.distinct()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "title") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.spotify_tab_library),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { grid = !grid }) {
                    Icon(
                        imageVector = if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        item(key = "filters") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (filter != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f))
                            .clickable { filter = null },
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                LibraryFilter.entries.forEach { f ->
                    if (filter == null || filter == f) {
                        SpotifyChip(
                            label = stringResource(
                                when (f) {
                                    LibraryFilter.PLAYLISTS -> R.string.spotify_section_playlists
                                    LibraryFilter.ALBUMS -> R.string.spotify_section_albums
                                    LibraryFilter.ARTISTS -> R.string.spotify_section_artists
                                },
                            ),
                            selected = filter == f,
                            onClick = { filter = if (filter == f) null else f },
                        )
                    }
                }
            }
        }

        if (grid) {
            items(shown.chunked(3), key = { row -> "g:" + row.joinToString { "${it.kind}${it.id}" } }) { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    row.forEach { item -> GridCell(item, Modifier.weight(1f)) { onOpen(item) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            itemsIndexed(shown, key = { i, it -> "l:$i:${it.kind}:${it.id}" }) { _, item ->
                ItemListRow(item, showKind = true, artSize = 64) { onOpen(item) }
            }
        }

        when {
            loading -> item(key = "loading") { LoadingRow() }
            shown.isEmpty() && errors.isNotEmpty() -> items(errors, key = { "e:$it" }) { ErrorText(it) }
            shown.isEmpty() -> item(key = "empty") {
                Text(
                    text = stringResource(R.string.spotify_empty_playlists),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun GridCell(item: SpotifyItem, modifier: Modifier, onClick: () -> Unit) {
    val artist = item.kind == SpotifyKind.ARTIST
    Column(
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(if (artist) CircleShape else RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(kindLabel(item.kind)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ---- Shared pieces ----

private fun kindLabel(kind: SpotifyKind): Int = when (kind) {
    SpotifyKind.TRACK -> R.string.spotify_kind_song
    SpotifyKind.ALBUM -> R.string.spotify_kind_album
    SpotifyKind.ARTIST -> R.string.spotify_kind_artist
    SpotifyKind.PLAYLIST -> R.string.spotify_kind_playlist
}

/** Spotify's pill: green when selected, a faint grey one otherwise. */
@Composable
private fun SpotifyChip(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val bg by animateColorAsState(
        if (selected) SpotifyGreen else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f),
        label = "chip",
    )
    val fg = if (selected) OnSpotifyGreen else MaterialTheme.colorScheme.onBackground
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        if (icon != null && selected) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

private fun LazyListScope.sectionState(shelf: SpotifyShelf<*>, emptyRes: Int?, key: String) {
    when {
        shelf.loading -> item(key = "$key:loading") { LoadingRow() }
        shelf.error != null -> item(key = "$key:error") { ErrorText(shelf.error) }
        shelf.items.isEmpty() && emptyRes != null -> item(key = "$key:empty") {
            Text(
                text = stringResource(emptyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp, color = SpotifyGreen)
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp),
    )
}

@Composable
private fun ItemRow(items: List<SpotifyItem>, onOpen: (SpotifyItem) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items, key = { index, item -> "${item.kind}:${item.id}:$index" }) { _, item ->
            SpotifyCard(item) { onOpen(item) }
        }
    }
}

/** A shelf card: square art (round for artists), bold title, grey subtitle. */
@Composable
private fun SpotifyCard(item: SpotifyItem, onClick: () -> Unit) {
    val artist = item.kind == SpotifyKind.ARTIST
    Column(
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(150.dp)
                .clip(if (artist) CircleShape else RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = if (artist) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (artist) TextAlign.Center else TextAlign.Start,
        )
        val subtitle = if (artist) stringResource(R.string.spotify_kind_artist) else item.subtitle
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (artist) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

/** A list row: art (round for artists), title, and "Kind • subtitle" under it. */
@Composable
private fun ItemListRow(item: SpotifyItem, showKind: Boolean, artSize: Int = 52, onClick: () -> Unit) {
    val artist = item.kind == SpotifyKind.ARTIST
    val kind = stringResource(kindLabel(item.kind))
    val subtitle = when {
        artist -> kind
        showKind && item.subtitle.isNotBlank() -> "$kind • ${item.subtitle}"
        showKind -> kind
        else -> item.subtitle
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(artSize.dp)
                .clip(if (artist) CircleShape else RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
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

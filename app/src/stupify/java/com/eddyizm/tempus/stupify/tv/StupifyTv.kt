package com.eddyizm.tempus.stupify.tv

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.eddyizm.tempus.App
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.stupify.StupifyHome
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.eddyizm.tempus.subsonic.models.ArtistID3
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.models.Playlist
import com.eddyizm.tempus.util.Preferences
import com.google.common.util.concurrent.ListenableFuture

/**
 * The Stupify TV app: a Leanback interface (rows of cards, D-pad friendly) over the same server,
 * playback service and queue as the phone app. Data calls here block: run them off the main thread.
 */
object StupifyTv {
    /** A card on a TV row: what it shows and what pressing OK on it does. */
    sealed class Item(val title: String, val subtitle: String, val coverArtId: String?) {
        object Dj : Item("Stupify DJ", "Favourites, new finds and songs you've missed", null)
        class OfPlaylist(val playlist: Playlist, subtitle: String) :
            Item(playlist.name.orEmpty(), subtitle, playlist.coverArtId)
        class OfAlbum(val album: AlbumID3) : Item(album.name.orEmpty(), album.artist.orEmpty(), album.coverArtId)
        class OfArtist(val artist: ArtistID3) : Item(artist.name.orEmpty(), "Artist", artist.coverArtId)
        class OfSong(val song: Child) : Item(song.title.orEmpty(), song.artist.orEmpty(), song.coverArtId)
    }

    class Row(val title: String, val items: List<Item>)

    fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION

    fun signedIn(): Boolean =
        Preferences.getServer() != null &&
            (Preferences.getPassword() != null || (Preferences.getToken() != null && Preferences.getSalt() != null))

    fun browser(context: Context): ListenableFuture<MediaBrowser> =
        MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, MediaService::class.java))).buildAsync()

    fun play(browser: ListenableFuture<MediaBrowser>, songs: List<Child>, start: Int = 0) {
        if (songs.isNotEmpty()) MediaManager.startQueue(browser, songs, start)
    }

    fun openPlayer(context: Context) =
        context.startActivity(Intent(context, TvPlayerActivity::class.java))

    // ---- data (blocking) ------------------------------------------------------------------------

    private val client get() = App.getSubsonicClientInstance(false)

    fun homeRows(): List<Row> {
        val mixes = runCatching { StupifyHome.load() }.getOrDefault(emptyList())
        val mixIds = mixes.map { it.id }.toSet()
        val rows = mutableListOf(Row("Stupify DJ", listOf(Item.Dj)))
        if (mixes.isNotEmpty()) rows += Row("Made for you", mixes.map {
            Item.OfPlaylist(it, it.name.orEmpty().substringAfter(" · ", "${it.songCount} songs"))
        })
        albums("newest").takeIf { it.isNotEmpty() }?.let { rows += Row("Recently added", it.map(Item::OfAlbum)) }
        albums("frequent").takeIf { it.isNotEmpty() }?.let { rows += Row("Most played", it.map(Item::OfAlbum)) }
        val own = runCatching {
            client.playlistClient.playlists.execute().body()?.subsonicResponse?.playlists?.playlists.orEmpty()
        }.getOrDefault(emptyList()).filter { it.id !in mixIds }
        if (own.isNotEmpty()) rows += Row("Your playlists", own.map { Item.OfPlaylist(it, "${it.songCount} songs") })
        return rows
    }

    private fun albums(type: String): List<AlbumID3> = runCatching {
        client.albumSongListClient.getAlbumList2(type, 30, 0, null, null).execute().body()
            ?.subsonicResponse?.albumList2?.albums.orEmpty()
    }.getOrDefault(emptyList())

    /** The songs to queue for a card (empty: nothing to play). */
    fun songsFor(item: Item): List<Child> = runCatching {
        when (item) {
            is Item.OfPlaylist -> client.playlistClient.getPlaylist(item.playlist.id).execute().body()
                ?.subsonicResponse?.playlist?.entries.orEmpty()
            is Item.OfAlbum -> client.browsingClient.getAlbum(item.album.id).execute().body()
                ?.subsonicResponse?.album?.songs.orEmpty()
            is Item.OfArtist -> client.browsingClient.getTopSongs(item.artist.name, 50).execute().body()
                ?.subsonicResponse?.topSongs?.songs.orEmpty()
            is Item.OfSong -> listOf(item.song)
            Item.Dj -> emptyList()
        }
    }.getOrDefault(emptyList())

    fun search(query: String): List<Row> {
        val r = runCatching {
            client.searchingClient.search3(query, 25, 0, 20, 0, 10, 0).execute().body()?.subsonicResponse?.searchResult3
        }.getOrNull() ?: return emptyList()
        return listOfNotNull(
            r.songs?.takeIf { it.isNotEmpty() }?.let { Row("Songs", it.map(Item::OfSong)) },
            r.albums?.takeIf { it.isNotEmpty() }?.let { Row("Albums", it.map(Item::OfAlbum)) },
            r.artists?.takeIf { it.isNotEmpty() }?.let { Row("Artists", it.map(Item::OfArtist)) },
        )
    }
}

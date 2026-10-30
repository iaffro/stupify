package com.eddyizm.tempus.stupify.tv

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import java.util.concurrent.Executors
import com.eddyizm.tempus.App
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.stupify.StupifyHome
import com.eddyizm.tempus.stupify.StupifySearch
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.eddyizm.tempus.subsonic.models.ArtistID3
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.models.Playlist
import com.eddyizm.tempus.util.Preferences
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * The Stupify TV app: a Leanback interface (rows of cards, D-pad friendly) over the same server,
 * playback service and queue as the phone app. Data calls here block: run them off the main thread.
 */
object StupifyTv {
    /** A card on a TV row: what it shows and what pressing OK on it does. */
    sealed class Item(val title: String, val subtitle: String, val coverArtId: String?) {
        object Dj : Item("Stupify DJ", "Favourites, new finds and songs you've missed", null)
        /** The listener's liked songs, always the first playlist. */
        object Liked : Item("Liked songs", "Your favourites", null)
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

    /** Makes a view a seek control: while it has focus, left / right skip back / forward in the playing song
     *  (10 s a press, 30 s while the key is held). `bar` is the progress bar that grows while the view is focused,
     *  so it is clear that left / right now seek. */
    fun bindSeek(view: android.view.View, bar: android.widget.ProgressBar, player: () -> androidx.media3.common.Player?, onSeek: () -> Unit = {}) {
        val density = bar.resources.displayMetrics.density
        val rest = bar.layoutParams.height
        view.isFocusable = true
        view.setOnFocusChangeListener { _, focused ->
            bar.layoutParams = bar.layoutParams.apply { height = if (focused) rest + (6 * density).toInt() else rest }
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT && keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            val p = player() ?: return@setOnKeyListener true
            if (event.action == android.view.KeyEvent.ACTION_DOWN && p.duration > 0) {
                val step = if (event.repeatCount >= 4) 30_000L else 10_000L
                val to = p.currentPosition + if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) step else -step
                p.seekTo(to.coerceIn(0L, p.duration))
                onSeek()
            }
            true  // left / right stay put: up / down move on
        }
    }

    /** Keeps the TV awake while music plays: without this the screen goes dark and then the TV switches itself
     *  off after a few minutes, which is no good for listening in the background. Paused, the TV's own
     *  timeout applies again. */
    fun keepAwake(activity: android.app.Activity, player: androidx.media3.common.Player?) {
        val playing = player != null && player.playWhenReady &&
            player.playbackState != androidx.media3.common.Player.STATE_IDLE &&
            player.playbackState != androidx.media3.common.Player.STATE_ENDED
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (playing) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }

    /** The playlist, album or artist the queue was started from: Now Playing offers a way back to it. */
    class Source(val type: String, val id: String, val title: String, val subtitle: String, val coverArtId: String?)

    @Volatile
    var playingFrom: Source? = null

    /** Starts a queue. `source` is where it came from (null for the DJ, a lone song and the like). The player's
     *  shuffle mode is the listener's last choice, exactly as on the phone. */
    fun play(browser: ListenableFuture<MediaBrowser>, songs: List<Child>, start: Int = 0, source: Source? = null) {
        playingFrom = source
        if (songs.isEmpty()) return
        MediaManager.startQueue(browser, songs, start)
        applyShuffleChoice(browser)
    }

    /** Puts the player's shuffle mode to what the listener last chose (Preferences, shared with the phone's button). */
    fun applyShuffleChoice(browser: ListenableFuture<MediaBrowser>) = browser.addListener({
        runCatching { browser.get() }.getOrNull()?.shuffleModeEnabled = Preferences.isShuffleModeEnabled()
    }, MoreExecutors.directExecutor())

    /** The DJ plays in order: its voice clips between sets would be scrambled by shuffle. Not saved as a choice. */
    fun shuffleOffForDj(browser: ListenableFuture<MediaBrowser>) = browser.addListener({
        val b = runCatching { browser.get() }.getOrNull() ?: return@addListener
        if (!b.shuffleModeEnabled) return@addListener
        val chosen = Preferences.isShuffleModeEnabled()
        b.addListener(object : androidx.media3.common.Player.Listener {
            // the service saves every change as the listener's choice; this one is the DJ's, not theirs
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                b.removeListener(this)
                Preferences.setShuffleModeEnabled(chosen)
            }
        })
        b.shuffleModeEnabled = false
    }, MoreExecutors.directExecutor())

    /** Adds songs after the current one (`next`) or to the end of the queue. With nothing queued yet there is
     *  no queue to add to, so they simply start playing. Under shuffle the playback service keeps songs queued
     *  by hand first, in the order they were queued. */
    fun enqueue(browser: ListenableFuture<MediaBrowser>, songs: List<Child>, next: Boolean) {
        if (songs.isEmpty()) return
        songs.forEach { com.eddyizm.tempus.stupify.StupifySearch.prefetch(it.id) } // ☁ songs start downloading now
        browser.addListener({
            val b = runCatching { browser.get() }.getOrNull() ?: return@addListener
            if (b.mediaItemCount == 0) {
                MediaManager.startQueue(browser, songs, 0)
                applyShuffleChoice(browser)
            } else {
                MediaManager.enqueue(browser, songs, next)
            }
        }, MoreExecutors.directExecutor())
    }

    /** Opens Now Playing. `reuse`: if one is already open further back in the stack, come back to that one
     *  instead of stacking a second (the mini player bar and the "playing from" link use this). */
    fun openPlayer(context: Context, reuse: Boolean = false) = context.startActivity(
        Intent(context, TvPlayerActivity::class.java).apply {
            if (reuse) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })

    /** The screen for a playlist, album or artist card (null for anything else). */
    fun detailIntent(context: Context, item: Item): Intent? = when (item) {
        Item.Liked -> TvDetailActivity.intent(context, "liked", "", item.title, item.subtitle, null)
        is Item.OfPlaylist -> TvDetailActivity.intent(context, "playlist", item.playlist.id, item.title, item.subtitle, item.coverArtId)
        is Item.OfAlbum -> TvDetailActivity.intent(context, "album", item.album.id.orEmpty(), item.title, item.subtitle, item.coverArtId)
        is Item.OfArtist -> TvDetailActivity.intent(context, "artist", item.artist.id.orEmpty(), item.title, item.subtitle, item.coverArtId)
        else -> null
    }

    // ---- data (blocking) ------------------------------------------------------------------------

    private val client get() = App.getSubsonicClientInstance(false)

    fun homeRows(): List<Row> {
        val mixes = runCatching { StupifyHome.load() }.getOrDefault(emptyList())
        val mixIds = mixes.map { it.id }.toSet()
        val own = runCatching {
            client.playlistClient.playlists.execute().body()?.subsonicResponse?.playlists?.playlists.orEmpty()
        }.getOrDefault(emptyList()).filter { it.id !in mixIds }
        val rows = mutableListOf<Row>()
        // Playlists first: they are what most listeners come here to open.
        rows += Row("Your playlists", listOf<Item>(Item.Liked) + own.map { Item.OfPlaylist(it, "${it.songCount} songs") })
        rows += Row("Stupify DJ", listOf(Item.Dj))
        if (mixes.isNotEmpty()) rows += Row("Made for you", mixes.map {
            Item.OfPlaylist(it, it.name.orEmpty().substringAfter(" · ", "${it.songCount} songs"))
        })
        // New releases by the artists this listener plays, in place of the server-wide "newest" list,
        // which is just whatever finished downloading last, for anybody.
        runCatching { StupifyHome.radarAlbums() }.getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }?.let { rows += Row("New releases for you", it.map(Item::OfAlbum)) }
        mostPlayedSongs().takeIf { it.isNotEmpty() }?.let { rows += Row("Most played", it.map(Item::OfSong)) }
        return rows
    }

    /** The songs played most, by their own play counts. Subsonic can only rank albums, and this library
     *  holds a few songs from each album, so a row of albums read as songs named after the wrong thing
     *  ("HAPPY RETENTION" for 'Keep Ya Head Up'). The frequent albums say where to look; the songs say
     *  what was actually played. */
    fun mostPlayedSongs(limit: Int = 30): List<Child> {
        val frequent = albums("frequent")
        if (frequent.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val loaded = frequent.map { a ->
                pool.submit<List<Child>> {
                    runCatching {
                        client.browsingClient.getAlbum(a.id).execute().body()?.subsonicResponse?.album?.songs.orEmpty()
                    }.getOrDefault(emptyList())
                }
            }.flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
            return loaded.filter { (it.playCount ?: 0) > 0 }
                .sortedByDescending { it.playCount ?: 0 }  // stable: ties keep the albums' own order
                .take(limit)
        } finally {
            pool.shutdown()
        }
    }

    private fun albums(type: String): List<AlbumID3> = runCatching {
        client.albumSongListClient.getAlbumList2(type, 30, 0, null, null).execute().body()
            ?.subsonicResponse?.albumList2?.albums.orEmpty()
    }.getOrDefault(emptyList())

    /** The songs to queue for a card (empty: nothing to play). */
    fun songsFor(item: Item): List<Child> = when (item) {
        Item.Liked -> songsOf("liked", "", item.title)
        is Item.OfPlaylist -> songsOf("playlist", item.playlist.id, item.title)
        is Item.OfAlbum -> songsOf("album", item.album.id.orEmpty(), item.title)
        is Item.OfArtist -> songsOf("artist", item.artist.id.orEmpty(), item.title)
        is Item.OfSong -> listOf(item.song)
        Item.Dj -> emptyList()
    }

    /** The songs of a playlist, album or artist (blocking). An artist's top songs come from Last.fm through
     *  the server; with none, their albums are the fallback, so the page is never empty for no reason. */
    fun songsOf(type: String, id: String, name: String): List<Child> = runCatching {
        when (type) {
            "playlist" -> client.playlistClient.getPlaylist(id).execute().body()
                ?.subsonicResponse?.playlist?.entries.orEmpty()
            "liked" -> client.albumSongListClient.starred2.execute().body()
                ?.subsonicResponse?.starred2?.songs.orEmpty()
            "album" -> client.browsingClient.getAlbum(id).execute().body()
                ?.subsonicResponse?.album?.songs.orEmpty()
            "artist" -> artistSongs(id, name)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun artistSongs(id: String, name: String): List<Child> {
        val top = runCatching {
            client.browsingClient.getTopSongs(name, 50).execute().body()?.subsonicResponse?.topSongs?.songs.orEmpty()
        }.getOrDefault(emptyList())
        if (top.isNotEmpty()) return top
        val albums = client.browsingClient.getArtist(id).execute().body()?.subsonicResponse?.artist?.albums.orEmpty()
        return albums.take(12).flatMap { a ->
            runCatching {
                client.browsingClient.getAlbum(a.id).execute().body()?.subsonicResponse?.album?.songs.orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    /** Whether the song is in the listener's liked songs right now (blocking). */
    fun isStarred(id: String): Boolean = runCatching {
        client.browsingClient.getSong(id).execute().body()?.subsonicResponse?.song?.starred != null
    }.getOrDefault(false)

    /** Likes or un-likes a song (blocking); false if the server did not take it. */
    fun setStarred(id: String, starred: Boolean): Boolean = runCatching {
        val call = if (starred) client.mediaAnnotationClient.star(id, null, null)
        else client.mediaAnnotationClient.unstar(id, null, null)
        call.execute().isSuccessful
    }.getOrDefault(false)

    fun search(query: String): List<Row> {
        val r = runCatching {
            client.searchingClient.search3(query, 25, 0, 20, 0, 10, 0).execute().body()?.subsonicResponse?.searchResult3
        }.getOrNull()
        // The catalogue, as on the phone: without it a TV search only ever finds what is already
        // downloaded, so anything new reads as "nothing found". Playing a ☁ card fetches it on demand.
        val cloud = runCatching { StupifySearch.cloud(query) }.getOrNull()
        return listOfNotNull(
            r?.songs?.takeIf { it.isNotEmpty() }?.let { Row("Songs", it.map(Item::OfSong)) },
            r?.albums?.takeIf { it.isNotEmpty() }?.let { Row("Albums", it.map(Item::OfAlbum)) },
            r?.artists?.takeIf { it.isNotEmpty() }?.let { Row("Artists", it.map(Item::OfArtist)) },
            cloud?.songs?.takeIf { it.isNotEmpty() }?.let { Row("☁ Not in your library yet", it.map(Item::OfSong)) },
            cloud?.albums?.takeIf { it.isNotEmpty() }?.let { Row("☁ Albums to download", it.map(Item::OfAlbum)) },
        )
    }
}

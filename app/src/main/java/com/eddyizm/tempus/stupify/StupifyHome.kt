package com.eddyizm.tempus.stupify

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.media3.session.MediaBrowser
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.StupifyHomeSectorBinding
import com.eddyizm.tempus.databinding.StupifyItemPlaylistCardBinding
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.model.HomeSector
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.eddyizm.tempus.subsonic.models.Playlist
import com.eddyizm.tempus.util.Constants
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** "Made for you" home sector: Stupify DJ, then the Daily Mixes, Discover Weekly etc. the Stupify server builds for the user. */
object StupifyHome {
    const val SECTOR_ID = "HOME_SECTOR_STUPIFY"

    const val RADAR_KEY = "radar"       // the hub's key for Release Radar in /app/v1/home
    const val RADAR_ALBUMS = 20         // albums to show in the "New releases for you" row
    private const val LAYOUT_PREFS = "stupify_layout"
    private const val LIFTED_PLAYLISTS = "lifted_playlists_top"
    const val LIKED_ID = "stupify-liked"  // the Liked songs card in the Playlists sector (not a real playlist)

    /** The first card of the Playlists sector: the listener's liked songs. */
    @JvmStatic
    fun likedPlaylist(): Playlist = Playlist(LIKED_ID, "Liked songs")

    /** Puts the sector first in a saved home layout that doesn't have it yet (stupify build only). */
    @JvmStatic
    fun addSector(sectors: MutableList<HomeSector>, title: String): Boolean {
        if (!StupifyUpdater.isStupifyBuild() || sectors.any { it.id == SECTOR_ID }) return false
        sectors.add(0, HomeSector(SECTOR_ID, title, true, 0))
        return true
    }

    /** Lifts the playlists sector to the very top, above Stupify's own, once: saved layouts put it at the
     *  very bottom, and playlists are the first thing most listeners open. Done a single time so that
     *  rearranging the home screen afterwards sticks. */
    @JvmStatic
    fun liftPlaylists(sectors: MutableList<HomeSector>): Boolean {
        if (!StupifyUpdater.isStupifyBuild()) return false
        val prefs = App.getInstance().getSharedPreferences(LAYOUT_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LIFTED_PLAYLISTS, false)) return false
        val at = sectors.indexOfFirst { it.id == Constants.HOME_SECTOR_PINNED_PLAYLISTS }
        if (at < 0) return false  // not in this layout yet: leave the flag, try again later
        prefs.edit().putBoolean(LIFTED_PLAYLISTS, true).apply()
        if (at == 0) return false
        sectors.add(0, sectors.removeAt(at))
        return true
    }

    @JvmStatic
    fun bind(fragment: Fragment, bind: StupifyHomeSectorBinding, browser: () -> ListenableFuture<MediaBrowser>?,
             onClick: (Bundle) -> Unit, onLongClick: (Bundle) -> Unit, onChanged: Runnable) {
        if (!StupifyUpdater.isStupifyBuild()) return
        bind.root.visibility = View.VISIBLE  // the DJ card is there even before any mixes are built
        bind.stupifyDjCard.setOnClickListener {
            if (bind.stupifyDjProgress.visibility == View.VISIBLE) return@setOnClickListener
            val player = browser() ?: return@setOnClickListener  // created in the fragment's onStart
            StupifyDj.start(fragment, player) { busy ->
                bind.stupifyDjProgress.visibility = if (busy) View.VISIBLE else View.GONE
                bind.stupifyDjPlay.visibility = if (busy) View.INVISIBLE else View.VISIBLE
                bind.stupifyDjSubtitle.setText(if (busy) R.string.stupify_dj_warming else R.string.stupify_dj_subtitle)
            }
        }
        val adapter = CardAdapter(onClick, onLongClick)
        bind.stupifyHomeRecyclerView.layoutManager =
            LinearLayoutManager(fragment.requireContext(), LinearLayoutManager.HORIZONTAL, false)
        bind.stupifyHomeRecyclerView.adapter = adapter
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val playlists = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() } ?: return@launch
            adapter.items = playlists
            bind.stupifyHomeRecyclerView.visibility = if (playlists.isEmpty()) View.GONE else View.VISIBLE
            onChanged.run()
        }
    }

    /** [playlists] without the server-generated mixes (Daily Mixes, Discover Weekly...), which have their own
     *  row; the server is asked off the main thread, and if it can't be reached nothing is left out. */
    @JvmStatic
    fun withoutMixes(fragment: Fragment, playlists: List<Playlist>, done: java.util.function.Consumer<List<Playlist>>) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val mixIds = withContext(Dispatchers.IO) {
                runCatching { load().map { it.id }.toSet() }.getOrDefault(emptySet())
            }
            done.accept(playlists.filter { it.id !in mixIds })
        }
    }

    /** The server's list, in its order, as the user's own Subsonic playlists (for covers and the playlist page). */
    @JvmStatic
    fun load(): List<Playlist> = loadKeyed().map { it.second }

    /** The albums the listener's Release Radar songs come from, newest first: new releases by the artists
     *  they actually play, which is what the server-wide "recently added" row used to sit in the way of. */
    @JvmStatic
    fun radarAlbums(limit: Int = RADAR_ALBUMS): List<AlbumID3> {
        val radar = loadKeyed().firstOrNull { it.first == RADAR_KEY }?.second ?: return emptyList()
        val client = App.getSubsonicClientInstance(false)
        val songs = runCatching {
            client.playlistClient.getPlaylist(radar.id).execute().body()?.subsonicResponse?.playlist?.entries
        }.getOrNull().orEmpty()
        return songs.mapNotNull { it.albumId }.distinct().take(limit).mapNotNull { id ->
            runCatching { client.browsingClient.getAlbum(id).execute().body()?.subsonicResponse?.album }.getOrNull()
        }
    }

    /** Same list, each entry with the key the hub gave it ("mix1", "discover", "radar", "albums"). */
    @JvmStatic
    fun loadKeyed(): List<Pair<String, Playlist>> {
        val client = App.getSubsonicClientInstance(false)
        val query = client.params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL("${BuildConfig.STUPIFY_SERVER}/app/v1/home?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        val keyed = try {
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("playlists")
            (0 until arr.length()).map {
                arr.getJSONObject(it).let { o -> o.optString("key") to o.getString("id") }
            }
        } finally {
            conn.disconnect()
        }
        if (keyed.isEmpty()) return emptyList()
        val all = client.playlistClient.playlists.execute().body()
            ?.subsonicResponse?.playlists?.playlists.orEmpty().associateBy { it.id }
        return keyed.mapNotNull { (key, id) -> all[id]?.let { key to it } }
    }

    private class CardAdapter(val onClick: (Bundle) -> Unit, val onLongClick: (Bundle) -> Unit) :
        RecyclerView.Adapter<CardAdapter.Holder>() {
        var items: List<Playlist> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        class Holder(val item: StupifyItemPlaylistCardBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(StupifyItemPlaylistCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = items[position]
            // "Daily Mix 1 · Funky Groove Anthology" -> title "Daily Mix 1", subtitle the mix's theme
            val name = p.name.orEmpty()
            val title = name.substringBefore(" · ")
            val theme = name.substringAfter(" · ", "")
            val songs = holder.itemView.context.getString(R.string.stupify_home_songs, p.songCount)
            holder.item.stupifyCardTitle.text = title
            holder.item.stupifyCardSubtitle.text = if (theme.isEmpty()) songs else "$theme · $songs"
            CustomGlideRequest.Builder
                .from(holder.itemView.context, p.coverArtId, CustomGlideRequest.ResourceType.Playlist)
                .build()
                .into(holder.item.stupifyCardCover)
            val bundle = { Bundle().apply { putParcelable(Constants.PLAYLIST_OBJECT, p) } }
            holder.itemView.setOnClickListener { onClick(bundle()) }
            holder.itemView.setOnLongClickListener { onLongClick(bundle()); true }
        }
    }
}

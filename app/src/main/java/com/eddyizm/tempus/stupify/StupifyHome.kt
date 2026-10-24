package com.eddyizm.tempus.stupify

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
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
import com.eddyizm.tempus.subsonic.models.Playlist
import com.eddyizm.tempus.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** "Made for you" home sector: the Daily Mixes, Discover Weekly etc. the Stupify server builds for the user. */
object StupifyHome {
    const val SECTOR_ID = "HOME_SECTOR_STUPIFY"

    /** Puts the sector first in a saved home layout that doesn't have it yet (stupify build only). */
    @JvmStatic
    fun addSector(sectors: MutableList<HomeSector>, title: String): Boolean {
        if (!StupifyUpdater.isStupifyBuild() || sectors.any { it.id == SECTOR_ID }) return false
        sectors.add(0, HomeSector(SECTOR_ID, title, true, 0))
        return true
    }

    @JvmStatic
    fun bind(fragment: Fragment, bind: StupifyHomeSectorBinding, onClick: (Bundle) -> Unit,
             onLongClick: (Bundle) -> Unit, onChanged: Runnable) {
        if (!StupifyUpdater.isStupifyBuild()) return
        val adapter = CardAdapter(onClick, onLongClick)
        bind.stupifyHomeRecyclerView.layoutManager =
            LinearLayoutManager(fragment.requireContext(), LinearLayoutManager.HORIZONTAL, false)
        bind.stupifyHomeRecyclerView.adapter = adapter
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val playlists = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() } ?: return@launch
            adapter.items = playlists
            bind.root.visibility = if (playlists.isEmpty()) View.GONE else View.VISIBLE
            onChanged.run()
        }
    }

    /** The server's list, in its order, as the user's own Subsonic playlists (for covers and the playlist page). */
    @JvmStatic
    fun load(): List<Playlist> {
        val client = App.getSubsonicClientInstance(false)
        val query = client.params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL("${BuildConfig.STUPIFY_SERVER}/app/v1/home?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        val ids = try {
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("playlists")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        } finally {
            conn.disconnect()
        }
        if (ids.isEmpty()) return emptyList()
        val all = client.playlistClient.playlists.execute().body()
            ?.subsonicResponse?.playlists?.playlists.orEmpty().associateBy { it.id }
        return ids.mapNotNull { all[it] }
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

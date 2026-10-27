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
import com.eddyizm.tempus.databinding.StupifyItemCloudSongBinding
import com.eddyizm.tempus.databinding.StupifySearchCloudBinding
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.models.SearchResult3
import com.eddyizm.tempus.subsonic.utils.EmptyDateTypeAdapter
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.MusicUtil
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date

/**
 * Search, stupify build: library results show at once (the app's search3 is library-only for the "Stupify"
 * client), then the server's ☁ songs fill their own section, and ☁ albums are handed to the album row.
 */
class StupifySearch(
    private val fragment: Fragment,
    private val bind: StupifySearchCloudBinding,
    private val onPlay: (Bundle) -> Unit,
    private val onLongClick: (Bundle) -> Unit,
    private val onCloudAlbums: (List<AlbumID3>) -> Unit,
) {
    private val adapter = SongAdapter()
    private var searchJob: Job? = null
    private var pollJob: Job? = null

    /** cloud | downloading | ready | failed, by ext id */
    private val states = mutableMapOf<String, String>()

    init {
        bind.stupifyCloudRecyclerView.layoutManager = LinearLayoutManager(fragment.requireContext())
        bind.stupifyCloudRecyclerView.adapter = adapter
    }

    /** True when the section has ☁ songs to show (the fragment then hides its "nothing found" view). */
    val hasResults: Boolean get() = adapter.songs.isNotEmpty()

    fun search(query: String, onDone: Runnable) {
        searchJob?.cancel()
        adapter.songs = emptyList()
        onCloudAlbums(emptyList())
        bind.root.visibility = View.VISIBLE
        bind.stupifyCloudProgress.visibility = View.VISIBLE
        bind.stupifyCloudSubtitle.setText(R.string.stupify_cloud_subtitle)
        searchJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetch(query) }.getOrNull() }
            bind.stupifyCloudProgress.visibility = View.GONE
            val songs = result?.songs.orEmpty()
            adapter.songs = songs
            onCloudAlbums(result?.albums.orEmpty())
            when {
                result == null -> bind.stupifyCloudSubtitle.setText(R.string.stupify_cloud_failed)
                songs.isEmpty() -> bind.root.visibility = View.GONE
            }
            onDone.run()
        }
    }

    private fun fetch(query: String): SearchResult3 = cloud(query)

    private fun played(song: Child) {
        if (states[song.id] == "ready") return
        states[song.id] = "downloading"
        adapter.refresh(song.id)
        if (pollJob?.isActive == true) return
        pollJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val until = System.currentTimeMillis() + 5 * 60 * 1000
            while (isActive && System.currentTimeMillis() < until) {
                delay(2500)
                val waiting = states.filterValues { it == "downloading" }.keys
                if (waiting.isEmpty()) break
                val answer = withContext(Dispatchers.IO) {
                    runCatching { JSONObject(get("/app/v1/status", mapOf("ids" to waiting.joinToString(",")))) }
                        .getOrNull()
                } ?: continue
                for (id in waiting) {
                    val state = answer.optJSONObject(id)?.optString("state") ?: continue
                    // the hub reports a fresh play-now job a moment after the stream asked for it
                    if (state == "cloud") continue
                    states[id] = state
                    adapter.refresh(id)
                }
            }
        }
    }

    private inner class SongAdapter : RecyclerView.Adapter<SongAdapter.Holder>() {
        var songs: List<Child> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        inner class Holder(val item: StupifyItemCloudSongBinding) : RecyclerView.ViewHolder(item.root)

        fun refresh(id: String) {
            val i = songs.indexOfFirst { it.id == id }
            if (i >= 0) notifyItemChanged(i)
        }

        override fun getItemCount() = songs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(StupifyItemCloudSongBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val song = songs[position]
            val ctx = holder.itemView.context
            val duration = MusicUtil.getReadableDurationString(song.duration, false)
            holder.item.stupifyCloudSongTitle.text = song.title
            val state = states[song.id]
            holder.item.stupifyCloudSongSubtitle.text = when (state) {
                "downloading" -> ctx.getString(R.string.stupify_cloud_downloading)
                "ready" -> ctx.getString(R.string.stupify_cloud_ready, song.artist.orEmpty())
                "failed" -> ctx.getString(R.string.stupify_cloud_song_failed, song.artist.orEmpty())
                else -> ctx.getString(R.string.stupify_cloud_song_subtitle, song.artist.orEmpty(), duration)
            }
            holder.item.stupifyCloudSongBusy.visibility = if (state == "downloading") View.VISIBLE else View.GONE
            CustomGlideRequest.Builder
                .from(ctx, song.coverArtId, CustomGlideRequest.ResourceType.Song)
                .build()
                .into(holder.item.stupifyCloudCover)
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                played(songs[pos])
                onPlay(Bundle().apply {
                    putParcelableArrayList(Constants.TRACKS_OBJECT, ArrayList(listOf(songs[pos])))
                    putInt(Constants.ITEM_POSITION, 0)
                })
            }
            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                onLongClick(Bundle().apply {
                    putParcelable(Constants.TRACK_OBJECT, songs[pos])
                    putInt(Constants.ITEM_POSITION, pos)
                })
                true
            }
        }
    }

    companion object {
        private val gson = GsonBuilder()
            .registerTypeAdapter(Date::class.java, EmptyDateTypeAdapter())
            .setLenient()
            .create()

        private fun query(extra: Map<String, String>): String =
            (App.getSubsonicClientInstance(false).params + extra).entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }

        private fun get(path: String, extra: Map<String, String>): String {
            val conn = URL("${BuildConfig.STUPIFY_SERVER}$path?${query(extra)}").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            try {
                return conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }

        /** The ☁ part of a search: songs and albums the library doesn't have. Blocking; call it off the
         *  main thread. Shared with the TV, whose search was library-only and so found nothing at all
         *  for anything not downloaded yet. */
        @JvmStatic
        fun cloud(query: String): SearchResult3 =
            gson.fromJson(get("/app/v1/cloud", mapOf("q" to query)), SearchResult3::class.java)
    }
}

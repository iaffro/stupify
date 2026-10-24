package com.eddyizm.tempus.stupify

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.provider.AlbumArtContentProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/** Android Auto: the "Made for you" playlists from the Stupify server, as a folder in the car's Home. */
object StupifyAuto {
    const val MADE_FOR_YOU_ID = "[stupifyMadeForYouID]"

    private val executor = Executors.newSingleThreadExecutor()

    /** Items use [prefix] + playlist id, so opening one goes through the tree's normal playlist route. */
    @JvmStatic
    fun getPlaylists(prefix: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        executor.execute {
            val playlists = runCatching { StupifyHome.load() }.getOrElse {
                future.set(LibraryResult.ofError(SessionError.ERROR_IO))
                return@execute
            }
            val items = playlists.map { playlist ->
                val coverId = playlist.coverArtId
                val artwork = if (!coverId.isNullOrEmpty()) AlbumArtContentProvider.contentUri(coverId)
                else Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.ic_aa_playlist}")
                MediaItem.Builder()
                    .setMediaId(prefix + playlist.id)
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(playlist.name)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .setArtworkUri(artwork)
                        .build())
                    .setUri("")
                    .build()
            }
            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), null))
        }
        return future
    }
}

package com.eddyizm.tempus.stupify

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.subsonic.base.ApiResponse
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Constants
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/** Song radio: the mix becomes a playlist and its page opens, instead of starting to play at once. */
object StupifyRadio {
    @JvmStatic
    fun create(activity: MainActivity, name: String, songs: List<Child>) {
        val ids = ArrayList(songs.map { it.id })
        fun fail() = Handler(Looper.getMainLooper()).post {
            Toast.makeText(activity, R.string.bottom_sheet_problem_generating_instant_mix, Toast.LENGTH_SHORT).show()
        }
        App.getSubsonicClientInstance(false).playlistClient.createPlaylist(null, name, ids)
            .enqueue(object : Callback<ApiResponse> {
                override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                    val playlist = response.body()?.subsonicResponse?.playlist
                    if (playlist == null) { fail(); return }
                    activity.runOnUiThread {
                        val bundle = android.os.Bundle().apply { putParcelable(Constants.PLAYLIST_OBJECT, playlist) }
                        activity.navController.navigate(R.id.playlistPageFragment, bundle)
                    }
                }

                override fun onFailure(call: Call<ApiResponse>, t: Throwable) = fail().let { }
            })
    }
}

package com.eddyizm.tempus.stupify.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.StupifyTvTrackRowBinding
import com.eddyizm.tempus.subsonic.models.Child

/**
 * The songs of a playlist, album or the queue, one focusable row each. OK plays the row; a long press of OK
 * (or the remote's Menu key) asks for more: play next, add to queue, like and so on.
 */
class TvTrackAdapter(
    private val onPlay: (Int) -> Unit,
    private val onMore: (Int) -> Unit,
) : RecyclerView.Adapter<TvTrackAdapter.Holder>() {

    var items: List<Child> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** The row that is playing (shown with a marker and in the accent colour), or -1. */
    var current: Int = -1
        set(value) {
            val old = field
            field = value
            if (old in items.indices) notifyItemChanged(old)
            if (value in items.indices) notifyItemChanged(value)
        }

    class Holder(val b: StupifyTvTrackRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(StupifyTvTrackRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val song = items[position]
        val ctx = holder.b.root.context
        val playing = position == current
        holder.b.tvTrackNo.text = if (playing) "▶" else (position + 1).toString()
        holder.b.tvTrackNo.setTextColor(ContextCompat.getColor(ctx, if (playing) R.color.stupify_tv_accent else R.color.stupify_tv_text_dim))
        holder.b.tvTrackTitle.text = song.title.orEmpty()
        holder.b.tvTrackTitle.setTextColor(ContextCompat.getColor(ctx, if (playing) R.color.stupify_tv_accent else R.color.stupify_tv_text))
        holder.b.tvTrackArtist.text = song.artist.orEmpty()
        holder.b.tvTrackHeart.visibility = if (song.starred != null) View.VISIBLE else View.GONE
        holder.b.tvTrackTime.text = clock(song.duration)
        holder.b.root.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onPlay) }
        holder.b.root.setOnLongClickListener {
            holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onMore)
            true
        }
        holder.b.root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
                holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onMore)
                true
            } else false
        }
    }

    companion object {
        fun clock(seconds: Int?): String {
            val s = seconds ?: return ""
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}

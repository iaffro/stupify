package com.eddyizm.tempus.stupify.tv

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.eddyizm.tempus.R
import com.eddyizm.tempus.glide.CustomGlideRequest

/** Square cover cards for every TV row; the DJ card shows the unicorn. */
class TvCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_PX, CARD_PX)
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
            val surface = ContextCompat.getColor(context, R.color.stupify_tv_card)
            setBackgroundColor(surface)
            setInfoAreaBackgroundColor(surface)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any?) {
        val card = holder.view as ImageCardView
        val it = item as StupifyTv.Item
        card.titleText = it.title
        card.contentText = it.subtitle
        if (it is StupifyTv.Item.Dj) {
            card.mainImageView.setImageResource(R.drawable.stupify_logo)
            card.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)
            return
        }
        card.setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        val type = when (it) {
            is StupifyTv.Item.OfPlaylist -> CustomGlideRequest.ResourceType.Playlist
            is StupifyTv.Item.OfArtist -> CustomGlideRequest.ResourceType.Artist
            is StupifyTv.Item.OfSong -> CustomGlideRequest.ResourceType.Song
            else -> CustomGlideRequest.ResourceType.Album
        }
        CustomGlideRequest.Builder.from(card.context, it.coverArtId, type).build().into(card.mainImageView)
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {
        (holder.view as ImageCardView).mainImage = null
    }

    companion object {
        const val CARD_PX = 300
    }
}

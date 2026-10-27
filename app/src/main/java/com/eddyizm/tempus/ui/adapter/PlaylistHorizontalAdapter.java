package com.eddyizm.tempus.ui.adapter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.ItemHorizontalPlaylistBinding;
import com.eddyizm.tempus.glide.CustomGlideRequest;
import com.eddyizm.tempus.interfaces.ClickCallback;
import com.eddyizm.tempus.subsonic.models.Playlist;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.MusicUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PlaylistHorizontalAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements Filterable {
    private final ClickCallback click;

    private List<Playlist> playlists;
    private List<Playlist> playlistsFull;

    private final Filter filtering = new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<Playlist> filteredList = new ArrayList<>();

            if (constraint == null || constraint.length() == 0) {
                filteredList.addAll(playlistsFull);
            } else {
                String filterPattern = constraint.toString().toLowerCase().trim();

                for (Playlist item : playlistsFull) {
                    if (item.getName().toLowerCase().contains(filterPattern)) {
                        filteredList.add(item);
                    }
                }
            }

            FilterResults results = new FilterResults();
            results.values = filteredList;
            results.count = filteredList.size();

            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            playlists.clear();
            if (results.values != null) {
                playlists.addAll((List<Playlist>) results.values);
            }
            notifyDataSetChanged();
        }
    };

    /** Stupify: one row of cover + name cards (as on the TV) instead of the tall list. */
    private final boolean compact;

    public PlaylistHorizontalAdapter(ClickCallback click) {
        this(click, false);
    }

    public PlaylistHorizontalAdapter(ClickCallback click, boolean compact) {
        this.click = click;
        this.compact = compact;
        this.playlists = Collections.emptyList();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (compact) {
            return new ChipHolder(com.eddyizm.tempus.databinding.StupifyItemPlaylistChipBinding
                    .inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
        ItemHorizontalPlaylistBinding view = ItemHorizontalPlaylistBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Playlist playlist = playlists.get(position);
        if (viewHolder instanceof ChipHolder) {
            ChipHolder chip = (ChipHolder) viewHolder;
            chip.item.stupifyChipTitle.setText(playlist.getName());
            if (com.eddyizm.tempus.stupify.StupifyHome.LIKED_ID.equals(playlist.getId())) {
                com.bumptech.glide.Glide.with(chip.itemView).clear(chip.item.stupifyChipCover);
                chip.item.stupifyChipCover.setImageResource(R.drawable.stupify_liked_cover);
            } else {
                CustomGlideRequest.Builder
                        .from(chip.itemView.getContext(), playlist.getCoverArtId(), CustomGlideRequest.ResourceType.Playlist)
                        .build()
                        .into(chip.item.stupifyChipCover);
            }
            return;
        }
        ViewHolder holder = (ViewHolder) viewHolder;

        holder.item.playlistTitleTextView.setText(playlist.getName());
        if (com.eddyizm.tempus.stupify.StupifyHome.LIKED_ID.equals(playlist.getId())) {
            holder.item.playlistSubtitleTextView.setText(R.string.stupify_liked_subtitle);
            com.bumptech.glide.Glide.with(holder.itemView).clear(holder.item.playlistCoverImageView);
            holder.item.playlistCoverImageView.setImageResource(R.drawable.stupify_liked_cover);
            holder.item.playlistMoreButton.setVisibility(View.GONE);
            return;
        }
        holder.item.playlistMoreButton.setVisibility(View.VISIBLE);
        holder.item.playlistSubtitleTextView.setText(holder.itemView.getContext().getString(R.string.playlist_counted_tracks, playlist.getSongCount(), MusicUtil.getReadableDurationString(playlist.getDuration(), false)));

        CustomGlideRequest.Builder
                .from(holder.itemView.getContext(), playlist.getCoverArtId(), CustomGlideRequest.ResourceType.Playlist)
                .build()
                .into(holder.item.playlistCoverImageView);
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    public Playlist getItem(int id) {
        return playlists.get(id);
    }

    public void setItems(List<Playlist> playlists) {
        this.playlists = playlists;
        this.playlistsFull = new ArrayList<>(playlists);
        notifyDataSetChanged();
    }

    @Override
    public Filter getFilter() {
        return filtering;
    }

    public class ChipHolder extends RecyclerView.ViewHolder {
        final com.eddyizm.tempus.databinding.StupifyItemPlaylistChipBinding item;

        ChipHolder(com.eddyizm.tempus.databinding.StupifyItemPlaylistChipBinding item) {
            super(item.getRoot());
            this.item = item;
            itemView.setOnClickListener(v -> {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlists.get(getBindingAdapterPosition()));
                click.onPlaylistClick(bundle);
            });
            itemView.setOnLongClickListener(v -> {
                Bundle bundle = new Bundle();
                bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlists.get(getBindingAdapterPosition()));
                click.onPlaylistLongClick(bundle);
                return true;
            });
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        ItemHorizontalPlaylistBinding item;

        ViewHolder(ItemHorizontalPlaylistBinding item) {
            super(item.getRoot());

            this.item = item;
            item.playlistTitleTextView.setSelected(true);

            itemView.setOnClickListener(v -> onClick());
            itemView.setOnLongClickListener(this::onLongClick);

            item.playlistMoreButton.setOnClickListener(this::onLongClick);
        }

        public void onClick() {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlists.get(getBindingAdapterPosition()));

            click.onPlaylistClick(bundle);
        }

        public boolean onLongClick(View v) {
            Bundle bundle = new Bundle();
            bundle.putParcelable(Constants.PLAYLIST_OBJECT, playlists.get(getBindingAdapterPosition()));

            click.onPlaylistLongClick(bundle);

            return true;
        }
    }

    public void sort(String order) {
        switch (order) {
            case Constants.PLAYLIST_ORDER_BY_NAME:
                playlists.sort(Comparator.comparing(Playlist::getName));
                break;
            case Constants.PLAYLIST_ORDER_BY_RANDOM:
                Collections.shuffle(playlists);
                break;
        }

        notifyDataSetChanged();
    }
}

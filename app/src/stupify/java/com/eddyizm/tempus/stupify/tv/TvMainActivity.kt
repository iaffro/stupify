package com.eddyizm.tempus.stupify.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaBrowser
import com.eddyizm.tempus.R
import com.eddyizm.tempus.stupify.StupifyDj
import com.eddyizm.tempus.stupify.StupifyUpdater
import com.eddyizm.tempus.ui.activity.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TV home: the Stupify DJ, Made for you, recent and most played albums, playlists. */
class TvMainActivity : FragmentActivity() {
    lateinit var browser: ListenableFuture<MediaBrowser>
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!StupifyTv.signedIn()) {  // sign in on the normal screen, which comes back here (StupifyLogin)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        browser = StupifyTv.browser(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, TvHomeFragment()).commit()
            StupifyUpdater.check(this)  // TVs get the same in-app updates as phones
        }
    }

    override fun onDestroy() {
        if (this::browser.isInitialized) MediaBrowser.releaseFuture(browser)
        super.onDestroy()
    }
}

/** Shared by the home and search screens: what pressing OK on a card does. */
internal fun FragmentActivity.openItem(browser: ListenableFuture<MediaBrowser>, item: StupifyTv.Item) {
    if (item is StupifyTv.Item.Dj) {
        StupifyDj.start(this, applicationContext, browser, onBusy = { busy ->
            if (busy) Toast.makeText(this, R.string.stupify_dj_warming, Toast.LENGTH_SHORT).show()
        }, onStarted = { StupifyTv.openPlayer(this) })
        return
    }
    lifecycleScope.launch {
        val songs = withContext(Dispatchers.IO) { StupifyTv.songsFor(item) }
        if (songs.isEmpty()) {
            Toast.makeText(this@openItem, R.string.stupify_tv_nothing_to_play, Toast.LENGTH_SHORT).show()
            return@launch
        }
        StupifyTv.play(browser, songs)
        StupifyTv.openPlayer(this@openItem)
    }
}

internal fun rowsAdapter(rows: List<StupifyTv.Row>): ArrayObjectAdapter =
    ArrayObjectAdapter(ListRowPresenter()).apply {
        val cards = TvCardPresenter()
        rows.forEachIndexed { i, row ->
            add(ListRow(HeaderItem(i.toLong(), row.title), ArrayObjectAdapter(cards).apply { addAll(0, row.items) }))
        }
    }

class TvHomeFragment : BrowseSupportFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        badgeDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.stupify_logo)
        headersState = HEADERS_DISABLED  // rows only: simpler with a remote
        brandColor = ContextCompat.getColor(requireContext(), R.color.stupify_tv_brand)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.stupify_tv_accent)
        setOnSearchClickedListener { startActivity(Intent(requireContext(), TvSearchActivity::class.java)) }
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val activity = requireActivity() as TvMainActivity
            activity.openItem(activity.browser, item as StupifyTv.Item)
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (adapter != null && (adapter as ObjectAdapter).size() > 0) return
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { StupifyTv.homeRows() }
            adapter = rowsAdapter(rows)
        }
    }
}

class TvSearchActivity : FragmentActivity() {
    lateinit var browser: ListenableFuture<MediaBrowser>
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = StupifyTv.browser(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, TvSearchFragment()).commit()
        }
    }

    override fun onDestroy() {
        MediaBrowser.releaseFuture(browser)
        super.onDestroy()
    }
}

class TvSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {
    private val results = ArrayObjectAdapter(ListRowPresenter())
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener { _, item, _, _ ->
            val activity = requireActivity() as TvSearchActivity
            activity.openItem(activity.browser, item as StupifyTv.Item)
        }
    }

    override fun getResultsAdapter(): ObjectAdapter = results

    override fun onQueryTextChange(newQuery: String): Boolean = false  // search on Enter: kinder to the server

    override fun onQueryTextSubmit(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty() || q == lastQuery) return true
        lastQuery = q
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { StupifyTv.search(q) }
            results.clear()
            val cards = TvCardPresenter()
            rows.forEachIndexed { i, row ->
                results.add(ListRow(HeaderItem(i.toLong(), row.title), ArrayObjectAdapter(cards).apply { addAll(0, row.items) }))
            }
            if (rows.isEmpty()) Toast.makeText(requireContext(), R.string.stupify_tv_no_results, Toast.LENGTH_SHORT).show()
        }
        return true
    }
}

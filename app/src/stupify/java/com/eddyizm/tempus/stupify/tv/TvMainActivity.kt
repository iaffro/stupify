package com.eddyizm.tempus.stupify.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
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
        setContentView(R.layout.stupify_tv_screen)
        TvMiniBar(this)
        browser = StupifyTv.browser(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.tv_screen_content, TvHomeFragment()).commit()
            StupifyUpdater.check(this)  // TVs get the same in-app updates as phones
        }
    }

    override fun onDestroy() {
        if (this::browser.isInitialized) MediaBrowser.releaseFuture(browser)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val home = supportFragmentManager.findFragmentById(R.id.tv_screen_content) as? BrowseSupportFragment
        return moveBetweenRowsAndBar(home?.rowsSupportFragment, event) || super.dispatchKeyEvent(event)
    }
}

/**
 * Leanback's row grids keep focus inside themselves at the bottom, so with the D-pad the mini player bar under
 * them could never be reached. DOWN on the last row now moves focus to the bar, and UP from the bar goes back
 * to the rows. Returns true when it handled the key.
 */
internal fun FragmentActivity.moveBetweenRowsAndBar(rows: RowsSupportFragment?, event: KeyEvent): Boolean {
    if (rows == null || event.action != KeyEvent.ACTION_DOWN) return false
    val bar = findViewById<View>(R.id.tv_mini_bar) ?: return false
    if (bar.visibility != View.VISIBLE) return false
    val focus = currentFocus ?: return false
    fun inside(root: View?): Boolean {
        var v: View? = focus
        while (v != null) {
            if (v === root) return true
            v = v.parent as? View
        }
        return false
    }
    when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            val last = (rows.adapter?.size() ?: return false) - 1
            if (!inside(rows.view) || rows.selectedPosition != last) return false
            findViewById<View>(R.id.tv_mini_open).requestFocus()
            return true
        }
        KeyEvent.KEYCODE_DPAD_UP -> {
            if (!inside(bar)) return false
            return rows.view?.requestFocus() == true
        }
    }
    return false
}

/** The songs on a row, in order (empty for any other kind of row). */
internal fun ListRow?.songItems(): List<StupifyTv.Item.OfSong> {
    val a = this?.adapter ?: return emptyList()
    return (0 until a.size()).mapNotNull { a.get(it) as? StupifyTv.Item.OfSong }
}

/**
 * Shared by the home and search screens: what pressing OK on a card does. A playlist, album or artist opens
 * its own screen (with Play, Shuffle and the songs); the DJ starts; a song plays from its row, so the rest of
 * the row follows it. A ☁ song plays alone: queueing the row would fetch every one of them.
 */
internal fun FragmentActivity.openItem(browser: ListenableFuture<MediaBrowser>, item: StupifyTv.Item, row: ListRow? = null) {
    when (item) {
        is StupifyTv.Item.Dj -> {
            StupifyTv.playingFrom = null
            StupifyTv.shuffleOffForDj(browser)
            StupifyDj.start(this, applicationContext, browser, onBusy = { busy ->
                if (busy) Toast.makeText(this, R.string.stupify_dj_warming, Toast.LENGTH_SHORT).show()
            }, onStarted = { StupifyTv.openPlayer(this) })
        }
        is StupifyTv.Item.OfSong -> {
            val inRow = if (item.song.id.startsWith("ext-")) emptyList() else row.songItems().map { it.song }
            val songs = inRow.ifEmpty { listOf(item.song) }
            StupifyTv.play(browser, songs, songs.indexOfFirst { it.id == item.song.id }.coerceAtLeast(0))
            StupifyTv.openPlayer(this)
        }
        else -> StupifyTv.detailIntent(this, item)?.let { startActivity(it) }
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
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
            val activity = requireActivity() as TvMainActivity
            activity.openItem(activity.browser, item as StupifyTv.Item, row as? ListRow)
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
        setContentView(R.layout.stupify_tv_screen)
        TvMiniBar(this)
        browser = StupifyTv.browser(this)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.tv_screen_content, TvSearchFragment()).commit()
        }
    }

    override fun onDestroy() {
        MediaBrowser.releaseFuture(browser)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val search = supportFragmentManager.findFragmentById(R.id.tv_screen_content) as? SearchSupportFragment
        return moveBetweenRowsAndBar(search?.rowsSupportFragment, event) || super.dispatchKeyEvent(event)
    }
}

class TvSearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {
    private val results = ArrayObjectAdapter(ListRowPresenter())
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener { _, item, _, row ->
            val activity = requireActivity() as TvSearchActivity
            activity.openItem(activity.browser, item as StupifyTv.Item, row as? ListRow)
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

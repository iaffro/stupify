package com.eddyizm.tempus.stupify

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import com.eddyizm.tempus.lan.LanRemoteSession
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** "Play on…": this device first, then every other device of the user that is online, each by its own name. */
object ConnectPicker {
    private class Row(val id: String?, val label: String, val sub: String, val on: Boolean)

    @JvmStatic fun show(activity: FragmentActivity) {
        ConnectHub.start(activity)
        var rows = listOf<Row>()
        val adapter = object : ArrayAdapter<Row>(activity, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val r = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = (if (r.on) "✓  " else "     ") + r.label
                v.findViewById<TextView>(android.R.id.text2).text = r.sub
                return v
            }
        }
        val controlling = LanRemoteSession.isActive()
        fun rebuild(list: List<ConnectHub.Device>) {
            val me = list.firstOrNull { it.self }
            val remoteName = LanRemoteSession.current().name
            val mine = Row(null, "This device" + (me?.name?.let { " · $it" } ?: ""),
                if (!controlling) "Playing here" else "Bring the music back here", !controlling)
            val others = list.filter { !it.self }.map {
                Row(it.id, it.name, (if (it.kind == "tv") "TV" else "Phone") + if (it.active) " · playing now" else "",
                    controlling && it.name == remoteName)
            }
            rows = listOf(mine) + others
            adapter.clear(); adapter.addAll(rows)
        }
        val observer = Observer<List<ConnectHub.Device>> { rebuild(it) }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (ConnectHub.isConnected()) "Play on…" else "Connecting…")
            .setAdapter(adapter, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        rebuild(ConnectHub.devices().value.orEmpty())
        ConnectHub.devices().observe(activity, observer)
        dialog.setOnDismissListener { ConnectHub.devices().removeObserver(observer) }
        dialog.show()
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) ?: return@setOnItemClickListener
            if (row.id == null) { if (controlling) LanRemoteSession.pullBack(activity) }
            else if (!(controlling && row.on)) LanRemoteSession.connectHub(activity, row.id, row.label)
            dialog.dismiss()
        }
    }
}

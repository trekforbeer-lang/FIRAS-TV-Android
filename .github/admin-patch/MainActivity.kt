package com.firas.tv

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.firas.tv.stream.StreamConfigStore
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class MainActivity : AppCompatActivity() {
    private var sections: List<Section> = emptyList()
    private var selected = ""
    private var query = ""
    private var lastLaunchAt = 0L
    private var returningFromAdmin = false
    private lateinit var adapter: ChannelAdapter
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sections = try { ChannelRepository.load(this) } catch (_: Exception) { emptyList() }
        val appCtx = applicationContext
        Thread { StreamConfigStore.ensureLoaded(appCtx) }.start()

        adapter = ChannelAdapter { c ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastLaunchAt >= 800L) {
                lastLaunchAt = now
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_NAME, c.name)
                    .putExtra(PlayerActivity.EXTRA_URL, c.url)
                    .putExtra(PlayerActivity.EXTRA_REFERER, c.referer)
                    .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, c.id))
            }
        }
        val rv = findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = GridLayoutManager(this, resources.getInteger(R.integer.grid_span))
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        empty = findViewById(R.id.empty)

        rebuildChips(findViewById(R.id.chips))
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<Button>(R.id.adminButton).setOnClickListener {
            returningFromAdmin = true
            startActivity(Intent(this, AdminActivity::class.java))
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (returningFromAdmin) {
            returningFromAdmin = false
            recreate()
        }
    }

    private fun rebuildChips(group: ChipGroup) {
        group.removeAllViews()
        fun addChip(label: String, tag: String, checked: Boolean) {
            val chip = layoutInflater.inflate(R.layout.item_chip, group, false) as Chip
            chip.id = View.generateViewId(); chip.text = label; chip.tag = tag; chip.isChecked = checked
            group.addView(chip)
        }
        addChip(getString(R.string.all), "", true)
        sections.forEach { addChip(it.title, it.title, false) }
        group.setOnCheckedStateChangeListener { g, ids ->
            selected = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag as? String } ?: ""
            refresh()
        }
    }

    private fun refresh() {
        val list = sections.filter { selected.isEmpty() || it.title == selected }
            .flatMap { it.items }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        adapter.submitList(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}
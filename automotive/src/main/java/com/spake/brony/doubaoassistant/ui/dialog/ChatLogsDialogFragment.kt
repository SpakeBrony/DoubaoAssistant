package com.spake.brony.doubaoassistant.ui.dialog

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.data.local.ChatHistoryLogReader
import com.spake.brony.doubaoassistant.data.repository.AssistantPreferences
import com.spake.brony.doubaoassistant.databinding.DialogChatLogsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChatLogsDialogFragment : DialogFragment() {
    private var binding: DialogChatLogsBinding? = null
    private val rows = mutableListOf<String>()
    private lateinit var recordsAdapter: ArrayAdapter<String>
    private var table = "messages"
    private var lastId = 0L
    private var complete = false
    private var failed = false
    private var generation = 0
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_DoubaoAssistant)
        table = savedInstanceState?.getString("table")?.takeIf { it in ChatHistoryLogReader.tables } ?: "messages"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        DialogChatLogsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ui = checkNotNull(binding)
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density + .5f).toInt()
        recordsAdapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    typeface = Typeface.MONOSPACE
                    setTextSize(14f)
                    setTextColor(ContextCompat.getColor(context, R.color.voice_text))
                    setPadding(padding, padding, padding, padding)
                    setSingleLine(false)
                    setTextIsSelectable(true)
                }
        }
        ui.records.adapter = recordsAdapter
        val labels = listOf(getString(R.string.voice_logs_messages_table), getString(R.string.voice_logs_topics_table))
        val tablesAdapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_activated_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextSize(16f)
                    setTextColor(ContextCompat.getColor(context, R.color.voice_text))
                    setPadding(padding, padding, padding, padding)
                    setSingleLine(false)
                    setBackgroundColor(ContextCompat.getColor(context,
                        if (ChatHistoryLogReader.tables[position] == table) R.color.voice_selected else R.color.voice_panel))
                }
        }
        ui.tables.adapter = tablesAdapter
        ui.tables.setItemChecked(ChatHistoryLogReader.tables.indexOf(table), true)
        ui.tables.setOnItemClickListener { _, _, position, _ ->
            table = ChatHistoryLogReader.tables[position]
            tablesAdapter.notifyDataSetChanged()
            reload()
        }
        ui.records.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) = Unit
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount) loadMore()
            }
        })
        ui.back.setOnClickListener { dismiss() }
        ui.refresh.setOnClickListener { reload() }
        reload()
    }

    override fun onStart() {
        super.onStart()
        if (!AssistantPreferences(requireContext()).debugMode) { dismissAllowingStateLoss(); return }
        dialog?.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun reload() {
        generation++
        loadJob?.cancel()
        loadJob = null
        rows.clear()
        lastId = 0
        complete = false
        failed = false
        recordsAdapter.notifyDataSetChanged()
        binding?.records?.setSelection(0)
        binding?.recordsTitle?.text = table
        loadMore()
    }

    private fun loadMore() {
        val ui = binding ?: return
        if (loadJob?.isActive == true || complete || failed || !AssistantPreferences(requireContext()).debugMode) return
        val version = generation
        val selectedTable = table
        val context = requireContext().applicationContext
        ui.status.setText(R.string.voice_logs_loading)
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = ChatHistoryLogReader.read(context, selectedTable, lastId)
                if (version != generation) return@launch
                rows += page.items.map { it.text }
                page.items.lastOrNull()?.let { lastId = it.id }
                complete = !page.hasMore || rows.size.toLong() >= page.total
                recordsAdapter.notifyDataSetChanged()
                ui.recordsTitle.text = getString(R.string.voice_logs_count, selectedTable, rows.size, page.total)
                ui.status.setText(when {
                    rows.isEmpty() -> R.string.voice_logs_empty
                    complete -> R.string.voice_logs_complete
                    else -> R.string.voice_logs_more
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (version == generation) {
                    failed = true
                    ui.status.setText(R.string.voice_logs_failed)
                    Log.w("DoubaoLogs", "Cannot read chat table $selectedTable", error)
                }
            } finally {
                if (version == generation) loadJob = null
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("table", table)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        binding = null
        super.onDestroyView()
    }
}

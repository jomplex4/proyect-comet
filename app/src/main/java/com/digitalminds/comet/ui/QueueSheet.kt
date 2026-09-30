package com.digitalminds.comet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.Player
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitalminds.comet.R
import com.digitalminds.comet.databinding.SheetQueueBinding
import com.digitalminds.comet.service.PlaybackService

/**
 * "Playing Queue" bottom sheet: drag with the two lines to reorder, X to take a file out of
 * the queue (the file itself is not touched), tap to jump to it, and the repeat mode on top.
 */
object QueueSheet {

    val modeNames = arrayOf("Play in Order", "Repeat Current", "Shuffle", "Repeat Folder", "Play Once")
    val modeIcons = intArrayOf(
        R.drawable.ic_mode_order, R.drawable.ic_mode_repeat_one, R.drawable.ic_mode_shuffle,
        R.drawable.ic_mode_repeat_all, R.drawable.ic_mode_once
    )

    @SuppressLint("ClickableViewAccessibility")
    fun show(activity: Activity, svc: PlaybackService, onDismiss: () -> Unit = {}) {
        val player = svc.player
        val dialog = Dialog(activity, R.style.Theme_Comet_Sheet)
        val b = SheetQueueBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(b.root)
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        val density = activity.resources.displayMetrics.density
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()

        lateinit var touchHelper: ItemTouchHelper

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = player.mediaItemCount

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val v = holder.itemView
                val item = player.getMediaItemAt(position)
                val current = position == player.currentMediaItemIndex
                val title = v.findViewById<TextView>(R.id.title)
                val subtitle = v.findViewById<TextView>(R.id.subtitle)
                val eq = v.findViewById<EqBarsView>(R.id.eq)
                title.text = item.mediaMetadata.title ?: item.mediaMetadata.displayTitle ?: ""
                title.setTextColor(activity.getColor(if (current) R.color.red_hot else R.color.white))
                subtitle.text = item.mediaMetadata.artist ?: ""
                eq.visibility = if (current) View.VISIBLE else View.GONE
                eq.playing = current && player.isPlaying
                v.setOnClickListener {
                    val p = holder.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) {
                        svc.savePosition()
                        player.seekToDefaultPosition(p)
                        player.play()
                    }
                }
                v.findViewById<ImageButton>(R.id.btnRemove).setOnClickListener {
                    val p = holder.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) player.removeMediaItem(p)
                }
                v.findViewById<ImageView>(R.id.handle).setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                    false
                }
            }
        }

        var dragging = false
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val z = to.bindingAdapterPosition
                if (a == RecyclerView.NO_POSITION || z == RecyclerView.NO_POSITION) return false
                player.moveMediaItem(a, z)
                adapter.notifyItemMoved(a, z)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                dragging = actionState == ItemTouchHelper.ACTION_STATE_DRAG
                viewHolder?.itemView?.alpha = if (dragging) 0.85f else 1f
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                dragging = false
                adapter.notifyDataSetChanged()
            }
        })

        b.queueList.layoutManager = LinearLayoutManager(activity)
        b.queueList.adapter = adapter
        touchHelper.attachToRecyclerView(b.queueList)

        fun header() {
            b.queueTitle.text = "Playing Queue  ${player.mediaItemCount}"
            val mode = svc.repeatMode()
            b.btnMode.setImageResource(modeIcons.getOrElse(mode) { modeIcons[0] })
            b.btnMode.setColorFilter(activity.getColor(R.color.red_hot))
            b.modeLabel.text = modeNames.getOrElse(mode) { modeNames[0] }
            val rowH = (56 * density).toInt()
            b.queueList.layoutParams = b.queueList.layoutParams.apply {
                height = minOf(maxHeight, rowH * player.mediaItemCount.coerceAtLeast(1))
            }
        }
        header()
        b.queueList.scrollToPosition(player.currentMediaItemIndex.coerceAtLeast(0))

        b.btnClose.setOnClickListener { dialog.dismiss() }
        b.btnMode.setOnClickListener {
            svc.setRepeatMode((svc.repeatMode() + 1) % modeNames.size)
            header()
        }

        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (p.mediaItemCount == 0) {
                    dialog.dismiss()
                    return
                }
                if (!dragging) {
                    header()
                    adapter.notifyDataSetChanged()
                }
            }
        }
        player.addListener(listener)
        dialog.setOnDismissListener {
            player.removeListener(listener)
            onDismiss()
        }
        dialog.show()
    }
}

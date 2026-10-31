/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.clipboard

import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.db.ClipboardImagePayload
import com.osfans.trime.data.db.ClipboardImageRepository
import com.osfans.trime.data.db.PersistencePermitExpiredException
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.PrivacyModeState
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splitties.dimensions.dp

/** Image bytes never leave the repository; a page owns and clears every sampled bitmap. */
internal class ClipboardImageAdapter(
    private val service: TrimeInputMethodService,
    private val repository: ClipboardImageRepository,
    private val scope: ThemeScope,
    private val send: (ClipboardImagePayload, () -> Boolean, () -> Boolean, ClipboardImageDispatchGate) -> Boolean,
) : PagingDataAdapter<ClipboardImagePayload, ClipboardImageAdapter.Holder>(DIFF) {
    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ClipboardImagePayload>() {
            override fun areItemsTheSame(oldItem: ClipboardImagePayload, newItem: ClipboardImagePayload) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: ClipboardImagePayload, newItem: ClipboardImagePayload) = oldItem == newItem
        }
    }

    internal class Holder(val row: LinearLayout, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(row) {
        var imageJob: Job? = null
        var bitmap: Bitmap? = null
        fun clear() {
            imageJob?.cancel()
            imageJob = null
            image.setImageDrawable(null)
            // A hardware display list can still refer to a previously shown bitmap.
            // Drop our reference and let Android release it when rendering is done.
            bitmap = null
            label.text = ""
            row.contentDescription = null
            row.setOnClickListener(null)
            row.setOnLongClickListener(null)
        }
    }

    private val holders = mutableSetOf<Holder>()
    @Volatile private var generation = 0L
    @Volatile private var privacyEpoch: Long? = null
    private var editorToken = 0L
    private var popup: PopupMenu? = null
    private var activeDispatch: ClipboardImageDispatchGate? = null

    fun start(owner: Long): Boolean {
        stop()
        val epoch = PrivacyModeState.observeOrdinaryPersistence() ?: return false
        if (!service.isCurrentEditor(owner) || service.isPrivacyModeActive) return false
        editorToken = owner
        privacyEpoch = epoch
        generation++
        // Existing rows must be rebound with the new editor and privacy epoch;
        // their identities and positions have not changed.
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        return true
    }

    fun stop() {
        activeDispatch?.cancelBeforeClaim()
        activeDispatch = null
        generation++
        privacyEpoch = null
        popup?.dismiss()
        popup = null
        holders.forEach(Holder::clear)
    }

    private fun permits(ticket: Long, epoch: Long, owner: Long): Boolean =
        isClipboardImageTicketCurrent(ticket, generation, epoch, privacyEpoch, PrivacyModeState.observeOrdinaryPersistence(), owner, service.editorToken)

    private fun current(ticket: Long, epoch: Long, owner: Long): Boolean =
        permits(ticket, epoch, owner) && owner == editorToken && service.isCurrentEditor(owner) && !service.isPrivacyModeActive

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(80)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        }
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = TextView(ctx).apply { setTextColor(scope.colors.keyTextColor) }
        row.addView(image, LinearLayout.LayoutParams(ctx.dp(72), ctx.dp(72)))
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return Holder(row, image, label).also(holders::add)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holders.add(holder)
        holder.clear()
        val item = getItem(position) ?: return
        val epoch = privacyEpoch ?: return
        val ticket = generation
        val owner = editorToken
        if (!current(ticket, epoch, owner)) return
        fun stillBound(): Boolean = current(ticket, epoch, owner) &&
            holder.bindingAdapterPosition != RecyclerView.NO_POSITION && getItem(holder.bindingAdapterPosition)?.id == item.id
        holder.label.text = holder.row.context.getString(R.string.clipboard_image_description, item.width, item.height)
        holder.row.contentDescription = holder.label.text
        holder.row.setOnClickListener {
            if (stillBound()) {
                val previous = activeDispatch
                val gate = ClipboardImageDispatchGate()
                activeDispatch = gate
                if (!send(item, ::stillBound, { permits(ticket, epoch, owner) }, gate)) activeDispatch = previous
            }
        }
        holder.imageJob = service.lifecycleScope.launch {
            var loaded: Bitmap? = null
            try {
                loaded = repository.loadThumbnail(item.id) {
                    if (!permits(ticket, epoch, owner)) throw PersistencePermitExpiredException()
                }
                if (stillBound()) {
                    holder.bitmap = loaded
                    holder.image.setImageBitmap(loaded)
                    loaded = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: PersistencePermitExpiredException) {
                // The input/page owner changed while decoding.
            } catch (_: Exception) {
                if (stillBound()) {
                    holder.label.setText(R.string.clipboard_image_load_failed)
                    holder.row.contentDescription = holder.label.text
                    holder.row.setOnClickListener {
                        if (stillBound()) notifyItemChanged(holder.bindingAdapterPosition)
                    }
                }
            } finally {
                loaded?.recycle()
            }
        }
        holder.row.setOnLongClickListener { anchor ->
            if (!stillBound()) return@setOnLongClickListener true
            val menu = PopupMenu(anchor.context, anchor)
            menu.menu.add(if (item.pinned) R.string.simple_key_unpin else R.string.simple_key_pin).setOnMenuItemClickListener {
                if (stillBound()) service.lifecycleScope.launch {
                    try {
                        repository.setPinned(item.id, !item.pinned) {
                            if (!permits(ticket, epoch, owner)) throw PersistencePermitExpiredException()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: PersistencePermitExpiredException) {
                        // Stale actions never target the new editor.
                    } catch (_: Exception) {
                        if (stillBound()) Toast.makeText(holder.row.context, R.string.clipboard_image_action_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            menu.menu.add(R.string.delete).setOnMenuItemClickListener {
                if (stillBound()) service.lifecycleScope.launch {
                    try {
                        repository.deleteReady(item.id) {
                            if (!permits(ticket, epoch, owner)) throw PersistencePermitExpiredException()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: PersistencePermitExpiredException) {
                        // Stale actions never target the new editor.
                    } catch (_: Exception) {
                        if (stillBound()) Toast.makeText(holder.row.context, R.string.clipboard_image_action_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            popup?.dismiss()
            popup = menu
            menu.setOnDismissListener { if (popup === menu) popup = null }
            menu.show()
            true
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.clear()
        holders.remove(holder)
        super.onViewRecycled(holder)
    }
}

package com.dogu.livekit.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dogu.livekit.R
import com.dogu.livekit.call.CallManager
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.VideoTrack

class VideoAdapter : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    // identity, sid, track
    private val videoTracks = mutableListOf<Triple<String, String, VideoTrack>>()

    // Hangi kullanıcının kamerasının kapalı olduğunu tutuyoruz.
    // Identity -> true/false
    private val cameraStates = mutableMapOf<String, Boolean>()

    // RecyclerView referansı
    private var hostRecyclerView: RecyclerView? = null

    companion object {

        fun getRowCount(n: Int): Int = when {
            n <= 1 -> 1
            n == 2 -> 2
            n % 2 == 0 -> n / 2
            else -> 1 + (n - 1) / 2
        }

        fun getSpanSizeForPosition(
            n: Int,
            position: Int
        ): Int = when {
            n <= 2 -> 2
            n % 2 == 1 && position == 0 -> 2
            else -> 1
        }
    }

    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        val renderer: SurfaceViewRenderer =
            view.findViewById(R.id.videoRenderer)

        val blackOverlay: View =
            view.findViewById(R.id.blackOverlay)

        val cameraOffTv: TextView =
            view.findViewById(R.id.cameraOffText)

        val identityTv: TextView =
            view.findViewById(R.id.participantIdentity)

        var currentTrackSid: String? = null
    }

    override fun onAttachedToRecyclerView(
        recyclerView: RecyclerView
    ) {
        super.onAttachedToRecyclerView(recyclerView)
        hostRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(
        recyclerView: RecyclerView
    ) {
        super.onDetachedFromRecyclerView(recyclerView)

        if (hostRecyclerView == recyclerView) {
            hostRecyclerView = null
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): VideoViewHolder {

        val view = LayoutInflater
            .from(parent.context)
            .inflate(
                R.layout.item_remote_video,
                parent,
                false
            )

        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: VideoViewHolder,
        position: Int
    ) {

        val (identity, sid, track) =
            videoTracks[position]

        // Kullanıcı adını göster
        holder.identityTv.text = identity

        // ---------------------------------------------------------
        // VIDEO KUTUSUNUN YÜKSEKLİĞİNİ AYARLA
        // ---------------------------------------------------------

        val recyclerView = hostRecyclerView
        if (recyclerView != null && recyclerView.height > 0) {
            val totalHeight = recyclerView.height
            val rows = getRowCount(videoTracks.size)
            
            // Kenar boşluklarını kaldırdığımız için artık margin hesabı yapmıyoruz
            val rowHeight = (totalHeight / rows)

            val lp = holder.itemView.layoutParams
            if (lp != null && lp.height != rowHeight) {
                lp.height = rowHeight
                holder.itemView.layoutParams = lp
            }
        }

        // ---------------------------------------------------------
        // ESKİ TRACK KONTROLÜ
        // ---------------------------------------------------------

        if (
            holder.currentTrackSid != null &&
            holder.currentTrackSid != sid
        ) {

            try {
                track.removeRenderer(holder.renderer)
            } catch (e: Exception) {
                // Ignore
            }

            try {
                holder.renderer.release()
            } catch (e: Exception) {
                // Ignore
            }

            holder.currentTrackSid = null
        }

        // ---------------------------------------------------------
        // LIVEKIT RENDERER AYARLARI
        // ---------------------------------------------------------

        try {

            CallManager.room?.initVideoRenderer(
                holder.renderer
            )

            holder.renderer.setScalingType(
                livekit.org.webrtc.RendererCommon
                    .ScalingType.SCALE_ASPECT_FILL
            )

        } catch (e: Exception) {
            // Ignore
        }

        // ---------------------------------------------------------
        // TRACK'İ RENDERER'A BAĞLA
        // ---------------------------------------------------------

        holder.currentTrackSid = sid

        try {
            track.addRenderer(
                holder.renderer
            )
        } catch (e: Exception) {
            // Ignore
        }

        // ---------------------------------------------------------
        // KAMERA DURUMUNU UYGULA
        // ---------------------------------------------------------

        val cameraEnabled =
            cameraStates[identity] ?: true

        holder.blackOverlay.visibility =
            if (cameraEnabled) {
                View.GONE
            } else {
                View.VISIBLE
            }

        holder.cameraOffTv.visibility =
            if (cameraEnabled) {
                View.GONE
            } else {
                View.VISIBLE
            }
    }

    override fun onViewDetachedFromWindow(
        holder: VideoViewHolder
    ) {

        super.onViewDetachedFromWindow(holder)

        val position =
            holder.bindingAdapterPosition

        if (
            position != RecyclerView.NO_POSITION &&
            position < videoTracks.size
        ) {

            val (_, _, track) =
                videoTracks[position]

            try {
                track.removeRenderer(
                    holder.renderer
                )
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    override fun getItemCount(): Int {
        return videoTracks.size
    }

    // ---------------------------------------------------------
    // TRACK EKLE
    // ---------------------------------------------------------

    fun addTrack(
        identity: String,
        track: VideoTrack
    ): Boolean {

        val trackSid =
            track.sid

        if (trackSid.isNullOrEmpty()) {
            return false
        }

        // Aynı track zaten varsa tekrar ekleme
        val existingIndex =
            videoTracks.indexOfFirst {
                it.second == trackSid
            }

        if (existingIndex != -1) {

            // Mevcut kameranın durumunu koru
            if (!cameraStates.containsKey(identity)) {
                cameraStates[identity] = true
            }

            return false
        }

        // Yeni kullanıcı için kamera varsayılan olarak açık
        if (!cameraStates.containsKey(identity)) {
            cameraStates[identity] = true
        }

        videoTracks.add(
            Triple(
                identity,
                trackSid,
                track
            )
        )

        notifyDataSetChanged()

        return true
    }

    // ---------------------------------------------------------
    // TRACK SİL
    // ---------------------------------------------------------

    fun removeTrack(
        identity: String
    ) {

        val index =
            videoTracks.indexOfFirst {
                it.first == identity
            }

        if (index != -1) {

            val (_, _, track) =
                videoTracks[index]

            // Renderer bağlantısını kes
            val holder =
                hostRecyclerView
                    ?.findViewHolderForAdapterPosition(index)
                        as? VideoViewHolder

            if (holder != null) {

                try {
                    track.removeRenderer(
                        holder.renderer
                    )
                } catch (e: Exception) {
                    // Ignore
                }
            }

            videoTracks.removeAt(index)

            cameraStates.remove(identity)

            notifyDataSetChanged()
        }
    }

    // ---------------------------------------------------------
    // LOCAL / REMOTE KAMERA DURUMU
    // ---------------------------------------------------------

    fun setCameraEnabled(
        identity: String,
        enabled: Boolean
    ) {

        // Durumu kaydet
        cameraStates[identity] = enabled

        val index =
            videoTracks.indexOfFirst {
                it.first == identity
            }

        if (index == -1) {
            return
        }

        // O an ekranda görünen ViewHolder'ı bul
        val holder =
            hostRecyclerView
                ?.findViewHolderForAdapterPosition(index)
                    as? VideoViewHolder

        if (holder != null) {

            val visibility = if (enabled) View.GONE else View.VISIBLE
            holder.blackOverlay.visibility = visibility
            holder.cameraOffTv.visibility = visibility
        }
    }

    // ---------------------------------------------------------
    // HER ŞEYİ TEMİZLE
    // ---------------------------------------------------------

    fun clear() {

        // Renderer'ları temizle
        videoTracks.forEachIndexed { index, item ->

            val (_, _, track) =
                item

            val holder =
                hostRecyclerView
                    ?.findViewHolderForAdapterPosition(index)
                        as? VideoViewHolder

            if (holder != null) {

                try {
                    track.removeRenderer(
                        holder.renderer
                    )
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        videoTracks.clear()

        cameraStates.clear()

        notifyDataSetChanged()
    }
}
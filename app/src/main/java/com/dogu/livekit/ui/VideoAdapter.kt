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

    private val videoTracks = mutableListOf<Triple<String, String, VideoTrack>>() // identity, sid, track

    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val renderer: SurfaceViewRenderer = view.findViewById(R.id.videoRenderer)
        val identityTv: TextView = view.findViewById(R.id.participantIdentity)
        var currentTrackSid: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_remote_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val (identity, sid, track) = videoTracks[position]
        holder.identityTv.text = identity
        
        // Eğer bu renderer'a zaten farklı bir track bağlıysa temizle
        if (holder.currentTrackSid != null && holder.currentTrackSid != sid) {
             // Eski track'i bulup temizlemek zor olabilir ama renderer'ı temizleyebiliriz
             try { holder.renderer.release() } catch (e: Exception) {}
        }

        // Renderer'ı ilklendir
        try {
            CallManager.room?.initVideoRenderer(holder.renderer)
            holder.renderer.setScalingType(livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        } catch (e: Exception) {}
        
        holder.currentTrackSid = sid
        track.addRenderer(holder.renderer)
    }

    override fun onViewDetachedFromWindow(holder: VideoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION && position < videoTracks.size) {
            val (_, _, track) = videoTracks[position]
            track.removeRenderer(holder.renderer)
        }
    }

    override fun getItemCount() = videoTracks.size

    fun addTrack(identity: String, track: VideoTrack): Boolean {
        val trackSid = track.sid
        if (trackSid.isNullOrEmpty()) return false

        // Zaten listede var mı?
        val existingIndex = videoTracks.indexOfFirst { it.second == trackSid }
        if (existingIndex != -1) return false
        
        videoTracks.add(Triple(identity, trackSid, track))
        notifyItemInserted(videoTracks.size - 1)
        return true
    }

    fun removeTrack(identity: String) {
        val iterator = videoTracks.iterator()
        var index = 0
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.first == identity) {
                iterator.remove()
                notifyItemRemoved(index)
                return
            }
            index++
        }
    }

    fun clear() {
        videoTracks.clear()
        notifyDataSetChanged()
    }
}

package org.fossify.phone.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fossify.phone.R
import org.fossify.phone.engine.BackgroundSound

class BackgroundSoundAdapter(
    private val sounds: List<BackgroundSound>,
    private val onClick: (BackgroundSound) -> Unit
) : RecyclerView.Adapter<BackgroundSoundAdapter.ViewHolder>() {

    private val emojiMap = mapOf(
        BackgroundSound.NONE to "🔇",
        BackgroundSound.RAIN to "🌧️",
        BackgroundSound.CAFE to "☕",
        BackgroundSound.TRAFFIC to "🚗",
        BackgroundSound.OFFICE to "🏢",
        BackgroundSound.CROWD to "👥",
        BackgroundSound.NATURE to "🌲",
        BackgroundSound.WIND to "💨",
        BackgroundSound.WHITE_NOISE to "📻",
        BackgroundSound.FAN to "🌀"
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val backgroundEmoji: TextView = view.findViewById(R.id.background_emoji)
        val backgroundName: TextView = view.findViewById(R.id.background_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_background_sound, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sound = sounds[position]
        holder.backgroundEmoji.text = emojiMap[sound] ?: "🔊"
        holder.backgroundName.text = sound.displayName
        holder.itemView.setOnClickListener { onClick(sound) }
    }

    override fun getItemCount(): Int = sounds.size
}
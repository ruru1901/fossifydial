package org.fossify.phone.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.fossify.phone.R
import org.fossify.phone.engine.BackgroundSound
import org.fossify.phone.engine.BackgroundSoundEngine
import org.fossify.phone.services.VoiceProcessingService

class BackgroundPickerSheet : BottomSheetDialogFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var seekBar: SeekBar
    private var service: VoiceProcessingService? = null

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_background_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.background_grid)
        seekBar = view.findViewById(R.id.volume_seekbar)

        setupRecyclerView()
        setupSeekBar()
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = GridLayoutManager(context, 3)
        val sounds = BackgroundSound.values().toList()
        recyclerView.adapter = BackgroundSoundAdapter(sounds) { sound ->
            service?.setBackground(sound)
            dismissAllowingStateLoss()
        }
    }

    private fun setupSeekBar() {
        // Convert -30..0 dB to 0..100
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val db = progress - 30f  // -30 to 0
                    service?.setBackgroundVolume(db)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Initialize at -15dB
        seekBar.progress = 15
    }

    companion object {
        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            service: VoiceProcessingService
        ) {
            BackgroundPickerSheet().apply {
                this.service = service
                show(fragmentManager, "BackgroundPicker")
            }
        }
    }
}

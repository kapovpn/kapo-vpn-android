package org.amnezia.awg.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.amnezia.awg.R
import org.amnezia.awg.databinding.FragmentStatsBinding
import org.amnezia.awg.databinding.ItemToggleBinding

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    /** Multi-Hop needs its subtitle rewritten whenever the hop count changes. */
    private var multiHopBinding: ItemToggleBinding? = null

    /** Redraws everything sourced from KapoState. Registered as a listener so the
     *  numbers stay correct even while this tab sits hidden behind Home. */
    private val onStateChanged: () -> Unit = { render() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToggle(binding.toggleKillSwitch, "Kill Switch", "Block traffic if VPN drops", KapoState.killSwitch) {
            KapoState.killSwitch = it
        }
        setupToggle(binding.toggleDns, "DNS Protection", "Encrypted DoH / DoQ", KapoState.dnsProtection) {
            KapoState.dnsProtection = it
        }

        // Multi-Hop is live: this row is a quick 1<->2 hop switch (3-hop lives on
        // the Home screen). Flipping it reconnects through the extra country.
        multiHopBinding = binding.toggleMultiHop
        binding.toggleMultiHop.tvToggleName.text = "Multi-Hop"
        binding.toggleMultiHop.tvToggleDesc.text = multiHopDesc()
        binding.toggleMultiHop.root.alpha = 1f
        setToggleVisual(binding.toggleMultiHop, KapoState.multiHop, animate = false)
        binding.toggleMultiHop.root.setOnClickListener {
            val on = KapoState.hopCount <= 1
            KapoState.setHopCount(if (on) 2 else 1)
            setToggleVisual(binding.toggleMultiHop, on, animate = true)
        }

        setupToggle(binding.toggleAdBlock, "Ad Blocking", "Blocks web ads & trackers", KapoState.adBlock) {
            KapoState.setAdBlock(it)
        }

        // AmneziaWG is the live protocol. VLESS/Reality and Auto aren't built yet,
        // so they stay visible as tasteful "SOON" rows (slightly dimmed) that
        // explain themselves on tap instead of silently doing nothing.
        binding.protoAmneziaWG.setOnClickListener { selectProto(0) }
        val comingSoon = View.OnClickListener {
            Toast.makeText(requireContext(), "This protocol is coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.protoVless.alpha = 0.8f
        binding.protoAuto.alpha = 0.8f
        binding.protoVless.setOnClickListener(comingSoon)
        binding.protoAuto.setOnClickListener(comingSoon)
        KapoState.protocol = 0

        KapoState.addListener(onStateChanged)
        render()
        updateProtoUI()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun multiHopDesc(): String =
        if (KapoState.hopCount >= 3) "Routing through 3 countries"
        else if (KapoState.hopCount == 2) "Routing through 2 countries"
        else "Route through a 2nd country"

    /** Everything that reflects shared state, in one place. */
    private fun render() {
        val b = _binding ?: return

        b.tvSentTotal.text = KapoState.toMegabytes(KapoState.uploadBytes)
        b.tvRecvTotal.text = KapoState.toMegabytes(KapoState.downloadBytes)

        multiHopBinding?.let {
            it.tvToggleDesc.text = multiHopDesc()
            setToggleVisual(it, KapoState.multiHop, animate = false)
        }
    }

    private fun setupToggle(
        toggleBinding: ItemToggleBinding,
        name: String,
        desc: String,
        initialState: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        toggleBinding.tvToggleName.text = name
        toggleBinding.tvToggleDesc.text = desc
        setToggleVisual(toggleBinding, initialState, animate = false)
        toggleBinding.root.setOnClickListener {
            val next = !(toggleBinding.root.tag as? Boolean ?: false)
            setToggleVisual(toggleBinding, next, animate = true)
            onToggle(next)
        }
    }

    private fun setToggleVisual(tb: ItemToggleBinding, isOn: Boolean, animate: Boolean) {
        tb.root.tag = isOn
        updateToggleUI(tb.toggleSwitch, tb.toggleThumb, isOn, animate)
    }

    private fun updateToggleUI(switch: FrameLayout, thumb: View, isOn: Boolean, animate: Boolean) {
        val density = resources.displayMetrics.density
        // travel = switch width (44dp) - thumb (16dp) - 2 * margin (4dp) = 24dp
        val travelPx = 24 * density
        val target = if (isOn) travelPx else 0f

        switch.setBackgroundResource(if (isOn) R.drawable.bg_toggle_on else R.drawable.bg_toggle_off)
        thumb.setBackgroundResource(if (isOn) R.drawable.bg_toggle_thumb_on else R.drawable.bg_toggle_thumb)

        if (animate) {
            ObjectAnimator.ofFloat(thumb, "translationX", target).apply { duration = 220; start() }
        } else {
            thumb.translationX = target
        }
    }

    private fun selectProto(id: Int) {
        KapoState.protocol = id
        updateProtoUI()
    }

    private fun updateProtoUI() {
        val p = KapoState.protocol
        binding.radioAmneziaWG.setBackgroundResource(
            if (p == 0) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected
        )
        binding.radioVless.setBackgroundResource(
            if (p == 1) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected
        )
        binding.radioAuto.setBackgroundResource(
            if (p == 2) R.drawable.bg_radio_selected else R.drawable.bg_radio_unselected
        )
    }

    override fun onDestroyView() {
        KapoState.removeListener(onStateChanged)
        multiHopBinding = null
        _binding = null
        super.onDestroyView()
    }
}

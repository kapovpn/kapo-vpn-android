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

        // Kill Switch and DNS Protection are NOT user-togglable: the tunnel is
        // always built full-tunnel (AllowedIPs = 0.0.0.0/0, ::/0) with
        // setBlocking(true) in GoBackend, so non-tunnel traffic is already
        // blocked outright whenever the interface is up, and DNS is always the
        // server-assigned resolver from enrollment - neither depends on a
        // setting. Leaving these interactive (as they used to be) let a user
        // believe they'd turned protection off when nothing actually read the
        // switch. Same fix already shipped on the Windows client - see its
        // MainWindow.xaml comment for the same reasoning. Shown as status here.
        setupAlwaysOn(binding.toggleKillSwitch, "Kill Switch", "Always on - blocks traffic if the tunnel drops")
        setupAlwaysOn(binding.toggleDns, "DNS Protection", "Always on - DNS locked to the assigned resolver")

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
            // Turning Ad Blocking off also turns Adult Content off (it depends
            // on Ad Blocking's resolver) - reflect that in the row's visual too.
            setToggleVisual(binding.toggleBlockAdult, KapoState.blockAdult, animate = true)
        }

        // Depends on Ad Blocking's resolver (chained behind it on the node), so
        // turning this on also turns Ad Blocking on - one tap does the right thing.
        setupToggle(binding.toggleBlockAdult, "Block Adult Content", "Also blocks adult sites", KapoState.blockAdult) {
            KapoState.setBlockAdult(it)
            setToggleVisual(binding.toggleAdBlock, KapoState.adBlock, animate = true)
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

    /** A protection row that's always enforced and can't actually be turned
     *  off - rendered permanently ON with no click handler, instead of a real
     *  toggle, so it can never lie about its own state. */
    private fun setupAlwaysOn(toggleBinding: ItemToggleBinding, name: String, desc: String) {
        toggleBinding.tvToggleName.text = name
        toggleBinding.tvToggleDesc.text = desc
        setToggleVisual(toggleBinding, isOn = true, animate = false)
        toggleBinding.root.isClickable = false
        toggleBinding.root.alpha = 0.85f
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

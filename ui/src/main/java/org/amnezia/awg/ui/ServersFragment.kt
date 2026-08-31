package org.amnezia.awg.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import org.amnezia.awg.databinding.FragmentServersBinding
import org.amnezia.awg.ui.adapter.ServerAdapter
import org.amnezia.awg.model.Server

class ServersFragment : Fragment() {

    private var _binding: FragmentServersBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ServerAdapter
    private var selectedServerId = 2 // Iceland is default (index 2)

    // Server list: Iceland is REAL and active, others are coming soon
    private val allServers = listOf(
        Server(id=0,  flag="\uD83C\uDDE9\uD83C\uDDEA", city="Frankfurt",  country="Germany",      code="DE", ping=8,   load=34, region="Europe",   active=false),
        Server(id=1,  flag="\uD83C\uDDF3\uD83C\uDDF1", city="Amsterdam",  country="Netherlands",  code="NL", ping=12,  load=41, region="Europe",   active=false),
        Server(id=2,  flag="\uD83C\uDDEE\uD83C\uDDF8", city="Reykjavik",  country="Iceland",      code="IS", ping=45,  load=18, region="Europe",   active=true),
        Server(id=9,  flag="\uD83C\uDDEB\uD83C\uDDEE", city="Helsinki",   country="Finland",      code="FI", ping=52,  load=15, region="Europe",   active=true),
        Server(id=3,  flag="\uD83C\uDDE8\uD83C\uDDED", city="Zurich",     country="Switzerland",  code="CH", ping=19,  load=27, region="Europe",   active=false),
        Server(id=4,  flag="\uD83C\uDDF8\uD83C\uDDEA", city="Stockholm",  country="Sweden",       code="SE", ping=28,  load=22, region="Europe",   active=false),
        Server(id=5,  flag="\uD83C\uDDFA\uD83C\uDDF8", city="New York",   country="United States",code="US", ping=89,  load=55, region="Americas", active=false),
        Server(id=6,  flag="\uD83C\uDDE8\uD83C\uDDE6", city="Toronto",    country="Canada",       code="CA", ping=94,  load=39, region="Americas", active=false),
        Server(id=10, flag="\uD83C\uDDF2\uD83C\uDDFE", city="Kuala Lumpur", country="Malaysia",   code="MY", ping=182, load=16, region="Asia",     active=true),
        Server(id=7,  flag="\uD83C\uDDF8\uD83C\uDDEC", city="Singapore",  country="Singapore",    code="SG", ping=178, load=62, region="Asia",     active=false),
        Server(id=8,  flag="\uD83C\uDDEF\uD83C\uDDF5", city="Tokyo",      country="Japan",        code="JP", ping=195, load=44, region="Asia",     active=false),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ServerAdapter(
            items = buildDisplayList(allServers),
            selectedId = selectedServerId,
            onServerClick = { server ->
                if (server.active) {
                    selectedServerId = server.id
                    adapter.setSelected(server.id)
                    // Tell the rest of the app which node to enroll with; Home
                    // reconnects automatically if a tunnel is already up.
                    KapoState.setSelectedServer(
                        node = server.code.lowercase(),
                        city = server.city,
                        code = server.code,
                        ping = "${server.ping} MS",
                        flag = server.flag
                    )
                }
            }
        )

        binding.rvServers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvServers.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val query = s.toString().lowercase()
                val filtered = if (query.isEmpty()) allServers else {
                    allServers.filter {
                        it.city.lowercase().contains(query) ||
                        it.country.lowercase().contains(query) ||
                        it.code.lowercase().contains(query)
                    }
                }
                adapter.updateList(buildDisplayList(filtered))
            }
        })
    }

    // Active servers first (grouped by region), then a single "Coming soon"
    // section with everything that isn't live yet - so working locations are
    // always on top and placeholders sit at the bottom.
    private fun buildDisplayList(servers: List<Server>): List<Any> {
        val result = mutableListOf<Any>()

        var lastRegion = ""
        servers.filter { it.active }.forEach { server ->
            if (server.region != lastRegion) {
                result.add(server.region) // String = region header
                lastRegion = server.region
            }
            result.add(server)
        }

        val soon = servers.filter { !it.active }
        if (soon.isNotEmpty()) {
            result.add("Coming soon")
            soon.forEach { result.add(it) }
        }
        return result
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package org.amnezia.awg.model

data class Server(
    val id: Int,
    val flag: String,
    val city: String,
    val country: String,
    val code: String,
    val ping: Int,      // ms, -1 if not yet measured
    val load: Int,      // percentage 0-100
    val region: String,
    val active: Boolean // true = connectable, false = coming soon
)

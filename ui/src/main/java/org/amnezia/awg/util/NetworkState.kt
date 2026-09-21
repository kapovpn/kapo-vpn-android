/*
 * Copyright © 2025 AmneziaWG. All Rights conneserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.delay

private const val TAG = "KapoVPN/NetworkState"
private const val BIND_NETWORK_RETRY_ATTEMPTS = 5

enum class NetworkType {
    NONE, WIFI, CELLULAR, OTHER
}

class NetworkState(
    private val context: Context,
    private val onNetworkChange: (NetworkType, NetworkType) -> Unit
) {
    private var currentNetwork: Network? = null
    private var currentNetworkType: NetworkType = NetworkType.NONE
    private var validated: Boolean = false
    private var isListenerBound = false
    // Distinguishes "app just started, nothing to reconnect from" (no callback
    // needed) from "we had a network, lost it entirely, and are now recovering"
    // (a real change onNetworkChange must see) - onLost() resets currentNetwork
    // to null just like the pre-bind state, so without this flag every recovery
    // from a total outage (airplane mode, a dead zone, WiFi dropping before
    // cellular takes over) looked identical to first-ever startup and silently
    // never reconnected the tunnel.
    private var hasEverConnected: Boolean = false

    private val handler: Handler by lazy {
        Handler(Looper.getMainLooper())
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService<ConnectivityManager>()!!
    }

    private val networkRequest: NetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NET_CAPABILITY_INTERNET)
            .addTransportType(TRANSPORT_WIFI)
            .addTransportType(TRANSPORT_CELLULAR)
            .build()
    }

    private val networkCallback: NetworkCallback by lazy {
        object : NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "onAvailable: $network")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val networkType = getNetworkType(networkCapabilities)
                val isValidated = networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)
                Log.d(TAG, "onCapabilitiesChanged: network=$network, type=$networkType, validated=$isValidated")
                checkNetworkState(network, networkCapabilities)
            }

            private fun checkNetworkState(network: Network, networkCapabilities: NetworkCapabilities) {
                val newNetworkType = getNetworkType(networkCapabilities)
                val isValidated = networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)

                if (currentNetwork == null) {
                    currentNetwork = network
                    currentNetworkType = newNetworkType
                    validated = isValidated

                    if (!hasEverConnected) {
                        // Genuinely the first network callback since the app
                        // started - nothing was ever up to reconnect.
                        Log.d(TAG, "Initial network: $newNetworkType, validated: $validated")
                        if (isValidated) hasEverConnected = true
                    } else {
                        // Recovering from a total outage (onLost cleared
                        // currentNetwork to null) - treat exactly like a normal
                        // network change so a tunnel that was up before the
                        // outage gets reconnected.
                        Log.d(TAG, "Network recovered after total loss: NONE -> $newNetworkType, validated: $validated")
                        if (isValidated) {
                            handler.post {
                                onNetworkChange(NetworkType.NONE, newNetworkType)
                            }
                        }
                    }
                } else {
                    if (currentNetwork != network || currentNetworkType != newNetworkType) {
                        // Network changed (e.g., WiFi to Cellular or vice versa)
                        val oldNetworkType = currentNetworkType
                        currentNetwork = network
                        currentNetworkType = newNetworkType
                        validated = false

                        Log.d(TAG, "Network changed: $oldNetworkType -> $newNetworkType")

                        if (isValidated) {
                            validated = true
                            hasEverConnected = true
                            handler.post {
                                onNetworkChange(oldNetworkType, newNetworkType)
                            }
                        }
                    } else if (!validated && isValidated) {
                        // Same network became validated
                        validated = true
                        hasEverConnected = true
                        Log.d(TAG, "Network validated: $newNetworkType")
                        handler.post {
                            onNetworkChange(currentNetworkType, newNetworkType)
                        }
                    }
                }
            }

            private fun getNetworkType(capabilities: NetworkCapabilities): NetworkType {
                return when {
                    capabilities.hasTransport(TRANSPORT_WIFI) -> NetworkType.WIFI
                    capabilities.hasTransport(TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                    else -> NetworkType.OTHER
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "onLost: $network, currentNetwork: $currentNetwork")
                if (currentNetwork == network) {
                    val oldType = currentNetworkType
                    currentNetwork = null
                    currentNetworkType = NetworkType.NONE
                    validated = false
                    Log.d(TAG, "Network lost: $oldType -> NONE")
                    handler.post {
                        onNetworkChange(oldType, NetworkType.NONE)
                    }
                }
            }
        }
    }

    suspend fun bindNetworkListener() {
        if (isListenerBound) {
            Log.d(TAG, "Network listener already bound")
            return
        }

        // Check if we have the required permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "ACCESS_NETWORK_STATE permission not granted, cannot bind network listener")
            return
        }

        Log.i(TAG, "Binding network listener (SDK ${Build.VERSION.SDK_INT})")

        var attemptCount = 0
        while (true) {
            try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        connectivityManager.registerBestMatchingNetworkCallback(networkRequest, networkCallback, handler)
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                        connectivityManager.registerNetworkCallback(networkRequest, networkCallback, handler)
                    }
                    else -> {
                        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
                    }
                }
                isListenerBound = true
                Log.i(TAG, "Network listener bound successfully")
                break
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to bind network listener: $e")
                // Android 11 bug: https://issuetracker.google.com/issues/175055271
                if (e.message?.startsWith("Package android does not belong to") == true) {
                    if (++attemptCount >= BIND_NETWORK_RETRY_ATTEMPTS) {
                        throw e
                    }
                    delay(1000)
                    continue
                } else {
                    throw e
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind network listener", e)
                throw e
            }
        }
    }

    fun unbindNetworkListener() {
        if (!isListenerBound) {
            Log.d(TAG, "Network listener not bound, nothing to unbind")
            return
        }
        Log.d(TAG, "Unbind network listener")

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network listener unbound successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while unbinding network listener", e)
        } catch (e: IllegalArgumentException) {
            // Callback was not registered, ignore
            Log.w(TAG, "Callback was not registered", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind network listener", e)
        }

        isListenerBound = false
        currentNetwork = null
        currentNetworkType = NetworkType.NONE
        validated = false
        hasEverConnected = false
    }

    fun getCurrentNetworkType(): NetworkType = currentNetworkType

    fun isConnected(): Boolean = validated && currentNetworkType != NetworkType.NONE
}


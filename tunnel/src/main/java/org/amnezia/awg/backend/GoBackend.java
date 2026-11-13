/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.backend;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import org.amnezia.awg.backend.BackendException.Reason;
import org.amnezia.awg.backend.Tunnel.State;
import org.amnezia.awg.util.SharedLibraryLoader;
import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.InetEndpoint;
import org.amnezia.awg.config.InetNetwork;
import org.amnezia.awg.config.Peer;
import org.amnezia.awg.crypto.Key;
import org.amnezia.awg.crypto.KeyFormatException;
import org.amnezia.awg.util.NonNullForAll;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import static org.amnezia.awg.GoBackend.*;

/**
 * Implementation of {@link Backend} that uses the amneziawg-go userspace implementation to provide
 * AmneziaWG tunnels.
 */
@NonNullForAll
public final class GoBackend implements Backend {
    private static final int DNS_RESOLUTION_RETRIES = 10;
    private static final String TAG = "AmneziaWG/GoBackend";
    @Nullable private static AlwaysOnCallback alwaysOnCallback;
    private static GhettoCompletableFuture<VpnService> vpnService = new GhettoCompletableFuture<>();
    private final Context context;
    // Read without a lock by getState/getStatistics/getLastHandshake and the status job,
    // so volatile: a reader sees a teardown's -1 at once, never a stale handle.
    @Nullable private volatile Config currentConfig;
    @Nullable private volatile Tunnel currentTunnel;
    private volatile int currentTunnelHandle = -1;
    // One state change at a time. Callers (network change, connect, a location switch, the
    // Quick Settings tile, the restore at start-up) each run on their own IO thread, and two
    // UPs used to both pass the "already up" check, both turn awg on, and leave one handle
    // running that nothing owned or ever turned off.
    private final Object setStateLock = new Object();
    // Held only while the awg handle and the fields above change hands, and by the service's
    // onDestroy (main thread), so a teardown from Android can't interleave with a turn-on.
    // Never held across anything slow (DNS, waiting for the service, establish()).
    private final Object stateLock = new Object();
    @Nullable private Thread statusThread;
    @Nullable private StatusCallback statusCallback;

    /**
     * Public constructor for GoBackend.
     *
     * @param context An Android {@link Context}
     */
    public GoBackend(final Context context) {
        SharedLibraryLoader.loadSharedLibrary(context, "wg-go");
        this.context = context;
    }

    /**
     * Set a {@link AlwaysOnCallback} to be invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     *
     * @param cb Callback to be invoked
     */
    public static void setAlwaysOnCallback(final AlwaysOnCallback cb) {
        alwaysOnCallback = cb;
    }



    /**
     * Method to get the names of running tunnels.
     *
     * @return A set of string values denoting names of running tunnels.
     */
    @Override
    public Set<String> getRunningTunnelNames() {
        final Tunnel tunnel = currentTunnel;
        if (tunnel != null) {
            final Set<String> runningTunnels = new ArraySet<>();
            runningTunnels.add(tunnel.getName());
            return runningTunnels;
        }
        return Collections.emptySet();
    }

    /**
     * Get the associated {@link State} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to examine the state of.
     * @return {@link State} associated with the given tunnel.
     */
    @Override
    public State getState(final Tunnel tunnel) {
        return currentTunnel == tunnel ? State.UP : State.DOWN;
    }

    /**
     * Get the associated {@link Statistics} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to retrieve statistics for.
     * @return {@link Statistics} associated with the given tunnel.
     */
    @Override
    public Statistics getStatistics(final Tunnel tunnel) {
        final Statistics stats = new Statistics();
        final int handle = currentTunnelHandle;
        if (tunnel != currentTunnel || handle == -1)
            return stats;
        final String config = awgGetConfig(handle);
        if (config == null)
            return stats;
        Key key = null;
        long rx = 0;
        long tx = 0;
        long latestHandshakeMSec = 0;
        for (final String line : config.split("\\n")) {
            if (line.startsWith("public_key=")) {
                if (key != null)
                    stats.add(key, rx, tx, latestHandshakeMSec);
                rx = 0;
                tx = 0;
                latestHandshakeMSec = 0;
                try {
                    key = Key.fromHex(line.substring(11));
                } catch (final KeyFormatException ignored) {
                    key = null;
                }
            } else if (line.startsWith("rx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    rx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    rx = 0;
                }
            } else if (line.startsWith("tx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    tx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    tx = 0;
                }
            } else if (line.startsWith("last_handshake_time_sec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(24)) * 1000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            } else if (line.startsWith("last_handshake_time_nsec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(25)) / 1000000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            }
        }
        if (key != null)
            stats.add(key, rx, tx, latestHandshakeMSec);
        return stats;
    }


    /**
     * Get the last handshake time for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to retrieve the last handshake time for.
     * @return Last handshake time in seconds (>=0), -1 if no handshake found, -2 on error, -3 if tunnel not active.
     */
    @Override
    public long getLastHandshake(final Tunnel tunnel) {
        final int handle = currentTunnelHandle;
        if (tunnel != currentTunnel || handle == -1)
            return -3; // Tunnel not active
        final String config = awgGetConfig(handle);
        if (config == null) {
            Log.e(TAG, "Failed to get tunnel config");
            return -2;
        }

        for (final String line : config.split("\\n")) {
            if (line.startsWith("last_handshake_time_sec=")) {
                try {
                    return Long.parseLong(line.substring(24));
                } catch (final NumberFormatException ignored) {
                    Log.e(TAG, "Failed to parse last_handshake_time_sec");
                    return -2;
                }
            }
        }

        Log.e(TAG, "Failed to get last_handshake_time_sec");
        return -1;
    }

    /**
     * Set a callback to be notified when connection status changes.
     *
     * @param callback The callback to invoke on status change
     */
    public void setStatusCallback(@Nullable final StatusCallback callback) {
        this.statusCallback = callback;
    }

    /**
     * Launch a background thread to poll handshake status and determine connection state.
     * This is called after tunnel creation to wait for the first successful handshake.
     */
    private void launchStatusJob() {
        stopStatusJob();
        Log.d(TAG, "Launch status job");
        statusThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                final long lastHandshake = getLastHandshake(currentTunnel);

                // Check if tunnel is no longer active (race condition protection)
                if (lastHandshake == -3L) {
                    Log.d(TAG, "Tunnel is no longer active, stopping status job");
                    break;
                }

                // 0 means no handshake yet, wait and retry
                if (lastHandshake == 0L) {
                    try {
                        Thread.sleep(1000);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                // Only positive handshake time indicates successful connection
                // -1 may be returned if unable to parse output (doesn't mean no connection)
                // -2 indicates command execution error (also doesn't mean no connection)
                if (lastHandshake > 0L) {
                    if (statusCallback != null) {
                        statusCallback.onStatusChanged(true);
                    }
                    break;
                }

                // For -1 or -2, retry after delay instead of reporting disconnected
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            statusThread = null;
        }, "StatusJob");
        statusThread.start();
    }

    /**
     * Stop the status polling thread if running.
     */
    private void stopStatusJob() {
        if (statusThread != null) {
            statusThread.interrupt();
            statusThread = null;
        }
    }

    /**
     * Get the version of the underlying amneziawg-go library.
     *
     * @return {@link String} value of the version of the amneziawg-go library.
     */
    @Override
    public String getVersion() {
        return awgVersion();
    }

    /**
     * Change the state of a given {@link Tunnel}, optionally applying a given {@link Config}.
     *
     * @param tunnel The tunnel to control the state of.
     * @param state  The new state for this tunnel. Must be {@code UP}, {@code DOWN}, or
     *               {@code TOGGLE}.
     * @param config The configuration for this tunnel, may be null if state is {@code DOWN}.
     * @return {@link State} of the tunnel after state changes are applied.
     * @throws Exception Exception raised while changing tunnel state.
     */
    @Override
    public State setState(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
        synchronized (setStateLock) {
            return setStateLocked(tunnel, state, config);
        }
    }

    private State setStateLocked(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
        final State originalState = getState(tunnel);

        if (state == State.TOGGLE)
            state = originalState == State.UP ? State.DOWN : State.UP;
        if (state == originalState && tunnel == currentTunnel && config == currentConfig)
            return originalState;
        // A new config for the tunnel that is running (a network change, KapoVpn.connect on a
        // new location or setting): rebuild it make-before-break. Taking it down first, as
        // below, left a moment with no tun at all, and with Android's "Block connections
        // without VPN" off apps used the plain network with the real IP then. The service has
        // to be running for it; if it is on its way out, the old way below still works.
        if (state == State.UP && tunnel == currentTunnel && currentTunnelHandle != -1 && vpnService.isDone()) {
            rebuildInPlace(tunnel, config);
            return getState(tunnel);
        }
        if (state == State.UP) {
            final Config originalConfig = currentConfig;
            final Tunnel originalTunnel = currentTunnel;
            if (currentTunnel != null)
                setStateInternal(currentTunnel, null, State.DOWN);
            try {
                setStateInternal(tunnel, config, state);
            } catch (final Exception e) {
                if (originalTunnel != null)
                    setStateInternal(originalTunnel, originalConfig, State.UP);
                throw e;
            }
        } else if (state == State.DOWN && tunnel == currentTunnel) {
            setStateInternal(tunnel, null, State.DOWN);
        }
        return getState(tunnel);
    }

    private void setStateInternal(final Tunnel tunnel, @Nullable final Config config, final State state)
            throws Exception {
        Log.i(TAG, "Bringing tunnel " + tunnel.getName() + ' ' + state);

        if (state == State.UP) {
            if (config == null)
                throw new BackendException(Reason.TUNNEL_MISSING_CONFIG);

            if (VpnService.prepare(context) != null)
                throw new BackendException(Reason.VPN_NOT_AUTHORIZED);

            final VpnService service;
            if (!vpnService.isDone()) {
                Log.d(TAG, "Requesting to start VpnService");
                context.startService(new Intent(context, VpnService.class));
            }

            try {
                service = vpnService.get(2, TimeUnit.SECONDS);
            } catch (final TimeoutException e) {
                final Exception be = new BackendException(Reason.UNABLE_TO_START_VPN);
                be.initCause(e);
                throw be;
            }
            service.setOwner(this);

            if (currentTunnelHandle != -1) {
                Log.w(TAG, "Tunnel already up");
                return;
            }


            resolveEndpoints(config);

            // Build config
            final String goConfig = config.toAwgUserspaceString();

            // Create the vpn tunnel with android API
            final VpnService.Builder builder = newBuilder(service, tunnel, config);
            try (final ParcelFileDescriptor tun = builder.establish()) {
                if (tun == null)
                    throw new BackendException(Reason.TUN_CREATION_ERROR);
                Log.d(TAG, "Go backend " + awgVersion());
                synchronized (stateLock) {
                    // Android destroyed the service while this was building (another VPN took
                    // over, or KAPO was switched off in Android's settings): nothing to attach
                    // the tunnel to. The new tun closes with the try-with-resources.
                    if (!vpnService.isDone() || vpnService.get(0, TimeUnit.NANOSECONDS) != service)
                        throw new BackendException(Reason.UNABLE_TO_START_VPN);
                    final int handle = awgTurnOn(tunnel.getName(), tun.detachFd(), goConfig);
                    if (handle < 0)
                        throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, handle);
                    currentTunnelHandle = handle;
                    currentTunnel = tunnel;
                    currentConfig = config;
                    service.protect(awgGetSocketV4(handle));
                    service.protect(awgGetSocketV6(handle));
                }
            }

            launchStatusJob();
        } else {
            if (currentTunnelHandle == -1) {
                Log.w(TAG, "Tunnel already down");
                return;
            }
            stopStatusJob();
            synchronized (stateLock) {
                final int handleToClose = currentTunnelHandle;
                currentTunnel = null;
                currentTunnelHandle = -1;
                currentConfig = null;
                if (handleToClose != -1)
                    awgTurnOff(handleToClose);
            }
            try {
                vpnService.get(0, TimeUnit.NANOSECONDS).stopSelf();
            } catch (final TimeoutException ignored) { }
        }

        tunnel.onStateChange(state);
    }

    /**
     * Rebuild the running tunnel on a new config, make-before-break:
     * <ol>
     * <li>establish() the new tun while the old one still runs. Android moves the routes over to
     * the new interface before it drops the old one, so until awg reads the new tun, packets
     * wait in it or are dropped. They never go out on the plain network.</li>
     * <li>Turn the old awg handle off. Go owns the old tun's fd since it was detached at turn-on,
     * and closing the device closes it.</li>
     * <li>Turn awg on with the new tun and protect() its sockets.</li>
     * </ol>
     * The old handle goes off before the new one comes on, so only one awg handle ever runs
     * (two with the same key would take turns being the one the server answers). No stopSelf()
     * and no DOWN in between: only a real DOWN stops the service.
     * <p>
     * Anything that fails before establish() has returned a tun leaves the old tunnel running as
     * it was, and the exception goes to the caller, which keeps the old config. If the new handle
     * fails to start, the config that was running goes back on the new tun, which a second hold on
     * it keeps open, so even then there is no moment without a tun.
     */
    private void rebuildInPlace(final Tunnel tunnel, @Nullable final Config config) throws Exception {
        Log.i(TAG, "Rebuilding tunnel " + tunnel.getName() + " in place");
        if (config == null)
            throw new BackendException(Reason.TUNNEL_MISSING_CONFIG);
        if (VpnService.prepare(context) != null)
            throw new BackendException(Reason.VPN_NOT_AUTHORIZED);
        final VpnService service = vpnService.get(0, TimeUnit.NANOSECONDS);
        service.setOwner(this);

        // Through the old tunnel, which is still up.
        resolveEndpoints(config);
        final String goConfig = config.toAwgUserspaceString();
        final VpnService.Builder builder = newBuilder(service, tunnel, config);

        // Outside the lock: a binder call into Android, which onDestroy shouldn't wait on. Null:
        // Android refused the new interface, and the old one is untouched. Keep it.
        final ParcelFileDescriptor tun = builder.establish();
        if (tun == null)
            throw new BackendException(Reason.TUN_CREATION_ERROR);
        try {
            synchronized (stateLock) {
                swapHandles(service, tunnel, config, goConfig, tun);
            }
        } finally {
            // Does nothing once the fd was handed to Go (detachFd()): it is for a throw before it.
            tun.close();
        }
    }

    /** The part of {@link #rebuildInPlace} that turns the old handle off and the new one on. */
    private void swapHandles(final VpnService service, final Tunnel tunnel, final Config config, final String goConfig,
                             final ParcelFileDescriptor tun) throws Exception {
        // onDestroy got there first: it took the old tunnel down and told the app. There is
        // nothing to replace, and the new tun closes with the caller's finally.
        if (!vpnService.isDone() || vpnService.get(0, TimeUnit.NANOSECONDS) != service ||
                currentTunnel != tunnel || currentTunnelHandle == -1)
            throw new BackendException(Reason.UNABLE_TO_START_VPN);
        final Config originalConfig = currentConfig;

        // A second hold on the new tun. If the new handle fails to start, Go closes the fd it was
        // given, and with no fd left open Android would drop the interface. This keeps it there
        // for the config that was running.
        ParcelFileDescriptor spare = null;
        try {
            spare = tun.dup();
        } catch (final Exception e) {
            Log.w(TAG, "Couldn't keep a second hold on the new tun", e);
        }

        int newHandle = -1;
        Exception startError = null;
        stopStatusJob();
        final int oldHandle = currentTunnelHandle;
        // Nothing may read the old handle once it is off (statistics, the status job).
        currentTunnelHandle = -1;
        awgTurnOff(oldHandle);
        try {
            // Go owns the fd from here, and closes it itself if it fails.
            newHandle = awgTurnOn(tunnel.getName(), tun.detachFd(), goConfig);
        } catch (final Exception e) {
            startError = e;
        }

        Config running = config;
        if (newHandle < 0 && spare != null && originalConfig != null) {
            Log.e(TAG, "New tunnel failed to start (" + newHandle + "); going back to the old config on the same tun", startError);
            try {
                final int spareFd = spare.detachFd();
                spare = null;   // Go owns it now, and closes it itself if it fails
                newHandle = awgTurnOn(tunnel.getName(), spareFd, originalConfig.toAwgUserspaceString());
                running = originalConfig;
            } catch (final Exception e) {
                Log.e(TAG, "Going back to the old config on the same tun failed", e);
            }
        }
        if (spare != null) {
            try {
                spare.close();
            } catch (final Exception ignored) { }
        }

        if (newHandle < 0) {
            // The old handle is off and the new tun is closed: no tunnel now. This is a real
            // DOWN and is told as one, so the app (Home, the leak monitor) can show and alert it.
            Log.e(TAG, "New tunnel failed to start (" + newHandle + ")", startError);
            currentTunnel = null;
            currentConfig = null;
            try {
                service.stopSelf();
            } catch (final RuntimeException ignored) { }
            tunnel.onStateChange(State.DOWN);
            final BackendException failure = new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, newHandle);
            if (startError != null)
                failure.initCause(startError);
            throw failure;
        }

        currentTunnelHandle = newHandle;
        currentConfig = running;

        service.protect(awgGetSocketV4(newHandle));
        service.protect(awgGetSocketV6(newHandle));

        // The new handle has had no handshake yet: "connecting" until the status job sees one.
        if (statusCallback != null)
            statusCallback.onStatusChanged(false);
        launchStatusJob();
        tunnel.onStateChange(State.UP);

        // Back on the old config: the new one failed, and the caller must keep the old one.
        if (running != config) {
            final BackendException failure = new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, -1);
            if (startError != null)
                failure.initCause(startError);
            throw failure;
        }
    }

    /** Pre-resolve the endpoints, so they're cached when building the userspace string. */
    private static void resolveEndpoints(final Config config) throws Exception {
        dnsRetry: for (int i = 0; i < DNS_RESOLUTION_RETRIES; ++i) {
            for (final Peer peer : config.getPeers()) {
                final InetEndpoint ep = peer.getEndpoint().orElse(null);
                if (ep == null)
                    continue;
                if (ep.getResolved().orElse(null) == null) {
                    if (i < DNS_RESOLUTION_RETRIES - 1) {
                        Log.w(TAG, "DNS host \"" + ep.getHost() + "\" failed to resolve; trying again");
                        Thread.sleep(1000);
                        continue dnsRetry;
                    } else
                        throw new BackendException(Reason.DNS_RESOLUTION_FAILURE, ep.getHost());
                }
            }
            break;
        }
    }

    /** Everything about the tun Android is asked for, ready for establish(). */
    private static VpnService.Builder newBuilder(final VpnService service, final Tunnel tunnel, final Config config) {
        final VpnService.Builder builder = service.getBuilder();
        builder.setSession(tunnel.getName());

        // A split-tunnel app uninstalled since the config was saved makes these throw,
        // which used to stop the tunnel coming up at all (and on a reconnect, left it
        // silently down). Skipping it only ever puts more traffic inside the tunnel,
        // never less: a missing excluded app has nothing to exclude, and a missing
        // included app just isn't added. The package name is deliberately not logged.
        for (final String excludedApplication : config.getInterface().getExcludedApplications()) {
            try {
                builder.addDisallowedApplication(excludedApplication);
            } catch (final PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Skipping an excluded app that is no longer installed");
            }
        }

        for (final String includedApplication : config.getInterface().getIncludedApplications()) {
            try {
                builder.addAllowedApplication(includedApplication);
            } catch (final PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Skipping an included app that is no longer installed");
            }
        }

        for (final InetNetwork addr : config.getInterface().getAddresses())
            builder.addAddress(addr.getAddress(), addr.getMask());

        for (final InetAddress addr : config.getInterface().getDnsServers())
            builder.addDnsServer(addr.getHostAddress());

        for (final String dnsSearchDomain : config.getInterface().getDnsSearchDomains())
            builder.addSearchDomain(dnsSearchDomain);

        boolean sawDefaultRoute = false;
        for (final Peer peer : config.getPeers()) {
            for (final InetNetwork addr : peer.getAllowedIps()) {
                if (addr.getMask() == 0)
                    sawDefaultRoute = true;
                builder.addRoute(addr.getAddress(), addr.getMask());
            }
        }

        // "Kill-switch" semantics, only while the interface exists. After a real DOWN only
        // Android's own "Block connections without VPN" keeps traffic in (see getKillSwitchState).
        if (!(sawDefaultRoute && config.getPeers().size() == 1)) {
            builder.allowFamily(OsConstants.AF_INET);
            builder.allowFamily(OsConstants.AF_INET6);
        }

        builder.setMtu(config.getInterface().getMtu().orElse(1280));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            builder.setMetered(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            service.setUnderlyingNetworks(null);

        builder.setBlocking(true);
        return builder;
    }

    /** Whether Android's "Always-on VPN" and "Block connections without VPN" apply to this app. */
    public enum KillSwitchState {
        /** Too old an Android to ask, or the VPN service isn't running. */
        UNKNOWN,
        OFF,
        /** Android restarts the VPN, but lets traffic out while it's down. */
        ALWAYS_ON_ONLY,
        /** "Block connections without VPN". */
        ON
    }

    /**
     * Whether Android's "Always-on VPN" and "Block connections without VPN" apply to this app.
     * Android only answers on a running VpnService (API 29+). An app can read this but not set it.
     */
    public static KillSwitchState getKillSwitchState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !vpnService.isDone())
            return KillSwitchState.UNKNOWN;
        try {
            final VpnService service = vpnService.get(0, TimeUnit.NANOSECONDS);
            if (service.isLockdownEnabled())
                return KillSwitchState.ON;
            return service.isAlwaysOn() ? KillSwitchState.ALWAYS_ON_ONLY : KillSwitchState.OFF;
        } catch (final Exception e) {
            return KillSwitchState.UNKNOWN;
        }
    }

    /**
     * Callback for {@link GoBackend} that is invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     */
    public interface AlwaysOnCallback {
        void alwaysOnTriggered();
    }

    // TODO: When we finally drop API 21 and move to API 24, delete this and replace with the ordinary CompletableFuture.
    private static final class GhettoCompletableFuture<V> {
        private final LinkedBlockingQueue<V> completion = new LinkedBlockingQueue<>(1);
        private final FutureTask<V> result = new FutureTask<>(completion::peek);

        public boolean complete(final V value) {
            final boolean offered = completion.offer(value);
            if (offered)
                result.run();
            return offered;
        }

        public V get() throws ExecutionException, InterruptedException {
            return result.get();
        }

        public V get(final long timeout, final TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
            return result.get(timeout, unit);
        }

        public boolean isDone() {
            return !completion.isEmpty();
        }

        public GhettoCompletableFuture<V> newIncompleteFuture() {
            return new GhettoCompletableFuture<>();
        }
    }

    /**
     * {@link android.net.VpnService} implementation for {@link GoBackend}
     */
    public static class VpnService extends android.net.VpnService {
        @Nullable private GoBackend owner;

        public Builder getBuilder() {
            return new Builder();
        }

        @Override
        public void onCreate() {
            vpnService.complete(this);
            super.onCreate();
        }

        @Override
        public void onDestroy() {
            if (owner != null) {
                Tunnel tunnel;
                // Not in the middle of a turn-on: that would leave its new handle running with
                // nobody owning it. Only the few milliseconds of a handle change are ever held.
                synchronized (owner.stateLock) {
                    tunnel = owner.currentTunnel;
                    if (tunnel != null) {
                        final int handle = owner.currentTunnelHandle;
                        owner.currentTunnel = null;
                        owner.currentTunnelHandle = -1;
                        owner.currentConfig = null;
                        if (handle != -1)
                            awgTurnOff(handle);
                    }
                }
                if (tunnel != null)
                    tunnel.onStateChange(State.DOWN);
            }
            vpnService = vpnService.newIncompleteFuture();
            super.onDestroy();
        }

        @Override
        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
            vpnService.complete(this);
            if (intent == null || intent.getComponent() == null || !intent.getComponent().getPackageName().equals(getPackageName())) {
                Log.d(TAG, "Service started by Always-on VPN feature");
                if (alwaysOnCallback != null)
                    alwaysOnCallback.alwaysOnTriggered();
            }
            return super.onStartCommand(intent, flags, startId);
        }

        public void setOwner(final GoBackend owner) {
            this.owner = owner;
        }
    }
}

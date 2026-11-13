package org.amnezia.awg.ui

/**
 * The account-check rules that don't need Android, kept apart so they can be unit-tested.
 *
 * One principle: protection is only ever stopped because of a clear answer about the account
 * from the licence server ("expired", "revoked", "not found", "device limit"). No answer at all
 * (offline, a timeout, a server outage, a blocked server), a busy or rate-limited server, or a
 * local problem reading the saved account never stops a running tunnel or blocks auto-start.
 */
object LicenceGate {
    /** How long the last good enrolment is reused to connect while the server can't be reached. */
    const val GRACE_MS = 72L * 60 * 60 * 1000

    /** How often a running tunnel's account is checked again. */
    const val RECHECK_MS = 24L * 60 * 60 * 1000

    /** After a check that got no usable answer, wait this long before asking again. */
    const val RETRY_MS = 60L * 60 * 1000

    /** The only server answers that are really about the account. */
    private val LICENCE_NO = setOf("expired", "revoked", "not_found", "device_limit")

    /**
     * Whether the last confirmation at [confirmedAt] still counts at [now]. A time in the
     * future does not count: setting the phone's clock back must not stretch the window.
     */
    fun confirmedRecently(now: Long, confirmedAt: Long, windowMs: Long = GRACE_MS): Boolean =
        confirmedAt > 0L && now >= confirmedAt && now - confirmedAt <= windowMs

    /**
     * True only for an answer the server itself gave ([fromServer]) that says the account may
     * not connect. Statuses made up on the phone (a malformed saved code, a network error) and
     * server answers like "rate_limited", "busy" or "pool_exhausted" are not.
     */
    fun isLicenceNo(fromServer: Boolean, status: String): Boolean = fromServer && status in LICENCE_NO

    enum class Recheck {
        /** Checked recently enough, nothing asked. */
        NOT_DUE,
        /** The server confirmed the account. */
        OK,
        /** The server said the account may not connect: protection is stopped and the user told. */
        REJECTED,
        /** No usable answer (offline, server busy, local error): keep the tunnel, ask later. */
        RETRY
    }

    /**
     * Is a check due? Once a day after the last good one, and not more than once an hour after
     * a check that got no answer (so an outage isn't met with a request every minute). Times in
     * the future (a clock set back) count as never.
     */
    fun recheckDue(now: Long, lastValidated: Long, lastAttempt: Long): Boolean =
        !confirmedRecently(now, lastValidated, RECHECK_MS) &&
            !confirmedRecently(now, lastAttempt, RETRY_MS)

    /**
     * The account status a check's answer stands for. valid=false with an active status means
     * the paid time has ended: that is "expired". A reply with no status (read as "error", such
     * as "too many tries") is left as it is and stays "try later".
     */
    fun checkStatus(fromServer: Boolean, valid: Boolean, status: String): String =
        if (fromServer && !valid && status == "active") "expired" else status

    /** What a check's answer means for a tunnel that is up. */
    fun outcome(fromServer: Boolean, valid: Boolean, status: String): Recheck = when {
        valid -> Recheck.OK
        isLicenceNo(fromServer, checkStatus(fromServer, valid, status)) -> Recheck.REJECTED
        else -> Recheck.RETRY
    }

    /**
     * May the KAPO tunnel be raised without a new enrolment (quick tile, boot, Always-on, app
     * start)? Only refused after the server has said no ([refusedStatus] is what it said);
     * nothing stored, as after an update from an older version, means yes.
     */
    fun mayRaise(refusedStatus: String?): Boolean = refusedStatus.isNullOrEmpty()

    /**
     * Whether the user wants KAPO on, at a restore. [stored] is the saved choice, null when an
     * older version never saved one; then a tunnel that was running before counts as wanted.
     */
    fun wantedOnRestore(stored: Boolean?, wasRunning: Boolean): Boolean = stored ?: wasRunning
}

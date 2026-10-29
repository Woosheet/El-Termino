package dev.eltermino.core.ssh.mina

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class MinaConfig(
    /** TCP connect plus key exchange, per hop. */
    val connectTimeout: Duration = 15.seconds,
    /** Authentication without a human in the loop (passwords, keys). */
    val authTimeout: Duration = 30.seconds,
    /** Authentication that waits for the user, such as an OTP prompt. */
    val interactiveAuthTimeout: Duration = 3.minutes,
    val channelOpenTimeout: Duration = 15.seconds,
    /** Zero turns keepalives off. */
    val keepAliveInterval: Duration = 30.seconds,
    /** The connection is dropped after this many unanswered keepalives. */
    val keepAliveMaxMissed: Int = 3,
)

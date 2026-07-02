package com.chrisjenx.serialkompat.core

/**
 * A break the team has explicitly sanctioned, mirroring one stanza of the
 * committed `serialkompat-exceptions.yaml` (design §7). A [Finding] matching an
 * accepted break is downgraded to *acknowledged*: logged, but not failing the
 * gate. The diff to this list in a PR is exactly the breakage it sanctions.
 *
 * @property type the affected contract's serial name.
 * @property rule the named rule being accepted (see [Rules]).
 * @property direction the direction accepted, or `null` to accept both.
 * @property reason why the break is acceptable (for the audit trail).
 * @property acceptedBy who signed off.
 */
public data class AcceptedBreak(
    val type: String,
    val rule: String,
    val direction: CompatibilityDirection? = null,
    val reason: String = "",
    val acceptedBy: String = "",
)

/** Whether this finding is sanctioned by any entry in [accepted]. */
public fun Finding.isAcceptedBy(accepted: List<AcceptedBreak>): Boolean =
    accepted.any { break_ ->
        break_.type == contract &&
            break_.rule == rule &&
            (break_.direction == null || break_.direction == direction)
    }

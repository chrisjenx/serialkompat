package com.chrisjenx.serialkompat.core

/**
 * A break the team has explicitly sanctioned, mirroring one stanza of the
 * committed `serialkompat-exceptions.yaml` (design §7). A [Finding] matching an
 * accepted break is downgraded to *acknowledged*: logged, but not failing the
 * gate. The diff to this list in a PR is exactly the breakage it sanctions.
 *
 * @property type the affected contract: its qualified name `Base/sub` (exactly that subtype) or
 *   its bare serial name (every contract with that serial name, under any base — #200).
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

/** The first entry in [accepted] that sanctions this finding, or `null` if none does. */
public fun Finding.findAcceptedBy(accepted: List<AcceptedBreak>): AcceptedBreak? =
    accepted.firstOrNull { break_ ->
        namesFindingContract(break_.type, contract) &&
            break_.rule == rule &&
            (break_.direction == null || break_.direction == direction)
    }

/** Whether this finding is sanctioned by any entry in [accepted]. */
public fun Finding.isAcceptedBy(accepted: List<AcceptedBreak>): Boolean = findAcceptedBy(accepted) != null

/**
 * Whether an accepted-break [type] names a finding's [contract] display name: exactly, or as the bare
 * serial name of a base-qualified `Base/sub` display name. A finding carries only the display string,
 * so the bare form is recognised as a `/`-delimited suffix (a `@SerialName` containing `/` can
 * therefore also be matched by its trailing segment — a documented limitation, design §8).
 */
private fun namesFindingContract(
    type: String,
    contract: String,
): Boolean = type == contract || contract.endsWith("/$type")

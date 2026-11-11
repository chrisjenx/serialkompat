package com.chrisjenx.serialkompat.core

/**
 * The member of its contract this change is about — the field's wire key, the enum value, or the
 * subtype's serial name — or `null` for a change to the whole type or to the config. It is exactly
 * the name the finding's detail quotes, so an [AcceptedBreak.member] can be copied from the report
 * (#120). Exhaustive with no `else`, so a new [Change] must decide whether it has a member.
 */
internal fun Change.member(): String? =
    when (this) {
        is Change.ElementAdded -> element.name

        is Change.ElementRemoved -> element.name

        is Change.ElementTypeChanged -> element

        is Change.ElementOptionalityChanged -> element

        is Change.ElementNullabilityChanged -> element

        is Change.ElementJsonNamesChanged -> element

        is Change.ElementCborLabelChanged -> element

        is Change.ElementByteStringChanged -> element

        is Change.CborTagsChanged -> element

        is Change.CborArrayOmittedDefault -> element

        is Change.EnumValueAdded -> value

        is Change.EnumValueRemoved -> value

        is Change.SubtypeAdded -> subtype.serialName

        is Change.SubtypeRemoved -> subtype.serialName

        is Change.DiscriminatorCollision -> subtype

        is Change.ContractAdded,
        is Change.ContractRemoved,
        is Change.ContractMoved,
        is Change.DiscriminatorChanged,
        is Change.ConfigChanged,
        is Change.CoverageGap,
        is Change.ContractUnpaired,
        is Change.CborArrayLayoutChanged,
        -> null
    }

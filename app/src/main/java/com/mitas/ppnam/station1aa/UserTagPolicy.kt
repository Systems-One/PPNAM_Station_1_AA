package com.mitas.ppnam.station1aa

/** Badges issued by Account Management start with these 3 bytes (hex). Item/pallet EPCs never do. */
object UserTagPolicy {
    const val USER_TAG_PREFIX = "505055"

    fun isUserTag(epc: String?): Boolean =
        epc?.trim()?.uppercase()?.startsWith(USER_TAG_PREFIX) == true

    /** Pallet/offload screens consume a read only if it is non-empty and not a user badge. */
    fun shouldForwardItemScan(epc: String?): Boolean = !epc.isNullOrEmpty() && !isUserTag(epc)
}

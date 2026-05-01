package com.example.smartkey.card

object HceConfig {
    const val PREFS = "hce_prefs"
    const val KEY_VEHICLE_ID = "vehicle_id"
    const val KEY_VEHICLE_TOKEN = "vehicle_token"
    const val KEY_FIXED_PAYLOAD = "hce_payload"
    const val FIXED_PAYLOAD = "SMARTKEY-CARD-0001"
    
    // CCC-Style AID: D27600008543434301
    // RID D276000085 (ISO test RID) + PIX "CCC" (434343) + Version 01
    const val CCC_STYLE_AID = "D27600008543434301"
    const val LEGACY_AID = "F00102030405"
}

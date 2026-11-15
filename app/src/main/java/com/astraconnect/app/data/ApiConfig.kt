package com.astraconnect.app.data

/**
 * Endpunkte der Stellantis (ehem. PSA) Connected-Car-API v4, wie sie auch die MyOpel-App nutzt.
 */
object ApiConfig {
    const val OAUTH_BASE = "https://idpcvs.opel.com/am/oauth2"
    const val API_BASE = "https://api.groupe-psa.com/connectedcar/v4/user"
    const val REALM = "clientsB2COpel"
    const val SCHEME = "mymopsdk"
    const val LOCALE = "de-DE"
    const val CULTURE = "de"

    /** Client-ID der MyOpel-App (Deutschland / Österreich). */
    const val DEFAULT_CLIENT_ID = "07364655-93cb-4194-8158-6b035ac2c24c"

    const val REDIRECT_URI = "$SCHEME://oauth2redirect/$CULTURE"
}

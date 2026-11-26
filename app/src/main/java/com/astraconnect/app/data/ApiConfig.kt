package com.astraconnect.app.data

/**
 * Endpunkte der Stellantis (ehem. PSA) Connected-Car-API v4, wie sie auch die MyOpel-App nutzt.
 */
object ApiConfig {
    const val OAUTH_BASE = "https://idpcvs.opel.com/am/oauth2"
    const val API_BASE = "https://api.groupe-psa.com/connectedcar/v4/user"
    const val REALM = "clientsB2COpel"
    const val SCHEME = "mymopsdk"

    /** Client-ID der MyOpel-App für die meisten europäischen Märkte (u. a. DE / AT / CH). */
    const val DEFAULT_CLIENT_ID = "07364655-93cb-4194-8158-6b035ac2c24c"

    /** Client-ID der MyOpel-App für einige weitere Märkte (Baltikum, Malta, Neuseeland, Taiwan). */
    private const val ALT_CLIENT_ID = "a009239a-ed2a-49f9-9e6f-3f59703d469d"

    // Client-Secrets der MyOpel-App, Quelle: configs.json von „homeassistant-stellantis-vehicles“ (MyOpel)
    private const val DEFAULT_CLIENT_SECRET = "F2kK7lC5kF5qN7tM0wT8kE3cW1dP0wC5pI6vC0sQ5iP5cN8cJ8"
    private const val ALT_CLIENT_SECRET = "M5yW4sE5qU6pO6jO4vH2qE0qS6gH6lR6cJ6kW1pE5cM6nM7sS0"

    const val DEFAULT_COUNTRY = "DE"

    /** Ein Markt der MyOpel-App. Login-Server und Realm sind überall gleich, nur Locale und Client-ID variieren. */
    data class Market(
        val code: String,
        val name: String,
        val locale: String,
        val clientId: String = DEFAULT_CLIENT_ID,
        /** Landeswährung, in der Preise und Kosten angezeigt werden. */
        val currency: Currency = Currency.EUR,
    ) {
        val redirectUri: String get() = "$SCHEME://oauth2redirect/${code.lowercase()}"
    }

    /** Märkte laut configs.json von „homeassistant-stellantis-vehicles“ (MyOpel), alphabetisch nach Name. */
    val MARKETS: List<Market> = listOf(
        Market("BE", "Belgien", "fr-BE"),
        Market("BG", "Bulgarien", "bg-BG"), // Euro seit 01.01.2026
        Market("DK", "Dänemark", "da-DK", currency = Currency.DKK),
        Market("DE", "Deutschland", "de-DE"),
        Market("EE", "Estland", "et-EE", ALT_CLIENT_ID),
        Market("FI", "Finnland", "fi-FI"),
        Market("FR", "Frankreich", "fr-FR"),
        Market("GF", "Französisch-Guayana", "fr-GF"),
        Market("GR", "Griechenland", "el-GR"),
        Market("GP", "Guadeloupe", "fr-GP"),
        Market("IE", "Irland", "en-IE"),
        Market("IS", "Island", "en-IS", currency = Currency.ISK),
        Market("IT", "Italien", "it-IT"),
        Market("JP", "Japan", "ja-JP", currency = Currency.JPY),
        Market("HR", "Kroatien", "hr-HR"),
        Market("LV", "Lettland", "lv-LV", ALT_CLIENT_ID),
        Market("LT", "Litauen", "lt-LT", ALT_CLIENT_ID),
        Market("LU", "Luxemburg", "fr-LU"),
        Market("MT", "Malta", "en-MT", ALT_CLIENT_ID),
        Market("MQ", "Martinique", "fr-MQ"),
        Market("NZ", "Neuseeland", "en-NZ", ALT_CLIENT_ID, Currency.NZD),
        Market("NL", "Niederlande", "nl-NL"),
        Market("NO", "Norwegen", "nb-NO", currency = Currency.NOK),
        Market("AT", "Österreich", "de-AT"),
        Market("PL", "Polen", "pl-PL", currency = Currency.PLN),
        Market("PT", "Portugal", "pt-PT"),
        Market("RE", "Réunion", "fr-RE"),
        Market("RO", "Rumänien", "ro-RO", currency = Currency.RON),
        Market("RU", "Russland", "ru-RU", currency = Currency.RUB),
        Market("SE", "Schweden", "sv-SE", currency = Currency.SEK),
        Market("CH", "Schweiz", "fr-CH", currency = Currency.CHF),
        Market("SG", "Singapur", "en-SG", currency = Currency.SGD),
        Market("SK", "Slowakei", "sk-SK"),
        Market("SI", "Slowenien", "sl-SI"),
        Market("ES", "Spanien", "es-ES"),
        Market("TW", "Taiwan", "zh-TW", ALT_CLIENT_ID, Currency.TWD),
        Market("CZ", "Tschechien", "cs-CZ", currency = Currency.CZK),
        Market("TR", "Türkei", "tr-TR", currency = Currency.TRY),
        Market("HU", "Ungarn", "hu-HU", currency = Currency.HUF),
    )

    fun market(code: String?): Market =
        MARKETS.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: MARKETS.first { it.code == DEFAULT_COUNTRY }

    /** Client-IDs, die zu einem bekannten Markt gehören (zum Erkennen „nicht selbst geändert“). */
    fun isMarketClientId(id: String) = MARKETS.any { it.clientId == id }

    /** In der App hinterlegtes Client-Secret zur Client-ID. Leer, wenn die Client-ID unbekannt ist. */
    fun builtInSecret(clientId: String): String = when (clientId.trim()) {
        DEFAULT_CLIENT_ID -> DEFAULT_CLIENT_SECRET
        ALT_CLIENT_ID -> ALT_CLIENT_SECRET
        else -> ""
    }
}

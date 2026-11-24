package com.astraconnect.app.data

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Währung, in der die App Preise und Kosten anzeigt. Ergibt sich aus dem gewählten MyOpel-Markt
 * ([ApiConfig.Market.currency]). Beträge werden nie umgerechnet, nur anders beschriftet.
 * Schreibweise bleibt deutsch: Betrag, dann Symbol („12,34 CHF“).
 *
 * [digits] = Nachkommastellen für Beträge (0 bei Währungen ohne gebräuchliche Untereinheit).
 * [perEuro] = grober Kurs, nur für Plausibilitätsgrenzen und Standardpreise – nicht zum Umrechnen.
 */
enum class Currency(val symbol: String, val perEuro: Double, val digits: Int = 2) {
    EUR("€", 1.0),
    CHF("CHF", 0.94),
    CZK("Kč", 24.5),
    DKK("kr.", 7.46),
    HUF("Ft", 395.0, 0),
    ISK("kr", 145.0, 0),
    JPY("¥", 175.0, 0),
    NOK("kr", 11.7),
    NZD("NZ$", 2.0),
    PLN("zł", 4.25),
    RON("lei", 5.1),
    RUB("₽", 95.0),
    SEK("kr", 11.0),
    SGD("S$", 1.5),
    TRY("₺", 48.0),
    TWD("NT$", 36.0, 0);

    /** Nachkommastellen für Preise je Liter bzw. kWh (eine mehr als bei Beträgen). */
    val priceDigits: Int get() = digits + 1

    /** Euro-Wert grob in diese Währung übertragen und auf [priceDigits] Stellen gerundet. */
    fun fromEuro(eur: Double): Double {
        val f = 10.0.pow(priceDigits)
        return (eur * perEuro * f).roundToLong() / f
    }

    /** Plausibler Bereich für Preise, die in Euro zwischen [minEur] und [maxEur] lägen. */
    fun range(minEur: Double, maxEur: Double): ClosedFloatingPointRange<Double> = minEur * perEuro..maxEur * perEuro

    companion object {
        /**
         * Währung des aktuell gewählten Markts. Wird von [AuthRepository] gesetzt, sobald das Land
         * gelesen oder geändert wird; Formatierer in UI und Export lesen sie hier.
         */
        @Volatile
        var current: Currency = EUR
    }
}

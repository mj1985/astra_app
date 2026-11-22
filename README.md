# Astra Connect

Android-App (Kotlin, Jetpack Compose) für den Opel Astra Plug-in-Hybrid (2022) über die
Stellantis Connected-Car-API v4 – dieselbe Schnittstelle, die auch die MyOpel-App nutzt.

## Funktionen

- **Übersicht:** Verriegelung, Türen/Fenster auf einen Blick, Akku- und Tankstand, Reichweiten,
  Kilometerstand, Ladestatus, Zündung, Außentemperatur, Vorklimatisierung, Warnungen, letzter Standort
- **Fahrtenbuch:** Fahrten aus „My Trip Report“ abrufen und lokal speichern, Kategorie
  (Privat / Dienstlich / Arbeitsweg) und Zweck/Notiz pro Fahrt, Adressen per Geocoder,
  Filter nach Jahr/Kategorie, CSV-Export (Excel-kompatibel, Semikolon)
- **Türen & Fenster:** Status jeder Tür, Kofferraum, Zentralverriegelung, Fenster (sofern geliefert)
- **Wartung & 12V-Batterie:** Tage/km bis zur Wartung inkl. Fälligkeitsdatum, 12V-Ladezustand mit Bewertung
- **Fernsteuerung:** Verriegeln/Entriegeln, Vorklimatisierung an/aus, Laden starten/pausieren, Hupe, Lichtsignal,
  Fahrzeug aufwecken (frischer Status) – mit Rückmeldung vom Auto
- **Akku-Verbrauch pro Fahrt (berechnet):** Ladestand vor/nach der Fahrt × nutzbare Kapazität (einstellbar,
  Standard 10,2 kWh); der Ladestand wird dafür alle 15 Min. im Hintergrund aufgezeichnet
- **myOpel-Import:** Export der myOpel-App (`.myop` oder ohne Endung) einlesen; Fahrten werden mit dem
  Fahrtenbuch abgeglichen (Startzeit ± 3 min bzw. gleicher km-Stand) und ergänzt
- **Fahrten zusammenführen/trennen:** Umparken oder kurze Zwischenstopps an die vorherige Fahrt anhängen
- **Orte & Regeln:** häufige Orte benennen („Zuhause“, „Arbeit“), Regeln wie „Zuhause ↔ Arbeit = Arbeitsweg“;
  neue Fahrten werden automatisch zugeordnet (von Hand gesetzte Kategorien bleiben unangetastet)
- **Leiste unten:** Startseite plus bis zu 4 frei wählbare Bereiche (Einstellungen → Leiste unten)
- **Tanken:** Tankbuch mit erkannten Tankvorgängen; Literpreis nach dem Tanken eintragen – gilt für alle folgenden Fahrten
- **Laden:** Ladeübersicht mit Kosten pro Ladevorgang, Monat und Ort; Strompreis pro Ort (z. B. Arbeit = 0 €),
  Betrag oder €/kWh pro Ladung korrigierbar, Ladungen an öffentlichen Säulen nachtragen
- **Kosten:** pro Fahrt, Monat und Kategorie – Benzin zum zuletzt getankten Preis, Strom zum Preis der letzten Ladung;
  ohne Eintrag gelten die Standardpreise (inkl. Ladeverluste)
- **Statistik:** km pro Monat (rein elektrisch / mit Benzin), Ø Benzinverbrauch, Kosten, Monatstabelle,
  Datenabgleich API ↔ myOpel (Einheiten-Check)
- **Karte:** Fahrten (Start → Ziel) und Häufigkeitskarte auf OpenStreetMap; Mini-Karte pro Fahrt
- **Benachrichtigungen:** Laden fertig/abgebrochen, Auto nachts offen, 12-V-Batterie schwach, Wartung fällig,
  Anmeldung abgelaufen
- **Homescreen-Widgets:**
  - *Laden:* Akkustand mit Balken, Ladestatus mit Restladezeit und Fertig-Uhrzeit, Reichweite elektrisch und gesamt (inkl. Benzin)
  - *Steuerung (5×2):* Klimatisierung an/aus, Hupe, Lichtsignal, Verriegeln – rechts das Fahrzeugbild, am Rand angeschnitten
    (Tasten brauchen die eingerichtete Fernsteuerung; Bild spiegeln unter Einstellungen → Widgets)
  - *Reichweite (2×1):* Akku- und Tankstand mit Reichweite; ab 2 Zeilen Höhe zusätzlich die Gesamtreichweite
  - *Letzte Fahrt (4×1):* Strecke, Dauer, Benzin- und Stromverbrauch (gesamt und pro 100 km); ab 2 Zeilen ausführlicher
- **Datensicherung:** Export/Import als JSON und automatische tägliche Sicherung in einen wählbaren Ordner
  (z. B. Google Drive) – ohne Zugangsdaten
- **API-Rohdaten:** die unveränderten JSON-Antworten zum Nachsehen/Kopieren

## Login

1. App starten → **Land** des MyOpel-Kontos wählen (Standard: Deutschland). Das Land bestimmt
   Sprache der Anmeldeseite, Redirect und Client-ID.
2. Client-ID ist passend zum Land vorbelegt. Ist beim Build kein Client-Secret hinterlegt (siehe unten),
   „API-Zugang & manueller Login“ aufklappen und das **Client-Secret** der MyOpel-App eintragen
   (z. B. aus `configs.json` des Projekts *homeassistant-stellantis-vehicles*, Eintrag `MyOpel` → Land).
3. „Mit MyOpel anmelden“ → Login auf der Opel-Seite. Die App fängt den Redirect
   `mymopsdk://oauth2redirect/<land>?code=…` ab und tauscht den Code gegen Tokens.
4. Falls die Anmeldeseite in der App nicht funktioniert (z. B. Captcha): Anmelde-Link kopieren,
   im PC-Browser mit Entwicklertools öffnen, nach dem Login den `code` aus der Redirect-URL
   in das Feld „Code“ einfügen.

### Client-Secret beim Build hinterlegen (optional)

Die Secrets gehören zur MyOpel-App und liegen deshalb **nicht im Repo**. Wer sie in `local.properties`
(von Git ignoriert) einträgt, bekommt eine App, in der das Secret schon vorbelegt ist:

```properties
# für Client-ID 07364655-93cb-4194-8158-6b035ac2c24c (die meisten Länder, u. a. DE/AT/CH)
myopel.clientSecret=…
# für Client-ID a009239a-ed2a-49f9-9e6f-3f59703d469d (EE, LT, LV, MT, NZ, TW)
myopel.clientSecret.alt=…
```

Ein in der App selbst eingetragenes Secret hat Vorrang, z. B. falls Opel es einmal ändert.

Tokens und Secret liegen mit einem Android-Keystore-Schlüssel verschlüsselt auf dem Gerät.
Das Passwort speichert die App nicht.

## Bauen & Installieren

```
gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` – aufs Handy kopieren und installieren
(„Installation aus unbekannten Quellen“ erlauben) oder per `adb install`.
Alternativ das Projekt in Android Studio öffnen und auf ▶ drücken.

## Grenzen

- Die API liefert den zuletzt vom Auto gemeldeten Stand. Für frische Daten: Fernsteuerung → „Status holen“.
- Fensterstatus wird von vielen Astra-Modellen nicht an die API übertragen – dann zeigt die App einen Hinweis.
- Die Felder der Fahrten sind nicht offiziell dokumentiert; die App liest mehrere mögliche Feldnamen.
  Fehlt etwas, hilft der Bildschirm „API-Rohdaten“ bzw. „Rohdaten anzeigen“ bei einer Fahrt.
- Fernsteuerung: einmalige Freischaltung per SMS-Code + MyOpel-PIN (Menü „Fernsteuerung“). Opel erlaubt nur
  wenige neue Sicherheitscodes pro Tag; das Token wird 3 Tage lang automatisch erneuert.
- Akku-Verbrauch: nur für Fahrten nach Beginn der Aufzeichnung. Liegen mehrere Fahrten zwischen zwei
  Messpunkten, wird nach Strecke aufgeteilt (≈); wurde dazwischen geladen, ist keine Berechnung möglich.

# BlitzLink

Android-App (Kotlin, Jetpack Compose) für Opel-Fahrzeuge über die Stellantis Connected-Car-API v4 –
dieselbe Schnittstelle, die auch die MyOpel-App nutzt. Entwickelt mit dem Astra Plug-in-Hybrid (2022),
nutzbar auch für Elektroautos und Verbrenner/Mild-Hybride.

## Funktionen

- **Übersicht:** Akku, Tank, Reichweite, Türen/Fenster, Verriegelung, Ladestatus, Wartung, 12-V-Batterie, Standort
- **Fernsteuerung:** Verriegeln, Vorklimatisierung, Laden starten/pausieren, Hupe, Lichtsignal, Status holen
- **Ladelimit:** die App pausiert das Laden bei einem einstellbaren Akkustand – für Autos ohne eigenes Limit
- **Fahrtenbuch:** Fahrten mit Kategorie und Notiz, automatische Zuordnung über Orte & Regeln,
  myOpel-Import, CSV-Export
- **Kosten & Statistik:** Tank- und Ladebuch, Kosten pro Fahrt und Monat, berechneter Akku-Verbrauch pro Fahrt
- **Karte:** Fahrten mit grobem Streckenverlauf und Häufigkeitskarte (OpenStreetMap)
- **Widgets:** Fahrzeug, Laden, Steuerung, Reichweite, letzte Fahrt
- **Benachrichtigungen:** Laden fertig, Auto offen, 12-V-Batterie schwach, Wartung fällig
- **Außerdem:** mehrere Fahrzeuge, Bereiche passend zur Antriebsart, tägliche Datensicherung, API-Rohdaten


## FAQ

### Fernsteuerung: „Aktivierung fehlgeschlagen … NOK:MAXNBTOOLS“ (oder „NOK:FORBIDDEN“)

Opel hat die Fernsteuerung für das Konto gesperrt, weil zu oft per SMS aktiviert wurde (laut
[psa_car_controller](https://github.com/flobz/psa_car_controller/blob/master/FAQ.md) nach 20 Aktivierungen).
Dabei zählen alle Einrichtungen, ob in BlitzLink, in der MyOpel-App oder in anderen Tools. Weitere
SMS-Codes anzufordern hilft nicht.

So hebst du die Sperre auf:

**Wenn die MyOpel-App installiert ist:**

1. In der MyOpel-App das Konto mit deinem Namen auswählen.
2. Bei „Smartphone registriert“ auf den Mülleimer tippen.
3. In BlitzLink unter „Fernsteuerung“ neu einrichten.

**Wenn die MyOpel-App nicht installiert ist:**

1. Die MyOpel-App auf dem Handy installieren und mit dem Opel-Konto anmelden.
2. Dort einen Fernbefehl ausprobieren. Die App sollte melden, dass das Konto zurückgesetzt werden muss –
   diesem Hinweis folgen.
3. Sobald die Fernsteuerung in der MyOpel-App funktioniert, in BlitzLink unter „Fernsteuerung“ neu einrichten.

Damit die Sperre nicht wieder auftritt, die Freischaltung in BlitzLink nur zurücksetzen, wenn es nötig ist.
Jede neue Einrichtung zählt als weitere Aktivierung.

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


## Halte mich wach! ☕
[![Yellow button with a steaming coffee cup and the text Buy me a coffee, in a cheerful banner style](https://www.buymeacoffee.com/assets/img/custom_images/yellow_img.png)](https://buymeacoffee.com/mj1985)
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/T5Q52BAE6A)


## Lizenz

GPL-3.0 – siehe [LICENSE](LICENSE). Teile sind aus Open-Source-Projekten übernommen bzw. portiert:
[psa_car_controller](https://github.com/flobz/psa_car_controller) (GPL-3.0; OTP-Erzeugung, Modellliste `car_models.yml`)
und [homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles)
(MIT, Copyright (c) 2023 andreadegiovine; Lizenztext dort).

Inoffizielle App – nicht mit Opel oder Stellantis verbunden. „Opel“ und „Astra“ sind Marken ihrer jeweiligen Inhaber.

# BlitzLink ⚡

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
- **Uhr (Wear OS):** Tile mit Akku, Reichweite, Ladestatus, Klima und Verriegeln; Complication mit Akkustand
- **Benachrichtigungen:** Laden fertig, Auto offen, 12-V-Batterie schwach, Wartung fällig
- **Außerdem:** mehrere Fahrzeuge, Bereiche passend zur Antriebsart, tägliche Datensicherung, API-Rohdaten


## FAQ

<details>
<summary><b>Fernsteuerung: „Aktivierung fehlgeschlagen … NOK:MAXNBTOOLS“ (oder „NOK:FORBIDDEN“)</b></summary>

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

</details>

<details>
<summary><b>Plug-in-Hybrid: Kein Akku-Verbrauch bei Fahrten mit leerem Akku</b></summary>

Fährst du mit 0 % Akku los und kommst mit 0 % an, ist der Hybrid nur mit dem Verbrenner gefahren.
Für diese Fahrt berechnet die App dann keinen Akku-Verbrauch und zeigt auch keinen an.

</details>

## Login

1. App starten → **Land** des MyOpel-Kontos wählen (Standard: Deutschland). Das Land bestimmt
   Sprache der Anmeldeseite, Redirect und Client-ID.
2. Client-ID und Client-Secret der MyOpel-App sind passend zum Land in der App hinterlegt (siehe unten).
3. „Mit MyOpel anmelden“ → Login auf der Opel-Seite. Die App fängt den Redirect
   `mymopsdk://oauth2redirect/<land>?code=…` ab und tauscht den Code gegen Tokens.
4. Falls die Anmeldeseite in der App nicht funktioniert (z. B. Captcha): Anmelde-Link kopieren,
   im PC-Browser mit Entwicklertools öffnen, nach dem Login den `code` aus der Redirect-URL
   in das Feld „Code“ einfügen.

## Wear OS

Optional gibt es eine Uhr-App für Wear OS 3 oder neuer (z. B. Pixel Watch, Galaxy Watch ab Watch4):

- **Tile:** Akkustand als Ring (beim Verbrenner der Tank), Reichweite, Ladestatus und Tasten für Klima und
  Verriegeln. Jeder Befehl will einmal bestätigt werden; ein Tipp auf den Akkustand holt einen neuen Stand.
- **Complication:** Akkustand fürs Zifferblatt, als Ring oder Text.

Die Uhr zeigt das in der Handy-App gewählte Fahrzeug. Daten und Befehle laufen über das gekoppelte Handy:
Die Uhr braucht keine eigene Anmeldung, die Fernsteuerung muss aber in der Handy-App eingerichtet sein.

**Installation:** `BlitzLink-Wear-X.Y.apk` aus den Releases laden. Auf der Uhr die Entwickleroptionen
einschalten (in den Einstellungen unter „System → Info“ mehrmals auf die Build-Nummer tippen, je nach Uhr
leicht anders), dort „ADB-Debugging“ und „Debugging über WLAN“ aktivieren. Dann vom PC im selben WLAN:

```
adb pair <IP>:<Port>       # Kopplungscode und Adresse unter „Neues Gerät koppeln“
adb connect <IP>:<Port>    # Adresse unter „Debugging über WLAN“
adb install BlitzLink-Wear-X.Y.apk
```

Danach in der Tile-Auswahl „BlitzLink“ hinzufügen bzw. im Zifferblatt-Editor die Complication „BlitzLink Akku“
wählen. Handy- und Uhr-App müssen zusammenpassen (beide Release oder beide Debug), sonst finden sie sich nicht.

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


### Client-Secret

Ändert Opel ein Secret, lässt sich unter „API-Zugang & manueller Login“ ein eigenes
eintragen – es hat Vorrang vor dem hinterlegten.

Tokens und Secret liegen mit einem Android-Keystore-Schlüssel verschlüsselt auf dem Gerät.
Das Passwort speichert die App nicht.

## Bauen & Installieren

```
gradlew.bat assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk` – aufs Handy kopieren und installieren
(„Installation aus unbekannten Quellen“ erlauben) oder per `adb install`.
Alternativ das Projekt in Android Studio öffnen und auf ▶ drücken.

Die Debug-Version heißt „BlitzLink Debug“ (`com.astraconnect.app.debug`) und lässt sich neben dem Release
installieren. Sie hat eigene Daten, Anmeldung und Fernsteuerungs-Freischaltung.

Uhr-App: `wear/build/outputs/apk/debug/wear-debug.apk`. Die Debug-Version enthält eine Tile-Vorschau mit
Beispieldaten, die auch auf einem Handy-Emulator läuft:
`adb shell am start -n com.astraconnect.app.debug/com.astraconnect.wear.TilePreviewActivity`


## Lizenz

GPL-3.0 – siehe [LICENSE](LICENSE). Teile sind aus Open-Source-Projekten übernommen bzw. portiert:
[psa_car_controller](https://github.com/flobz/psa_car_controller) (GPL-3.0; OTP-Erzeugung, Modellliste `car_models.yml`)
und [homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles)
(MIT, Copyright (c) 2023 andreadegiovine; Lizenztext dort; Märkte, Client-IDs und Client-Secrets aus `configs.json`).

Inoffizielle App – nicht mit Opel oder Stellantis verbunden. „Opel“ und „Astra“ sind Marken ihrer jeweiligen Inhaber.

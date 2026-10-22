# ASK Ergebnis

Android-App für den ASK Mannersdorf: Ergebnis des letzten Spiels abrufen, Ergebnis-SMS über Esendex an eine Kontaktgruppe senden und einen Flyer für Facebook, Instagram und WhatsApp-Status erstellen.

## APK bauen (ohne Android Studio)

1. Auf GitHub ein neues, leeres Repository anlegen (z. B. `ASK-Ergebnis`).
2. Den Inhalt dieses Ordners hochladen (inkl. des versteckten Ordners `.github`).
3. Unter **Actions** startet „APK bauen“ automatisch (oder manuell über „Run workflow“).
4. Nach ca. 5 Minuten im Lauf unter **Artifacts** `ASK-Ergebnis-APK` herunterladen, entpacken und `app-debug.apk` am Handy installieren.

## Einrichtung in der App (Einstellungen)

| Feld | Was eintragen |
|---|---|
| Esendex Benutzername / Passwort | Deine Zugangsdaten von app.esendex.de |
| Absender | Name, der beim Empfänger erscheint (max. 11 Zeichen, nur bei Qualität N) |
| ÖFB-Widget-Schlüssel | ist vorausgefüllt: `e19268845f` |
| Vereins-Homepage | Adresse der Seite, auf der das ÖFB-Widget eingebunden ist (z. B. `https://www.askmannersdorf.at`). Das ÖFB prüft diese Domain. |
| Ligaportal-Teamseite | Link zur ASK-Teamseite auf ligaportal.at – wird verwendet, wenn das ÖFB nichts liefert |

## Ablauf nach dem Match

1. **Ergebnis abrufen** – ÖFB-Widget, sonst Ligaportal. Alles bleibt von Hand korrigierbar (Teams, Tore, Torschützen, Haken = Tor für ASK).
2. **SMS** – Text eintippen (oder „Vorschlag einfügen“), Esendex-Gruppe wählen, senden. Mit „Testnummer“ zuerst an dich selbst schicken.
3. **Flyer** – Schalter an, Mannschaftsfoto wählen, Format wählen (Beitrag 4:5 oder Status/Story 9:16), dann WhatsApp / Instagram / Facebook / In Galerie speichern.
   Titel automatisch: HEIMSIEG! / AUSWÄRTSSIEG! / UNENTSCHIEDEN! / NIEDERLAGE.

## Wenn etwas nicht erkannt wird

- Unter „Rohdaten der Abfrage anzeigen“ steht, was ÖFB bzw. Ligaportal geliefert haben. Diesen Text bitte an Claude schicken, dann wird die Erkennung angepasst (`MatchParser.kt`).
- Esendex-Fehler werden mit der Antwort des Servers angezeigt.

## Aufbau

- `data/WebPageExtractor.kt` – lädt ÖFB-Widget/Ligaportal unsichtbar und liest den Text aus (auch aus dem iFrame)
- `data/MatchParser.kt` – erkennt Teams, Ergebnis, Torschützen mit Minuten
- `data/EsendexClient.kt` – Login, Gruppen, Guthaben, Versand (`/smstogroups`, `/sms`)
- `flyer/FlyerRenderer.kt` – zeichnet den Flyer
- Schriften: Bebas Neue, Barlow Condensed (SIL Open Font License)

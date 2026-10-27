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
| Ligaportal-Spielplan | ist vorausgefüllt: `https://ticker.ligaportal.at/mannschaft/1323/ask-mannersdorf/spielplan` (Hauptquelle: Ergebnis + Torschützen) |
| LMO-Ligaseite | ist vorausgefüllt: `https://askmannersdorf.at/LMO_2026/liga.php?id=1` (Ergänzung/Ersatz) |
| Eigener Kurzname in der SMS | `ASK` |
| ÖFB-Widget-Schlüssel | ist vorausgefüllt: `e19268845f` |
| Vereins-Homepage | Adresse der Seite, auf der das ÖFB-Widget eingebunden ist (z. B. `https://www.askmannersdorf.at`). Das ÖFB prüft diese Domain. |
| Ligaportal-Teamseite | Link zur ASK-Teamseite auf ligaportal.at – wird verwendet, wenn das ÖFB nichts liefert |

## Ablauf nach dem Match

1. **Spiel wählen + Ergebnis abrufen** – oben stehen alle Spiele aus dem Ligaportal-Spielplan (vorausgewählt: das zuletzt gespielte). „Ergebnis abrufen (Liveticker)“ liest aus dem Liveticker des Spiels Endstand, Halbzeit, Torschützen mit Minute (aus den Spieldaten, sonst aus den Ticker-Meldungen) und – falls im Ticker vermerkt – das U23-Ergebnis. Alles bleibt von Hand korrigierbar.
2. **SMS** – wird automatisch nach Vereinsmuster erstellt, z. B.
   `Weissenbach - ASK: 1-4 (1-2); 0:1 Srok, 0:2 Sejdini, 1:2, 1:3 Srok, 1:4 Özel  U23: 4-0`
   (nur eigene Torschützen mit Namen, U23 von Hand eintragen). Text kann frei geändert werden; „Text neu erzeugen“ stellt das Muster wieder her. Esendex-Gruppe wählen, senden. Mit „Testnummer“ zuerst an dich selbst schicken.
3. **Flyer** – Schalter an, Mannschaftsfoto wählen, Format wählen (Beitrag 4:5 oder Status/Story 9:16).
   **Meta Business Suite** legt den Flyer in die Galerie, kopiert den Beitragstext und öffnet die Business-Suite-App – dort „Beitrag“ oder „Story“ erstellen, neuestes Foto wählen, Text einfügen, auf Facebook + Instagram posten.
   Torschützen am Flyer mit Minuten, z. B. „SROK 8., 85.“
   Außerdem: WhatsApp (Status) / Instagram / Facebook / In Galerie speichern.
   Titel automatisch: HEIMSIEG! / AUSWÄRTSSIEG! / UNENTSCHIEDEN! / NIEDERLAGE.

4. **Tor-Flyer** – ASK-Tor aus der Liste wählen (aus dem Liveticker, auch während des Spiels), Spielerfoto aus der Galerie wählen: „TREFFER FÜR DEN ASK – DURCH SROK! – NEUER SPIELSTAND 0:1“ mit Minute. Teilen wie beim Ergebnis-Flyer.

## Wenn etwas nicht erkannt wird

- Unter „Rohdaten der Abfrage anzeigen“ steht, was ÖFB bzw. Ligaportal geliefert haben. Diesen Text bitte an Claude schicken, dann wird die Erkennung angepasst (`MatchParser.kt`).
- Esendex-Fehler werden mit der Antwort des Servers angezeigt.

## Aufbau

- `data/WebPageExtractor.kt` – lädt ÖFB-Widget/Ligaportal unsichtbar und liest den Text aus (auch aus dem iFrame)
- `data/MatchParser.kt` – erkennt Teams, Ergebnis, Torschützen mit Minuten
- `data/EsendexClient.kt` – Login, Gruppen, Guthaben, Versand (`/smstogroups`, `/sms`)
- `flyer/FlyerRenderer.kt` – zeichnet den Flyer
- Schriften: Bebas Neue, Barlow Condensed (SIL Open Font License)

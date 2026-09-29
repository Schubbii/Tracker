# SecurityCam

Android-App, die ein altes Handy in eine Überwachungskamera verwandelt.

- Nimmt mit der Handykamera auf, auch wenn der **Bildschirm aus** ist oder die **App aus der Übersicht geschlossen** wurde (Vordergrund-Dienst mit Benachrichtigung).
- Speichert in 10-Minuten-Abschnitten (MP4, 720p). Ist das Speicherlimit erreicht, werden die **ältesten Aufnahmen automatisch gelöscht** (Endlosaufnahme).
- Ton optional (standardmäßig aus), Rück- oder Frontkamera wählbar.
- Aufnahmen in der App ansehen (antippen) oder löschen (lange drücken).

## Installation

1. Auf dem Handy die neueste `SecurityCam.apk` unter *Releases → securitycam-latest* herunterladen.
2. Öffnen und „Installation aus unbekannten Quellen“ erlauben.
3. Voraussetzung: Android 7.0 oder neuer. (iPhones erlauben keine Kameraaufnahme im Hintergrund.)

## Einrichtung

1. App öffnen, Kamera über die Vorschau ausrichten.
2. **„Akku-Optimierung ausschalten“** tippen – sonst beendet das System die Aufnahme evtl. nach einiger Zeit.
3. „Aufnahme starten“ – danach Bildschirm ausschalten. Stoppen über die App oder die Benachrichtigung.
4. Handy am Ladekabel lassen.

Bei Xiaomi, Huawei, Samsung u. a. zusätzlich in den Systemeinstellungen für SecurityCam „Autostart“ / „Im Hintergrund ausführen“ erlauben bzw. die App nicht „schlafen legen“.

## Speicherort

`Android/data/de.securitycam/files/Movies/Aufnahmen/` – am PC per USB-Kabel erreichbar.

## Selbst bauen

`./gradlew assembleDebug` (Android SDK nötig) oder GitHub Actions (`.github/workflows/securitycam.yml`).

# MB Survey (Android)

MB Survey: app Android per site survey Wi-Fi passivi e attivi in stile Ekahau Survey: planimetria, calibrazione, survey continua o stop-and-go, heatmap, report PDF ed export verso Ekahau (.esx), CSV e PNG. Installazione via APK, senza Play Store.

## Compilazione automatica (GitHub Actions)

A ogni push su `main` il workflow `.github/workflows/build.yml` compila l'APK firmato e lo pubblica come **Release** del repository (`WiFiSurvey-1.0.N.apk`). Dal telefono: GitHub → Releases → scarica l'APK → installa (consenti "origini sconosciute").

Il keystore in `keystore/wifisurvey.jks` (password `wifisurvey`) serve solo a firmare gli aggiornamenti in modo coerente: resta nel repo privato.

Compilazione locale: Android Studio o `./gradlew assembleRelease`.

## Requisiti sul telefono

- Android 10 o superiore.
- Permessi: posizione (richiesto da Android per le scansioni Wi-Fi), dispositivi Wi-Fi nelle vicinanze (Android 13+), notifiche.
- Localizzazione attiva.
- **Wi-Fi scan throttling disattivato**: Impostazioni → Opzioni sviluppatore → "Limitazione scansione Wi-Fi" (serve prima abilitare le Opzioni sviluppatore toccando 7 volte "Numero build"). Senza questa modifica Android limita a 4 scansioni ogni 2 minuti e la survey è inutilizzabile. L'app lo rileva e lo segnala.

## Flusso di lavoro

1. **Progetto**: nuovo progetto oppure *Importa .esx* (carica planimetrie bitmap, scala e AP pianificati da un progetto Ekahau, per validare un design).
2. **Piano**: aggiungi la planimetria da immagine o PDF. Modalità *Calibra*: tocca due punti a distanza nota e inserisci i metri.
3. **Survey**: premi il pulsante rosso, scegli il nome e la modalità:
   - *Continua*: tocca dove sei, cammina in linea retta a passo costante, tocca il punto raggiunto, e così via. Le scansioni intermedie sono posizionate interpolando sul tempo.
   - *Stop-and-go*: tocca il punto, resta fermo finché l'app raccoglie N scansioni (impostabile), spostati e ripeti.
   - *Annulla ultimo punto* toglie l'ultimo tratto.
4. **Heatmap** (aggiornate in tempo reale durante la survey): segnale primario, secondario, SNR (con rumore di fondo stimato: Android non espone il rumore), numero di AP, interferenza di canale, singolo BSSID. Filtri per SSID e banda. La legenda mostra min/media/max, area rilevata e percentuali di copertura rispetto alle soglie.
5. **AP**: *Aggiorna AP dalle misure* crea gli AP dai BSSID rilevati con posizione stimata; in modalità *AP* sulla mappa li posizioni a mano; nella scheda AP li rinomini e segni come "miei".
6. **Esporta** dal menu del progetto: CSV misure, backup .zip (reimportabile), PNG heatmap, report PDF, Ekahau .esx.

## Export Ekahau (.esx)

Il file è uno ZIP di JSON con schema 1.8.0. `project.json`, `floorPlans.json`, `images.json`, immagini e `accessPoints.json` seguono la struttura dei progetti Ekahau AI Pro 11.x; i file di riferimento (tipi di piano, muri, profili, requisiti) sono versioni minime proprie di MB Survey; `measuredRadios.json` e `accessPointMeasurements.json` seguono i nomi di campo usati dai tool pubblici. `surveys.json` (percorso e letture per punto) è una ricostruzione best-effort del formato Ekahau: va verificato aprendo il file in Ekahau; per finalizzarlo serve un `.esx` con una survey reale da cui ricavare la struttura esatta. Tutte le misure sono comunque incluse anche in `wifisurvey-measurements.json` dentro l'archivio.

## Struttura del codice

- `model/` — Project, Floor, AccessPoint, Survey, Scan (persistenza JSON)
- `data/ProjectStore` — file sotto `filesDir/projects/<id>/`
- `wifi/WifiScanService` — foreground service che esegue le scansioni e pubblica su `ScanBus`
- `survey/RecordingSession` — stato della survey in corso, indipendente dalla UI; `SurveyEngine` — interpolazione, aggregazione BSSID, stima posizione AP, metriche
- `heatmap/Heatmap` — interpolazione IDW su griglia e scale colore
- `ui/` — Activity e `FloorPlanView` (zoom/pan, overlay)
- `export/` — CSV, backup, PNG, PDF (`PdfReport`), `EsxExporter`, `EsxImporter`, `JsonImporter`

## Limiti noti

- La scheda Wi-Fi di uno smartphone è meno accurata di un adattatore professionale: valori buoni per validazione e troubleshooting, non per collaudi certificati.
- Planimetrie solo SVG in un .esx non vengono importate (serve la versione bitmap, che Ekahau include quasi sempre).
- Niente spettro né rumore reale (limiti di Android).

## Licenza

Software proprietario, tutti i diritti riservati (vedi `LICENSE`). Non è open source: nessuna copia, modifica o redistribuzione senza autorizzazione scritta dell'autore. Componenti di terze parti inclusi: AndroidX, Material Components, Kotlin (Apache 2.0), font Inter (SIL OFL 1.1), tabella OUI IEEE via `oui-data` (MIT).

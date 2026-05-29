package com.example.gesturecontrolapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * GestureService — Motorul principal al aplicației.
 *
 * Este un Foreground Service persistent care:
 * 1. Citește date brute de la senzorii hardware (Lumină, Accelerație, Gravitație).
 * 2. Aplică algoritmi DSP (medie mobilă, varianță statistică, state machine) pentru
 *    a transforma datele brute în gesturi cu sens: Wave, Hover, Tap, Flip.
 * 3. Rutează fiecare gest detectat către acțiunea configurată de utilizator
 *    (control media, lanternă, volum, DND etc.).
 *
 * Optimizat pentru Android 14+.
 */
public class GestureService extends Service implements SensorEventListener {

    private static final String TAG        = "GestureService";
    private static final String CHANNEL_ID = "GestureControlChannel";
    private static final String PREFS_NAME = "GesturePrefs";

    // ─────────────────────────────────────────────────────────────────────────
    //  BROADCAST ACTIONS
    //  Identificatori unici pentru mesajele trimise către alte componente.
    //  Prefixul pachetului previne conflictele cu alte aplicații instalate.
    // ─────────────────────────────────────────────────────────────────────────

    /** Trimis periodic (max 5x/sec) cu datele brute de la senzori → Developer Terminal. */
    public static final String ACTION_DEBUG_UPDATE =
            "com.example.gesturecontrolapp.DEBUG_UPDATE";

    /** Trimis instant când un gest e detectat → animează card-ul corespunzător în UI. */
    public static final String ACTION_GESTURE_FLASH =
            "com.example.gesturecontrolapp.GESTURE_FLASH";

    /** Trimis când utilizatorul cere screenshot → interceptat de AccessibilityService. */
    public static final String ACTION_PERFORM_SCREENSHOT =
            "com.example.gesturecontrolapp.PERFORM_SCREENSHOT";

    // ─────────────────────────────────────────────────────────────────────────
    //  CHEI EXTRA PENTRU BROADCAST-URI
    //  Funcționează ca etichete pe un colet: cu ele atașăm și extragem date
    //  dintr-un Intent (mesaj Android).
    // ─────────────────────────────────────────────────────────────────────────

    /** Numele gestului care a declanșat animația: "wave", "hover", "tap", "flip". */
    public static final String EXTRA_FLASH_GESTURE   = "extra_flash_gesture";
    /** Valoarea curentă a senzorului de lumină, în lux. */
    public static final String EXTRA_LUX             = "extra_lux";
    /** Media mobilă a luminii ambientale, calculată de serviciu, în lux. */
    public static final String EXTRA_AMBIENT         = "extra_ambient";
    /** Accelerația liniară curentă pe axa Z, în m/s². */
    public static final String EXTRA_ACCEL_Z         = "extra_accel_z";
    /** Varianța statistică a ferestrei de 15 valori de accelerație. */
    public static final String EXTRA_VARIANCE        = "extra_variance";
    /** Componenta Z a vectorului gravitațional, în m/s². */
    public static final String EXTRA_GRAVITY_Z       = "extra_gravity_z";
    /** Ultimele 10 gesturi detectate, formatate ca text pentru terminal. */
    public static final String EXTRA_HISTORY         = "extra_history";
    /** true dacă există o sesiune media activă (Spotify etc.), false altfel. */
    public static final String EXTRA_MEDIA_CONNECTED = "extra_media_connected";

    // ─────────────────────────────────────────────────────────────────────────
    //  HARDWARE & SISTEM
    // ─────────────────────────────────────────────────────────────────────────

    /** Manager principal pentru accesul la toți senzorii hardware. */
    private SensorManager sensorManager;

    /** Referințe la senzorii individuali obținuți din SensorManager. */
    private Sensor lightSensor;      // Senzor de lumină ambientală (lux)
    private Sensor linearAccSensor;  // Accelerație liniară fără gravitație (m/s²)
    private Sensor gravitySensor;    // Vectorul forței gravitaționale (m/s²)

    /** Starea curentă a lanternei: true = aprinsă, false = stinsă. */
    private boolean isFlashOn = false;

    /**
     * WakeLock parțial — ține CPU-ul activ fără să aprindă ecranul.
     * Necesar pentru ca Android să nu suspende procesul când ecranul e stins,
     * ceea ce ar opri livrarea datelor de la senzori.
     */
    private PowerManager.WakeLock wakeLock;

    /** Starea curentă a ecranului, actualizată de screenReceiver. */
    private boolean isScreenOn = true;

    // ─────────────────────────────────────────────────────────────────────────
    //  CACHE PREFERINȚE UTILIZATOR
    //
    //  PROBLEMA: onSensorChanged() e apelat ~100 ori/secundă.
    //  Dacă am citi SharedPreferences (I/O pe disk) la fiecare apel,
    //  aplicația ar consuma inutil CPU și baterie.
    //
    //  SOLUȚIA: Citim o singură dată la pornire și stocăm în memorie.
    //  Un listener detectează orice schimbare din UI și actualizează cache-ul.
    // ─────────────────────────────────────────────────────────────────────────

    /** Indică dacă modulul Magic Wave este activat din UI. */
    private boolean enableWave;
    /** Indică dacă modulul Hover & Hold este activat din UI. */
    private boolean enableHover;
    /** Indică dacă modulul Back-Tap este activat din UI. */
    private boolean enableTap;
    /** Indică dacă modulul Flip to Shush este activat din UI. */
    private boolean enableFlip;

    /** Codul acțiunii configurate pentru gestul Wave (ex: "ACTION_NEXT"). */
    private String actionWave;
    /** Codul acțiunii configurate pentru gestul Hover (ex: "ACTION_PLAY_PAUSE"). */
    private String actionHover;
    /** Codul acțiunii configurate pentru gestul Tap (ex: "ACTION_FLASHLIGHT"). */
    private String actionTap;
    /** Codul acțiunii configurate pentru gestul Flip (ex: "ACTION_SILENT"). */
    private String actionFlip;

    /**
     * Listener declanșat automat când utilizatorul modifică orice setare în UI.
     * Expresia lambda `(prefs, key) -> reloadPreferences(prefs)` este
     * echivalentul scurt al unei clase anonime cu metoda onSharedPreferenceChanged().
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (prefs, key) -> reloadPreferences(prefs);

    /**
     * Citește toate preferințele din disk și le stochează în variabilele cache.
     * Al doilea parametru al fiecărui apel este valoarea implicită returnată
     * dacă cheia nu există încă (prima rulare a aplicației).
     *
     * @param prefs Instanța SharedPreferences din care citim.
     */
    private void reloadPreferences(SharedPreferences prefs) {
        enableWave  = prefs.getBoolean("enableWave",  true);
        enableHover = prefs.getBoolean("enableHover", true);
        enableTap   = prefs.getBoolean("enableTap",   true);
        enableFlip  = prefs.getBoolean("enableFlip",  true);
        actionWave  = prefs.getString("actionWave",  "ACTION_NEXT");
        actionHover = prefs.getString("actionHover", "ACTION_PLAY_PAUSE");
        actionTap   = prefs.getString("actionTap",   "ACTION_FLASHLIGHT");
        actionFlip  = prefs.getString("actionFlip",  "ACTION_SILENT");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SCREEN RECEIVER
    //  Android trimite broadcast-uri globale la orice schimbare de stare a ecranului.
    //  Le interceptăm pentru a gestiona senzorii eficient din punct de vedere
    //  al consumului de baterie.
    // ─────────────────────────────────────────────────────────────────────────

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                // Ecranul s-a stins: dezactivăm senzorul de lumină (inutilizabil
                // fără ecran) dar păstrăm accelerometrul și gravitația active.
                isScreenOn = false;
                manageSensors();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                // Ecranul s-a aprins: reactivăm toți senzorii și resetăm
                // calibrarea ambientală pentru a porni proaspăt.
                isScreenOn = true;
                manageSensors();
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    //  DATE SENZORI — starea curentă folosită în broadcast-ul de debug
    // ─────────────────────────────────────────────────────────────────────────

    /** Ultima valoare citită de la senzorul de lumină, în lux. */
    private float currentLux = 0f;
    /** Ultima valoare a accelerației liniare pe axa Z, în m/s². */
    private float currentAccelZ = 0f;
    /** Ultima valoare a gravitației pe axa Z, în m/s². */
    private float currentGravityZ = 0f;
    /** Timestamp-ul ultimului broadcast de debug trimis, în ms. Folosit pentru throttle. */
    private long lastBroadcastTime = 0;

    // ─────────────────────────────────────────────────────────────────────────
    //  MODUL 1 & 2: MAGIC WAVE + HOVER & HOLD (Senzor Lumină)
    //
    //  PRINCIPIU: Senzorul de lumină măsoară lux-ul din cameră.
    //  Când mâna acoperă telefonul, valoarea scade brusc.
    //  Distingem între Wave (acoperire scurtă) și Hover (acoperire lungă).
    // ─────────────────────────────────────────────────────────────────────────

    /** true dacă senzorul de lumină e acoperit în momentul curent. */
    private boolean wasDark = false;
    /** Momentul (ms) când senzorul a fost acoperit, pentru măsurarea duratei gestului. */
    private long darkTimestamp = 0;

    /**
     * Media mobilă exponențială a luminii ambientale, în lux.
     * Formula: ambient = (ambient * 0.95) + (lux_curent * 0.05)
     * Inițializată la -1 ca semnal că nu a fost calibrată încă.
     * Actualizată doar când senzorul nu e acoperit și lumina > 5 lux.
     */
    private float ambientLux = -1f;

    /**
     * Contorizează sample-urile valide de calibrare.
     * Gesturile sunt IGNORATE până la 20 de sample-uri pentru a preveni
     * false pozitive imediat după pornirea serviciului (când ambientLux
     * nu reflectă încă realitatea).
     */
    private int ambientSampleCount = 0;

    /** true dacă acțiunea Hover a fost deja declanșată pentru acoperirea curentă. */
    private boolean hoverActionTriggered = false;

    /**
     * Handler pe thread-ul principal (UI thread) pentru programarea task-urilor
     * cu delay. Necesar deoarece onSensorChanged rulează pe un thread separat
     * și nu poate modifica direct UI-ul.
     */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Task-ul care se execută după 1500ms de acoperire continuă = Hover & Hold.
     * Dacă mâna e retrasă înainte de 1500ms, acest Runnable e anulat cu
     * removeCallbacks() și se declanșează Wave în schimb.
     */
    private final Runnable hoverHoldRunnable = () -> {
        // Verificăm din nou wasDark pentru că mâna ar putea fi retrasă
        // chiar în momentul în care timer-ul se declanșează (race condition minor)
        if (wasDark && enableHover) {
            executeAction(actionHover, "hover");
            hoverActionTriggered = true; // Marcăm că Hover a fost executat
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    //  MODUL 3: SMART BACK-TAP (Accelerometru Linear)
    //
    //  PRINCIPIU: Un tap pe spatele telefonului produce un șoc mecanic detectat
    //  pe axa Z a accelerometrului. Double tap = două șocuri la 150-600ms distanță.
    //
    //  PROVOCARE: Cum distingem un tap real de zgomotul de fond (mers, vibrații)?
    //  SOLUȚIE: Sliding Window + Varianță Statistică DSP.
    // ─────────────────────────────────────────────────────────────────────────

    /** Numărul de sample-uri din fereastra de analiză DSP. */
    private static final int WINDOW_SIZE = 15;

    /**
     * Pragul minim de accelerație pe Z pentru a considera un eveniment ca tap.
     * 2.8 m/s² ≈ 0.46g — suficient pentru un tap deliberat, dar mai mare decât
     * zgomotul de fond al mersului normal (~1-2 m/s²).
     */
    private static final float Z_THRESHOLD = 2.8f;

    /**
     * Varianța maximă acceptată a ferestrei pentru a confirma că telefonul e stabil.
     * Dacă varianța > 5, telefonul se mișcă ritmic (mers) și ignorăm tap-urile.
     */
    private static final float MAX_AMBIENT_VARIANCE = 5f;

    /**
     * Timp minim de așteptare între două gesturi consecutive, în ms.
     * Previne declanșările multiple accidentale după un gest reușit.
     */
    private static final long COOLDOWN_MS = 1200;

    /**
     * Buffer circular (sliding window) cu ultimele WINDOW_SIZE valori de accelerație.
     * "Circular" înseamnă că după ce se umple, noile valori suprascriu cele mai vechi,
     * fără alocare dinamică de memorie — eficient pentru execuție la 100Hz.
     */
    private final float[] zWindow = new float[WINDOW_SIZE];

    /** Indexul curent de scriere în buffer-ul circular. Resetat la 0 după WINDOW_SIZE. */
    private int windowIndex = 0;

    /** Timestamp-ul primului tap, în ms. Resetat la 0 după detecția unui double tap. */
    private long lastTapTime = 0;

    /** Timestamp-ul ultimului gest detectat cu succes, în ms. Folosit pentru cooldown. */
    private long lastGestureTime = 0;

    // ─────────────────────────────────────────────────────────────────────────
    //  MODUL 4: FLIP TO SHUSH (Senzor Gravitație)
    //
    //  PRINCIPIU: Gravitația pământului (~9.8 m/s²) acționează mereu în jos.
    //  Când telefonul e normal, Z ≈ +9.8. Când e întors cu fața în jos, Z ≈ -9.8.
    //  Detectăm tranziția la Z < -8.5 cu axele X și Y stabile (nu în cădere).
    // ─────────────────────────────────────────────────────────────────────────

    /** true dacă telefonul e în poziție face-down în momentul curent. */
    private boolean wasFaceDown = false;

    /** Timestamp-ul ultimului Flip detectat, în ms. Folosit pentru cooldown. */
    private long lastFlipTime = 0;

    /**
     * Cooldown specific pentru Flip, mai mare decât cel general.
     * Necesar deoarece vibratorul propriu al telefonului (declanșat ca feedback)
     * poate produce fluctuații ale senzorului de gravitație care ar re-declanșa
     * gestul imediat după execuție.
     */
    private static final long FLIP_COOLDOWN_MS = 3000;

    // ─────────────────────────────────────────────────────────────────────────
    //  GESTURE HISTORY LOG
    //  Ultimele 10 gesturi detectate, afișate în Developer Terminal.
    //  Folosit pentru debugging fără Logcat.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Coadă dublă (deque) cu ultimele HISTORY_MAX intrări.
     * addFirst() adaugă la început (cele mai recente sunt primele).
     * removeLast() elimină cel mai vechi element când se depășește limita.
     */
    private final ArrayDeque<String> gestureHistory = new ArrayDeque<>();

    /** Numărul maxim de intrări păstrate în history. */
    private static final int HISTORY_MAX = 10;

    /**
     * Formatator de timp pentru history log.
     * Locale.US garantează că ora e formatată consistent indiferent de
     * setările de regiune ale utilizatorului (ex: unele locale folosesc
     * separatori diferiți pentru ore).
     */
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ═════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * onCreate() — Apelat o singură dată la crearea serviciului.
     * Inițializăm toate resursele hardware și înregistrăm listener-ii.
     * NU pornim senzorii aici — asta se face în onStartCommand().
     */
    @Override
    public void onCreate() {
        super.onCreate();

        // Obținem accesul la managerul de senzori hardware
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            // getDefaultSensor() returnează null dacă senzorul nu există pe dispozitiv
            // (de ex. emulatoare sau telefoane buget fără senzor de lumină)
            lightSensor     = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            linearAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            gravitySensor   = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }

        // Configurăm WakeLock-ul — îl activăm mai târziu în onStartCommand()
        // după ce serviciul e în foreground (necesar pe Android 14+)
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    // Tag-ul apare în setările de baterie Android → ușurează debugging-ul
                    "GestureControlApp::SmartWakeLock");
        }

        // Înregistrăm receiver-ul pentru evenimentele ecranului
        // RECEIVER_EXPORTED: broadcast-urile SCREEN_ON/OFF vin din sistemul Android
        // (proces extern), deci trebuie să permitem recepția din exterior
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(
                this, screenReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);

        // Încărcăm preferințele în cache și ne abonăm la schimbările viitoare
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        reloadPreferences(prefs);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        Log.d(TAG, "Serviciu creat. Preferințe încărcate în cache.");
    }

    /**
     * onStartCommand() — Apelat la fiecare pornire/repornire a serviciului.
     * Poate fi apelat de mai multe ori (Android îl repornește după ce e omorât).
     * Configurăm notificarea foreground și pornim senzorii.
     *
     * @param intent  Intent-ul cu care a fost pornit serviciul (poate fi null la repornire)
     * @param flags   Flags care indică tipul de pornire (normală, repornire automată etc.)
     * @param startId ID unic al acestei porniri, folosit dacă vrem să oprim o pornire specifică
     * @return START_STICKY — Android va reporni serviciul automat dacă e omorât
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        // Construim notificarea persistentă — OBLIGATORIE pentru Foreground Service
        // Fără ea, Android 8+ aruncă ForegroundServiceStartNotAllowedException
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // Utilizatorul nu poate șterge notificarea prin swipe
                .build();

        // Promovăm serviciul la Foreground — acum Android nu îl va omorî pentru memorie
        startForeground(1, notification);

        // Pornim WakeLock cu timeout de siguranță de 10 ore
        // Fără timeout, un bug ar putea ține WakeLock-ul activ la infinit
        // și ar descărca bateria complet
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 60 * 1000L); // 10 ore în milisecunde
            Log.d(TAG, "WakeLock pornit (timeout 10h).");
        }

        // Pornim senzorii în funcție de starea curentă a ecranului
        manageSensors();

        return START_STICKY;
    }

    /**
     * manageSensors() — Gestionează ce senzori sunt activi în funcție de contextul curent.
     *
     * LOGICA DE OPTIMIZARE A BATERIEI:
     * - Accelerometru + Gravitație: active MEREU (Back-Tap și Flip funcționează
     *   și cu ecranul stins, de ex. muzică în buzunar)
     * - Senzor Lumină: activ DOAR cu ecranul pornit (inutil cu ecranul stins +
     *   consumă baterie inutil)
     *
     * unregisterListener(this) stinge serviciul de la TOȚI senzorii,
     * apoi re-înregistrăm doar ce avem nevoie — mai simplu decât să
     * gestionăm fiecare senzor individual.
     */
    private void manageSensors() {
        if (sensorManager == null) return;

        // Oprim comunicarea cu toți senzorii și reluăm de la zero
        sensorManager.unregisterListener(this);

        // Accelerometrul rulează la SENSOR_DELAY_GAME (~50 sample-uri/sec)
        // — un bun echilibru între reactivitate și consum de baterie
        if (linearAccSensor != null)
            sensorManager.registerListener(this, linearAccSensor,
                    SensorManager.SENSOR_DELAY_GAME);

        // Gravitația rulează mai lent (SENSOR_DELAY_NORMAL ~5 sample-uri/sec)
        // deoarece Flip e o mișcare lentă și nu necesită eșantionare rapidă
        if (gravitySensor != null)
            sensorManager.registerListener(this, gravitySensor,
                    SensorManager.SENSOR_DELAY_NORMAL);

        if (isScreenOn && lightSensor != null) {
            // SENSOR_DELAY_FASTEST — maxim posibil pentru a detecta wave-uri rapide
            // și pentru a deosebi corect Wave de Hover (timing critic)
            sensorManager.registerListener(this, lightSensor,
                    SensorManager.SENSOR_DELAY_FASTEST);
            // Resetăm starea la repornirea senzorului pentru a evita
            // false positive-uri din starea anterioară
            wasDark              = false;
            hoverActionTriggered = false;
            Log.d(TAG, "Senzor lumină activat (ecran pornit).");
        } else {
            Log.d(TAG, "Senzor lumină dezactivat (ecran stins → economie baterie).");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SENSOR LOOP — Inima aplicației
    //  Apelat de Android de fiecare dată când un senzor produce date noi.
    //  IMPORTANT: Rulează pe un thread dedicat senzorilor, NU pe UI thread.
    //  Nu facem operații costisitoare sau UI updates direct aici.
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {

            // ─────────────────────────────────────────────────────────────────
            //  SENZOR LUMINĂ → Magic Wave + Hover & Hold
            // ─────────────────────────────────────────────────────────────────
            case Sensor.TYPE_LIGHT: {
                currentLux = event.values[0]; // Indice 0 = singura valoare a senzorului de lumină

                // ── CALIBRARE AMBIENTALĂ ──────────────────────────────────────
                // Actualizăm media mobilă exponențială NUMAI când senzorul e liber
                // (mâna nu e deasupra) și lumina e semnificativă (> 5 lux)
                if (!wasDark && currentLux > 5.0f) {
                    if (ambientLux < 0f) {
                        // Prima valoare validă: inițializare directă în loc de medie
                        // (ar dura prea mult să convergi de la -1 la valoarea reală)
                        ambientLux = currentLux;
                        ambientSampleCount = 1;
                    } else {
                        // Media mobilă exponențială:
                        // 95% din valoarea veche + 5% din valoarea nouă
                        // Efectul: filtru low-pass care elimină fluctuații rapide
                        // și urmărește lent schimbările reale de iluminare din cameră
                        ambientLux = (ambientLux * 0.95f) + (currentLux * 0.05f);
                        if (ambientSampleCount < 20) ambientSampleCount++;
                    }
                }

                // Nu procesăm gesturi până nu avem o calibrare fiabilă (20 sample-uri)
                if (ambientSampleCount < 20) break;

                // Optimizare: nu procesăm dacă ambele module sunt dezactivate din UI
                if (!enableWave && !enableHover) break;

                // ── DETECȚIE ACOPERIRE ────────────────────────────────────────
                // Condiție compusă pentru robustețe pe orice tip de iluminare:
                // - < 50% din ambient: funcționează bine în camere luminoase
                // - < 5 lux absolut: captează acoperirile în camere întunecate
                //   unde 50% din 8 lux = 4 lux ar putea fi prea permisiv
                boolean isDark = (currentLux < ambientLux * 0.5f) || (currentLux < 5.0f);

                if (isDark && !wasDark) {
                    // ── TRANZIȚIE LIGHT → DARK (mâna acoperă senzorul) ───────
                    darkTimestamp        = SystemClock.elapsedRealtime();
                    wasDark              = true;
                    hoverActionTriggered = false;

                    // Programăm Hover să se declanșeze după 1500ms
                    // Dacă mâna e retrasă înainte, anulăm cu removeCallbacks()
                    if (enableHover) {
                        handler.postDelayed(hoverHoldRunnable, 1500);
                    }

                } else if (!isDark && wasDark) {
                    // ── TRANZIȚIE DARK → LIGHT (mâna e retrasă) ─────────────
                    long duration = SystemClock.elapsedRealtime() - darkTimestamp;
                    wasDark = false;

                    // Anulăm timer-ul Hover — dacă ajungea până aici, l-ar fi
                    // declanșat și Wave-ul în același timp (conflict)
                    handler.removeCallbacks(hoverHoldRunnable);

                    // Dacă Hover NU s-a declanșat (acoperire < 1500ms) și Wave e activ,
                    // verificăm dacă durata e în fereastra validă pentru un Wave:
                    // - > 10ms: elimină glitch-uri electronice (spike-uri de zgomot)
                    // - < 1200ms: o acoperire mai lungă nu e un wave natural
                    if (!hoverActionTriggered && enableWave) {
                        if (duration > 10 && duration < 1200) {
                            executeAction(actionWave, "wave");
                        }
                    }
                }
                break;
            }

            // ─────────────────────────────────────────────────────────────────
            //  ACCELEROMETRU LINEAR → Smart Back-Tap
            //  TYPE_LINEAR_ACCELERATION = accelerație fără gravitație
            //  (gravitația e scăzută automat de Android, deci Z=0 când e stabil)
            // ─────────────────────────────────────────────────────────────────
            case Sensor.TYPE_LINEAR_ACCELERATION: {
                currentAccelZ = event.values[2]; // [0]=X, [1]=Y, [2]=Z

                // ── SLIDING WINDOW UPDATE ─────────────────────────────────────
                // Scriem noua valoare în poziția curentă a buffer-ului circular
                zWindow[windowIndex] = currentAccelZ;
                // Avansăm indexul; % WINDOW_SIZE face ca după 14 să revenim la 0
                windowIndex = (windowIndex + 1) % WINDOW_SIZE;

                if (!enableTap) break;

                long now = SystemClock.elapsedRealtime();

                // ── COOLDOWN ──────────────────────────────────────────────────
                // Ignorăm orice în primele COOLDOWN_MS după un gest reușit
                // (prevenire declanșări multiple din același tap)
                if (now - lastGestureTime < COOLDOWN_MS) break;

                // Calculăm varianța ÎNAINTE de a verifica pragul,
                // pentru a include valoarea curentă în analiză
                float ambientVariance = calculateWindowVariance();

                if (Math.abs(currentAccelZ) > Z_THRESHOLD) {
                    long diff = now - lastTapTime;

                    if (diff > 150 && diff < 600) {
                        // ── AL DOILEA TAP DETECTAT ────────────────────────────
                        // Fereastra 150-600ms este fereastra naturală a unui double tap uman:
                        // - < 150ms: prea rapid, probabil rezonanță mecanică a primului tap
                        // - > 600ms: prea lent, nu mai e perceput ca double tap

                        // Validare duală — suficient dacă ORICARE condiție e îndeplinită:
                        // strongEnough: șocul e atât de puternic încât e intenționat,
                        //               chiar dacă telefonul se mișcă (ex: tap energic în mers)
                        boolean strongEnough = Math.abs(currentAccelZ) > Z_THRESHOLD * 1.1f;
                        // stableEnough: telefonul e suficient de stabil pentru a exclude mersul
                        boolean stableEnough = ambientVariance < MAX_AMBIENT_VARIANCE;

                        if (strongEnough || stableEnough) {
                            executeAction(actionTap, "tap");
                            lastTapTime     = 0;  // Resetăm pentru un viitor double tap
                            lastGestureTime = now; // Pornim cooldown-ul
                            // Golim buffer-ul pentru a preveni detectarea unui al 3-lea tap
                            // din valorile reziduale ale celui de-al doilea impact
                            Arrays.fill(zWindow, 0f);
                        }
                    } else {
                        // ── PRIMUL TAP (sau tap prea vechi) ──────────────────
                        // Salvăm timestamp-ul și așteptăm al doilea tap
                        lastTapTime = now;
                    }
                }
                break;
            }

            // ─────────────────────────────────────────────────────────────────
            //  GRAVITAȚIE → Flip to Shush
            //  TYPE_GRAVITY măsoară vectorul gravitational (include rotația telefonului)
            //  Normal: Z ≈ +9.8 m/s² (gravitația trage în jos)
            //  Face-down: Z ≈ -9.8 m/s² (gravitația trage invers față de ecran)
            // ─────────────────────────────────────────────────────────────────
            case Sensor.TYPE_GRAVITY: {
                currentGravityZ = event.values[2]; // [0]=X, [1]=Y, [2]=Z
                if (!enableFlip) break;

                long now = SystemClock.elapsedRealtime();

                // Cooldown specific pentru Flip — mai mare decât cel general
                // deoarece vibratorul propriu poate perturba senzorul
                if (now - lastFlipTime < FLIP_COOLDOWN_MS) break;

                // Prag de -8.5 în loc de -9.8 pentru a accepta și poziții
                // ușor înclinate, nu doar perfect orizontale
                boolean faceDown = currentGravityZ < -8.5f;

                if (faceDown && !wasFaceDown) {
                    // Detectăm TRANZIȚIA în face-down, nu starea continuă.
                    // Dacă am executa la fiecare sample, acțiunea s-ar repeta
                    // de zeci de ori cât timp telefonul stă pe masă.
                    executeAction(actionFlip, "flip");
                    lastFlipTime = now;
                }

                // Actualizăm starea pentru comparația la următorul sample
                wasFaceDown = faceDown;
                break;
            }
        }

        // Trimitem datele brute către UI (cu throttle intern de 200ms)
        broadcastDebugData();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  RUTARE ACȚIUNI
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Punctul central de execuție pentru toate gesturile detectate.
     * Primește codul acțiunii (din SharedPreferences) și îl mapează
     * la funcția hardware/software corespunzătoare.
     *
     * @param actionCode  Codul acțiunii (ex: "ACTION_NEXT", "ACTION_FLASHLIGHT")
     * @param gestureName Numele gestului sursei (ex: "wave", "tap") pentru log și UI
     */
    private void executeAction(String actionCode, String gestureName) {
        if (actionCode == null) return;

        // Log-ul de debug e dezactivat în build-urile de producție (BuildConfig.DEBUG=false)
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Trigger: " + gestureName + " -> " + actionCode);
        }

        // Adăugăm în history log și oferim feedback haptic utilizatorului
        addToHistory(gestureName, actionCode);
        triggerVibration(70); // 70ms — scurt, discret, dar perceptibil

        // Notificăm UI-ul să animeze card-ul gestului respectiv
        sendGestureFlashBroadcast(gestureName);

        switch (actionCode) {
            // ── CONTROL MEDIA ─────────────────────────────────────────────────
            case "ACTION_PLAY_PAUSE":
                MediaControllerService.sendMediaAction(this, MediaControllerService.ACTION_PLAY_PAUSE);
                break;
            case "ACTION_NEXT":
                MediaControllerService.sendMediaAction(this, MediaControllerService.ACTION_NEXT);
                break;
            case "ACTION_PREV":
                MediaControllerService.sendMediaAction(this, MediaControllerService.ACTION_PREV);
                break;
            case "ACTION_STOP_MUSIC":
                MediaControllerService.sendStopCommand(this);
                break;

            // ── CONTROL VOLUM ─────────────────────────────────────────────────
            case "ACTION_VOL_UP":
                // ADJUST_RAISE crește volumul cu un pas + afișează UI-ul nativ de volum
                adjustVolume(AudioManager.ADJUST_RAISE);
                break;
            case "ACTION_VOL_DOWN":
                adjustVolume(AudioManager.ADJUST_LOWER);
                break;

            // ── HARDWARE ──────────────────────────────────────────────────────
            case "ACTION_FLASHLIGHT":
                toggleFlashlight();
                break;
            case "ACTION_SCREENSHOT":
                // Delegat la AccessibilityService prin broadcast intern
                takeScreenshot();
                break;

            // ── AUDIO AMBIENT ─────────────────────────────────────────────────
            case "ACTION_SILENT":
                // RINGER_MODE_SILENT: fără sunet, fără vibrații pentru apeluri/notificări
                setRingerMode(AudioManager.RINGER_MODE_SILENT);
                break;
            case "ACTION_VIBRATE":
                // RINGER_MODE_VIBRATE: fără sunet, dar cu vibrații
                setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                break;
            case "ACTION_DND":
                activateDnd();
                break;

            default:
                Log.w(TAG, "Acțiune necunoscută primită: " + actionCode);
        }
    }

    /**
     * Trimite broadcast-ul de flash vizual către MainActivity.
     * Extras ca metodă separată pentru claritate și pentru a elimina
     * warning-ul "extract method" din Android Studio.
     *
     * @param gestureName Numele gestului care a declanșat animația
     */
    private void sendGestureFlashBroadcast(String gestureName) {
        Intent flashIntent = new Intent(ACTION_GESTURE_FLASH);
        // setPackage() restricționează broadcast-ul la aplicația noastră —
        // alte aplicații nu pot intercepta acest mesaj intern
        flashIntent.setPackage(getPackageName());
        flashIntent.putExtra(EXTRA_FLASH_GESTURE, gestureName);
        sendBroadcast(flashIntent);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HELPERS — Implementările acțiunilor individuale
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Crește sau scade volumul fluxului de muzică cu un pas.
     *
     * @param direction AudioManager.ADJUST_RAISE sau ADJUST_LOWER
     */
    private void adjustVolume(int direction) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, // Fluxul de muzică/media (nu apeluri sau sistem)
                    direction,
                    AudioManager.FLAG_SHOW_UI  // Afișează bara de volum nativă Android
            );
        }
    }

    /**
     * Setează modul de sonerie al telefonului.
     * Necesită permisiunea MODIFY_AUDIO_SETTINGS în Manifest.
     * Pe unele dispozitive cu DND activ, poate arunca SecurityException — ignorată.
     *
     * @param mode AudioManager.RINGER_MODE_SILENT, RINGER_MODE_VIBRATE sau RINGER_MODE_NORMAL
     */
    private void setRingerMode(int mode) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                am.setRingerMode(mode);
            } catch (SecurityException ignored) {
                // Apare dacă DND (Do Not Disturb) e activ și aplicația nu are
                // permisiunea MANAGE_AUDIO_SETTINGS — ignorăm silențios
            }
        }
    }

    /**
     * Activează modul Nu Deranja (Do Not Disturb) la nivel de sistem.
     * INTERRUPTION_FILTER_NONE = blocat complet (nicio notificare, niciun apel).
     * Dacă permisiunea nu e acordată, redirecționăm utilizatorul la setări.
     */
    private void activateDnd() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (nm.isNotificationPolicyAccessGranted()) {
            // Avem permisiunea → activăm DND direct
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
        } else {
            // Nu avem permisiunea → deschidem pagina de setări corespunzătoare
            // FLAG_ACTIVITY_NEW_TASK e obligatoriu când pornim o Activity din Service
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    /**
     * Solicită AccessibilityService-ului să efectueze screenshot-ul.
     * Nu putem face screenshot direct dintr-un Service obișnuit —
     * avem nevoie de GLOBAL_ACTION_TAKE_SCREENSHOT disponibil doar în
     * AccessibilityService (API 28+).
     * Comunicarea se face prin broadcast intern securizat.
     */
    private void takeScreenshot() {
        if (BuildConfig.DEBUG) Log.i(TAG, "Screenshot solicitat → AccessibilityService.");
        // Vibrație scurtă ca feedback imediat, înainte ca screenshot-ul să aibă loc
        triggerVibration(50);
        Intent intent = new Intent(ACTION_PERFORM_SCREENSHOT);
        intent.setPackage(getPackageName()); // Securizat — doar aplicația noastră îl primește
        sendBroadcast(intent);
    }

    /**
     * Comută lanterna între ON și OFF.
     * Folosim CameraManager (API Camera2) care e singurul mod oficial
     * de a controla lanterna independent de camera foto.
     *
     * Gestionăm două tipuri de erori:
     * - CameraAccessException: camera e folosită de altă aplicație
     * - Exception generică: hardware defect sau ID invalid
     */
    private void toggleFlashlight() {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return;
        try {
            // Index [0] = camera principală (spate) — singurul care are lanternă
            String cameraId = cm.getCameraIdList()[0];
            // Înainte de a actualiza isFlashOn, trimitem comanda hardware
            // Dacă setTorchMode aruncă excepție, isFlashOn nu va fi modificat
            // (starea rămâne consistentă cu realitatea)
            cm.setTorchMode(cameraId, !isFlashOn);
            isFlashOn = !isFlashOn; // Actualizăm starea doar dacă comanda a reușit
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Flash: " + (isFlashOn ? "ON" : "OFF"));
            }
        } catch (android.hardware.camera2.CameraAccessException e) {
            // Caz comun: utilizatorul a deschis Camera sau WhatsApp video
            Log.e(TAG, "Lanterna blocată — camera folosită de altă aplicație.");
        } catch (Exception e) {
            Log.e(TAG, "Eroare generică lanternă: " + e.getMessage());
        }
    }

    /**
     * Declanșează o vibrație one-shot de durata specificată.
     * Folosim exclusiv VibrationEffect (API 26+) — metoda veche vibrate(long)
     * e depreciată și minSdk-ul nostru e 26, deci verificarea e inutilă.
     *
     * @param duration Durata vibrației în milisecunde
     */
    private void triggerVibration(long duration) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createOneShot(
                    duration,
                    VibrationEffect.DEFAULT_AMPLITUDE // Intensitate implicită a sistemului
            ));
        }
    }

    /**
     * Adaugă o intrare în history log cu timestamp, gestur și acțiune.
     * Formatul: "14:23:05  [WAVE]  →  ACTION_NEXT"
     *
     * @param gesture Numele gestului (ex: "wave", "tap")
     * @param action  Codul acțiunii executate (ex: "ACTION_NEXT")
     */
    private void addToHistory(String gesture, String action) {
        String entry = sdf.format(new Date())
                + "  [" + gesture.toUpperCase(Locale.US) + "]  →  " + action;
        gestureHistory.addFirst(entry); // Cele mai recente sunt primele
        // Eliminăm cel mai vechi element când depășim limita
        if (gestureHistory.size() > HISTORY_MAX) gestureHistory.removeLast();
    }

    /**
     * Construiește Intent-ul cu toate datele de diagnosticare pentru Developer Terminal.
     * Extras ca metodă separată pentru lizibilitate și pentru a elimina
     * warning-ul "extract method" din Android Studio.
     *
     * @return Intent complet cu toate extra-urile atașate
     */
    private Intent buildDebugIntent() {
        boolean mediaConnected = MediaControllerService.isMediaConnected(this);

        // Construim string-ul history din toate intrările din deque
        StringBuilder sb = new StringBuilder();
        for (String entry : gestureHistory) sb.append(entry).append("\n");

        Intent intent = new Intent(ACTION_DEBUG_UPDATE);
        intent.setPackage(getPackageName()); // Broadcast intern securizat
        intent.putExtra(EXTRA_LUX,             currentLux);
        // Dacă ambientLux nu e calibrat (-1), trimitem 0 pentru UI
        intent.putExtra(EXTRA_AMBIENT,         ambientLux < 0 ? 0f : ambientLux);
        intent.putExtra(EXTRA_ACCEL_Z,         currentAccelZ);
        intent.putExtra(EXTRA_VARIANCE,        calculateWindowVariance());
        intent.putExtra(EXTRA_GRAVITY_Z,       currentGravityZ);
        intent.putExtra(EXTRA_HISTORY,         sb.toString().trim());
        intent.putExtra(EXTRA_MEDIA_CONNECTED, mediaConnected);
        return intent;
    }

    /**
     * Trimite datele de diagnosticare către UI cu un throttle de 200ms.
     * Senzorii produc date la 50-200Hz, dar UI-ul nu poate procesa mai repede
     * de ~5 update-uri/secundă fără să devină pasiv.
     * 200ms = maxim 5 update-uri/secundă = suficient pentru un terminal live.
     */
    private void broadcastDebugData() {
        long now = SystemClock.uptimeMillis();
        // Dacă au trecut mai puțin de 200ms de la ultimul broadcast, ignorăm
        if (now - lastBroadcastTime < 200) return;
        lastBroadcastTime = now;
        sendBroadcast(buildDebugIntent());
    }

    /**
     * Calculează varianța statistică a valorilor din sliding window.
     *
     * FORMULĂ:
     * 1. Media = suma tuturor valorilor / numărul de valori
     * 2. Varianța = media pătratelor diferențelor față de medie
     *    Var = Σ(xi - medie)² / N
     *
     * INTERPRETARE:
     * - Varianță mică (< 3.5): valorile sunt grupate → telefon stabil → tap valid
     * - Varianță mare (> 3.5): valorile sunt răspândite → mișcare ritmică → probabil mers
     *
     * @return Varianța ferestrei curente de WINDOW_SIZE valori
     */
    private float calculateWindowVariance() {
        // Pasul 1: calculăm suma pentru medie
        float sum = 0;
        for (float v : zWindow) sum += v;
        float mean = sum / WINDOW_SIZE;

        // Pasul 2: calculăm suma pătratelor diferențelor față de medie
        float varianceSum = 0;
        for (float v : zWindow) varianceSum += (v - mean) * (v - mean);

        return varianceSum / WINDOW_SIZE;
    }

    /**
     * Creează canalul de notificări necesar pentru Foreground Service pe Android 8+.
     * IMPORTANCE_LOW = notificarea apare în bara de status fără sunet/vibrație.
     * Canalele sunt persistente — odată creat, nu poate fi șters de aplicație,
     * dar apelul e invariabil (sigur să fie apelat la fiecare pornire).
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Gesture Service",              // Numele afișat în setările aplicației
                NotificationManager.IMPORTANCE_LOW); // Fără sunet la fiecare update
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  METODE OBLIGATORII DIN INTERFEȚELE EXTINSE
    // ═════════════════════════════════════════════════════════════════════════

    /** Nu ne interesează schimbările de precizie ale senzorilor — implementare goală. */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Serviciul nostru nu suportă binding (comunicare directă client-server).
     * Returnăm null pentru a indica că e un Started Service, nu un Bound Service.
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    /**
     * onDestroy() — Apelat când serviciul e oprit definitiv.
     * OBLIGATORIU: eliberăm TOATE resursele pentru a preveni memory leak-uri
     * și consumul în continuare de baterie.
     */
    @Override
    public void onDestroy() {
        // Dezabonăm de la toți senzorii — fără asta, Android continuă să livreze
        // date și consumă baterie chiar dacă serviciul e "oprit"
        if (sensorManager != null) sensorManager.unregisterListener(this);

        // Anulăm orice Runnable pending — fără asta, hoverHoldRunnable ar putea
        // executa după onDestroy și ar accesa resurse deja eliberate (crash)
        handler.removeCallbacksAndMessages(null);

        // Radiem listener-ul de preferințe pentru a evita memory leak
        // (SharedPreferences ține o referință la listener, deci și la Service)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);

        // Radiem screen receiver
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}

        // Eliberăm WakeLock-ul — CRITIC: fără asta, CPU rămâne activ și
        // bateria se descarcă complet în câteva ore
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock eliberat.");
        }
        super.onDestroy();
    }
}
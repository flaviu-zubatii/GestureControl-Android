package com.example.gesturecontrolapp;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.Locale;
import java.util.Set;

/**
 * MainActivity — Interfața grafică a aplicației.
 *
 * Responsabilități:
 * 1. Pornește GestureService la lansare.
 * 2. Permite utilizatorului să configureze ce face fiecare gest (Switch + Spinner).
 * 3. Afișează starea serviciului și a conexiunii media în timp real.
 * 4. Animează card-urile când un gest e detectat (feedback vizual).
 * 5. Conține Developer Terminal ascuns (Easter Egg: 7 tap-uri pe titlu).
 */
public class MainActivity extends AppCompatActivity {

    // ─────────────────────────────────────────────────────────────────────────
    //  REFERINȚE UI
    //  Obținute în bindViews() prin findViewById() și refolosite în toată clasa.
    //  Câmpuri private pentru a forța accesul doar prin metodele acestei clase.
    // ─────────────────────────────────────────────────────────────────────────

    /** Cercul colorat din header care indică starea serviciului. */
    private View     statusDot;
    /** Textul de lângă statusDot: "Serviciu activ · Media conectat/deconectat". */
    private TextView tvServiceStatus;
    /** Banner galben afișat dacă permisiunea Notification Access lipsește. */
    private CardView cardPermissionWarning;
    /** Card-ul Developer Terminal, invizibil implicit, deblocat cu Easter Egg. */
    private CardView cardDeveloperMode;

    /** Card-urile gesturilor — referite pentru animația de flash la detecție. */
    private CardView cardWave, cardHover, cardTap, cardFlip;

    /** Câmpurile din Developer Terminal, actualizate live la fiecare broadcast. */
    private TextView tvDevLight, tvDevLightState;
    private TextView tvDevAccel, tvDevVariance, tvDevGravity;
    /** Log-ul celor mai recente 10 gesturi detectate. */
    private TextView tvDevHistory;

    // ─────────────────────────────────────────────────────────────────────────
    //  EASTER EGG — Developer Mode
    //  7 tap-uri rapide (< 500ms între ele) pe titlul "Gesture Control"
    //  deblochează/ascunde Developer Terminal.
    // ─────────────────────────────────────────────────────────────────────────

    /** Contorul de tap-uri consecutive pe titlu. Resetat la 0 după > 500ms pauză. */
    private int  tapCount    = 0;
    /** Timestamp-ul ultimului tap pe titlu, pentru calculul pauzei dintre tap-uri. */
    private long lastTapTime = 0;

    // ─────────────────────────────────────────────────────────────────────────
    //  DEFINIȚIILE ACȚIUNILOR PENTRU FIECARE GEST
    //
    //  Fiecare gest are propriul set de acțiuni posibile — nu toate gesturile
    //  pot face toate acțiunile (ex: Flip e dedicat controlului audio ambient).
    //
    //  STRUCTURĂ PARALELĂ: actionNames[i] corespunde ÎNTOTDEAUNA cu actionCodes[i].
    //  names = ce vede utilizatorul în Spinner
    //  codes = ce se salvează în SharedPreferences și se trimite la GestureService
    // ─────────────────────────────────────────────────────────────────────────

    /** Textele afișate în dropdown-ul Magic Wave. */
    private final String[] waveActionNames = {
            "Următoarea piesă",   // → ACTION_NEXT
            "Piesa anterioară",   // → ACTION_PREV
            "Play / Pause",       // → ACTION_PLAY_PAUSE
            "Volum +",            // → ACTION_VOL_UP
            "Volum -"             // → ACTION_VOL_DOWN
    };
    /** Codurile interne salvate în SharedPreferences pentru Magic Wave. */
    private final String[] waveActionCodes = {
            "ACTION_NEXT", "ACTION_PREV", "ACTION_PLAY_PAUSE",
            "ACTION_VOL_UP", "ACTION_VOL_DOWN"
    };

    /** Textele afișate în dropdown-ul Hover & Hold. */
    private final String[] hoverActionNames = {
            "Play / Pause",       // → ACTION_PLAY_PAUSE
            "Volum +",            // → ACTION_VOL_UP
            "Volum -"             // → ACTION_VOL_DOWN
    };
    /** Codurile interne pentru Hover & Hold. */
    private final String[] hoverActionCodes = {
            "ACTION_PLAY_PAUSE", "ACTION_VOL_UP", "ACTION_VOL_DOWN"
    };

    /** Textele afișate în dropdown-ul Back-Tap. */
    private final String[] tapActionNames = {
            "Lanternă",           // → ACTION_FLASHLIGHT
            "Screenshot"          // → ACTION_SCREENSHOT
    };
    /** Codurile interne pentru Back-Tap. */
    private final String[] tapActionCodes = {
            "ACTION_FLASHLIGHT", "ACTION_SCREENSHOT"
    };

    /** Textele afișate în dropdown-ul Flip to Shush. */
    private final String[] flipActionNames = {
            "Silențios",          // → ACTION_SILENT
            "Vibrații",           // → ACTION_VIBRATE
            "Nu deranja (DND)",   // → ACTION_DND
            "Oprește muzica"      // → ACTION_STOP_MUSIC
    };
    /** Codurile interne pentru Flip to Shush. */
    private final String[] flipActionCodes = {
            "ACTION_SILENT", "ACTION_VIBRATE", "ACTION_DND", "ACTION_STOP_MUSIC"
    };

    // ─────────────────────────────────────────────────────────────────────────
    //  BROADCAST RECEIVERS
    //  Înregistrate în onResume() și șterse în onPause() — există
    //  DOAR când Activity-ul e vizibil pentru a evita procesarea inutilă.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Primește datele brute de la senzori (trimise de GestureService la 5x/sec)
     * și actualizează Developer Terminal și status bar-ul.
     */
    private final BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!GestureService.ACTION_DEBUG_UPDATE.equals(intent.getAction())) return;

            // Extragem toate valorile din Intent folosind cheile definite în GestureService
            float lux      = intent.getFloatExtra(GestureService.EXTRA_LUX, 0f);
            float ambient  = intent.getFloatExtra(GestureService.EXTRA_AMBIENT, 0f);
            float accelZ   = intent.getFloatExtra(GestureService.EXTRA_ACCEL_Z, 0f);
            float variance = intent.getFloatExtra(GestureService.EXTRA_VARIANCE, 0f);
            float gravityZ = intent.getFloatExtra(GestureService.EXTRA_GRAVITY_Z, 0f);
            String history = intent.getStringExtra(GestureService.EXTRA_HISTORY);
            boolean mediaOk = intent.getBooleanExtra(
                    GestureService.EXTRA_MEDIA_CONNECTED, false);

            updateStatusBar(mediaOk);
            updateDeveloperUI(lux, ambient, accelZ, variance, gravityZ, history);
        }
    };

    /**
     * Primește notificarea că un gest a fost detectat și declanșează
     * animația de flash pe card-ul corespunzător gestului.
     * Oferă feedback vizual imediat utilizatorului.
     */
    private final BroadcastReceiver flashReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!GestureService.ACTION_GESTURE_FLASH.equals(intent.getAction())) return;

            String gesture = intent.getStringExtra(GestureService.EXTRA_FLASH_GESTURE);
            if (gesture == null) return;

            // Mapăm numele gestului la referința card-ului corespunzător
            CardView target = null;
            switch (gesture) {
                case "wave":  target = cardWave;  break;
                case "hover": target = cardHover; break;
                case "tap":   target = cardTap;   break;
                case "flip":  target = cardFlip;  break;
            }
            if (target != null) flashCard(target);
        }
    };

    // ═════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * onCreate() — Apelat o singură dată la crearea Activity-ului.
     * Ordinea apelurilor e importantă: bindViews() ÎNAINTE de setupSpinners()
     * deoarece setup-ul are nevoie de referințele UI.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Construim interfața layout-ul XML

        bindViews();            // 1. Obținem referințele la elementele UI
        setupSpinners();        // 2. Configurăm dropdown-urile cu opțiunile per gest
        setupSwitches();        // 3. Configurăm toggle-urile ON/OFF
        setupEasterEgg();       // 4. Activăm Easter Egg-ul pe titlu
        checkNotificationAccess(); // 5. Verificăm permisiunea media și afișăm banner dacă lipsește
        startGestureService();  // 6. Pornim serviciul de fundal
    }

    /**
     * onResume() — Apelat când Activity devine vizibil și interactiv.
     * Înregistrăm receiver-ii DOAR când UI-ul e vizibil — nu are rost să
     * procesăm update-uri când utilizatorul e în altă aplicație.
     */
    @Override
    protected void onResume() {
        super.onResume();

        // Înregistrăm ambii receiveri cu flag NOT_EXPORTED:
        // broadcast-urile noastre interne nu trebuie să fie accesibile altor aplicații
        IntentFilter f1 = new IntentFilter(GestureService.ACTION_DEBUG_UPDATE);
        ContextCompat.registerReceiver(this, debugReceiver, f1,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        IntentFilter f2 = new IntentFilter(GestureService.ACTION_GESTURE_FLASH);
        ContextCompat.registerReceiver(this, flashReceiver, f2,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // Re-verificăm permisiunea la fiecare revenire în Activity —
        // utilizatorul ar fi putut acorda/revoca permisiunea în background
        refreshPermissionBanner();
    }

    /**
     * onPause() — Apelat când Activity nu mai e vizibil (altă aplicație, ecran stins).
     * Anulăm receiver-ii pentru a nu procesa update-uri invizibile.
     * try-catch pentru cazul în care receiver-ul nu a fost înregistrat
     * (de ex. crash în onResume înainte de înregistrare).
     */
    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(debugReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(flashReceiver); } catch (Exception ignored) {}
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SETUP — Inițializarea componentelor UI
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Obține referințele la toate elementele UI din layout prin ID.
     * Centralizat într-o singură metodă pentru claritate și ușurință de întreținere.
     * Apelat PRIMUL în onCreate() — toate celelalte metode de setup depind de el.
     */
    private void bindViews() {
        statusDot             = findViewById(R.id.statusDot);
        tvServiceStatus       = findViewById(R.id.tvServiceStatus);
        cardPermissionWarning = findViewById(R.id.cardPermissionWarning);
        cardDeveloperMode     = findViewById(R.id.cardDeveloperMode);
        cardWave              = findViewById(R.id.cardWave);
        cardHover             = findViewById(R.id.cardHover);
        cardTap               = findViewById(R.id.cardTap);
        cardFlip              = findViewById(R.id.cardFlip);
        tvDevLight            = findViewById(R.id.tvDevLight);
        tvDevLightState       = findViewById(R.id.tvDevLightState);
        tvDevAccel            = findViewById(R.id.tvDevAccel);
        tvDevVariance         = findViewById(R.id.tvDevVariance);
        tvDevGravity          = findViewById(R.id.tvDevGravity);
        tvDevHistory          = findViewById(R.id.tvDevHistory);
    }

    /**
     * Configurează dropdown-urile (Spinner) pentru selectarea acțiunii fiecărui gest.
     * Fiecare Spinner are propriul adapter cu propriile opțiuni — nu există
     * un adapter global, deoarece fiecare gest are un set diferit de acțiuni posibile.
     */
    private void setupSpinners() {
        SharedPreferences prefs = getSharedPreferences("GesturePrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        Spinner spinnerWave  = findViewById(R.id.spinnerWave);
        Spinner spinnerHover = findViewById(R.id.spinnerHover);
        Spinner spinnerTap   = findViewById(R.id.spinnerTap);
        Spinner spinnerFlip  = findViewById(R.id.spinnerFlip);

        // Fiecare spinner primește adapter-ul cu lista sa specifică de opțiuni
        spinnerWave.setAdapter(makeAdapter(waveActionNames));
        spinnerHover.setAdapter(makeAdapter(hoverActionNames));
        spinnerTap.setAdapter(makeAdapter(tapActionNames));
        spinnerFlip.setAdapter(makeAdapter(flipActionNames));

        // Restaurăm selecția salvată — indexOf() caută codul salvat în array-ul de coduri
        // și returnează indexul corespunzător pentru a seta poziția corectă în Spinner
        spinnerWave.setSelection(indexOf(
                prefs.getString("actionWave",  "ACTION_NEXT"),       waveActionCodes));
        spinnerHover.setSelection(indexOf(
                prefs.getString("actionHover", "ACTION_PLAY_PAUSE"), hoverActionCodes));
        spinnerTap.setSelection(indexOf(
                prefs.getString("actionTap",   "ACTION_FLASHLIGHT"), tapActionCodes));
        spinnerFlip.setSelection(indexOf(
                prefs.getString("actionFlip",  "ACTION_SILENT"),     flipActionCodes));

        // Listener-ii salvează selecția în SharedPreferences la orice schimbare
        // GestureService va detecta schimbarea prin prefListener și va reîncărca cache-ul
        spinnerWave.setOnItemSelectedListener(
                makeListener("actionWave",  waveActionCodes,  editor));
        spinnerHover.setOnItemSelectedListener(
                makeListener("actionHover", hoverActionCodes, editor));
        spinnerTap.setOnItemSelectedListener(
                makeListener("actionTap",   tapActionCodes,   editor));
        spinnerFlip.setOnItemSelectedListener(
                makeListener("actionFlip",  flipActionCodes,  editor));
    }

    /**
     * Configurează switch-urile ON/OFF pentru activarea/dezactivarea fiecărui modul.
     * Restaurăm starea salvată și atașăm listener care salvează schimbările instant.
     */
    private void setupSwitches() {
        SharedPreferences prefs = getSharedPreferences("GesturePrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        SwitchMaterial switchWave  = findViewById(R.id.switchWave);
        SwitchMaterial switchHover = findViewById(R.id.switchHover);
        SwitchMaterial switchTap   = findViewById(R.id.switchTap);
        SwitchMaterial switchFlip  = findViewById(R.id.switchFlip);

        // Restaurăm starea salvată (default: toate activate)
        switchWave.setChecked(prefs.getBoolean("enableWave",  true));
        switchHover.setChecked(prefs.getBoolean("enableHover", true));
        switchTap.setChecked(prefs.getBoolean("enableTap",    true));
        switchFlip.setChecked(prefs.getBoolean("enableFlip",  true));

        // Lambda (b, v) -> ... este shorthand pentru OnCheckedChangeListener
        // b = butonul, v = noua valoare boolean
        switchWave.setOnCheckedChangeListener( (b, v) -> editor.putBoolean("enableWave",  v).apply());
        switchHover.setOnCheckedChangeListener((b, v) -> editor.putBoolean("enableHover", v).apply());
        switchTap.setOnCheckedChangeListener(  (b, v) -> editor.putBoolean("enableTap",   v).apply());
        switchFlip.setOnCheckedChangeListener( (b, v) -> editor.putBoolean("enableFlip",  v).apply());
    }

    /**
     * Configurează Easter Egg-ul: 7 tap-uri rapide pe titlu = Developer Terminal.
     * Logica: numărăm tap-urile consecutive cu pauză < 500ms între ele.
     * La >= 3 tap-uri afișăm un hint. La exact 7 tap-uri toggle Developer Mode.
     */
    private void setupEasterEgg() {
        TextView tvTitle = findViewById(R.id.tvHeaderTitle);
        SharedPreferences prefs = getSharedPreferences("GesturePrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Dacă Developer Mode era deja deblocat (sesiune anterioară), îl afișăm direct
        if (prefs.getBoolean("devModeUnlocked", false)) {
            cardDeveloperMode.setVisibility(View.VISIBLE);
        }

        tvTitle.setOnClickListener(v -> {
            long now = SystemClock.uptimeMillis();
            // Dacă pauza e > 500ms, resetăm contorul — secvența trebuie să fie rapidă
            if (now - lastTapTime > 500) tapCount = 0;
            lastTapTime = now;
            tapCount++;

            if (tapCount == 7) {
                // Toggle: dacă era deblocat → ascundem; dacă era ascuns → deblocăm
                boolean unlocked = prefs.getBoolean("devModeUnlocked", false);
                editor.putBoolean("devModeUnlocked", !unlocked).apply();
                cardDeveloperMode.setVisibility(!unlocked ? View.VISIBLE : View.GONE);
                Toast.makeText(this,
                        getString(!unlocked
                                ? R.string.dev_mode_unlocked
                                : R.string.dev_mode_hidden),
                        Toast.LENGTH_SHORT).show();
                tapCount = 0; // Resetăm pentru a putea re-declanșa
            } else if (tapCount >= 3) {
                // Hint progresiv: "Mai apasă de 4 ori", "... de 3 ori" etc.
                Toast.makeText(this,
                        getString(R.string.dev_mode_hint, (7 - tapCount)),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI UPDATES — Actualizări ale interfeței grafice
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Actualizează status bar-ul din header cu starea conexiunii media.
     * runOnUiThread() e necesar deoarece metoda poate fi apelată din
     * thread-ul receiver-ului, nu din UI thread.
     *
     * @param mediaConnected true dacă există o sesiune media activă (Spotify etc.)
     */
    private void updateStatusBar(boolean mediaConnected) {
        runOnUiThread(() -> {
            if (mediaConnected) {
                // Punct verde + text pozitiv
                statusDot.setBackgroundResource(R.drawable.circle_green);
                tvServiceStatus.setText(R.string.status_media_connected);
                tvServiceStatus.setTextColor(
                        ContextCompat.getColor(this, R.color.status_green));
            } else {
                // Punct galben + text de avertizare
                statusDot.setBackgroundResource(R.drawable.circle_yellow);
                tvServiceStatus.setText(R.string.status_media_disconnected);
                tvServiceStatus.setTextColor(
                        ContextCompat.getColor(this, R.color.status_yellow));
            }
        });
    }

    /**
     * Actualizează toate câmpurile din Developer Terminal cu datele primite.
     * Verificăm vizibilitatea card-ului înainte de update pentru a nu
     * face work inutil când terminalul e ascuns.
     *
     * Locale.US în String.format garantează că separatorul zecimal e "."
     * indiferent de setările de regiune (ex: în România default ar fi ",")
     */
    private void updateDeveloperUI(float lux, float ambient, float accelZ,
                                   float variance, float gravityZ, String history) {
        runOnUiThread(() -> {
            if (cardDeveloperMode.getVisibility() != View.VISIBLE) return;

            tvDevLight.setText(
                    String.format(Locale.US, "> LUX_DATA: %.1f | AMBIENT: %.1f", lux, ambient));
            // Afișăm starea interpretată a senzorului de lumină
            tvDevLightState.setText(
                    ((lux < ambient * 0.5f) || lux < 5.0f)
                            ? "> STATE: DARK (COVERED)"   // Mâna e pe senzor
                            : "> STATE: LIGHT (FREE)");    // Senzorul e liber
            tvDevAccel.setText(
                    String.format(Locale.US, "> ACCEL_Z: %.2f", accelZ));
            tvDevVariance.setText(
                    String.format(Locale.US, "> DSP_VAR: %.2f", variance));
            tvDevGravity.setText(
                    String.format(Locale.US, "> GRAVITY_Z: %.2f", gravityZ));

            // Actualizăm history doar dacă există conținut nou
            if (history != null && !history.isEmpty()) {
                tvDevHistory.setText(history);
            }
        });
    }

    /**
     * Verifică dacă permisiunea Notification Listener e acordată și
     * afișează/ascunde banner-ul de avertizare corespunzător.
     * Re-verificăm la fiecare onResume() deoarece utilizatorul ar fi putut
     * acorda/revoca permisiunea între timp.
     */
    private void refreshPermissionBanner() {
        // getEnabledListenerPackages() returnează set-ul de pachete cu acces la notificări
        Set<String> enabled = androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(this);
        boolean hasPermission = enabled.contains(getPackageName());

        // Ascundem banner-ul dacă permisiunea e acordată, îl afișăm dacă nu
        cardPermissionWarning.setVisibility(hasPermission ? View.GONE : View.VISIBLE);

        if (!hasPermission) {
            // Atașăm listener pe butonul "Activează" care deschide setările Android
            View btnFix = cardPermissionWarning.findViewById(R.id.btnFixPermission);
            if (btnFix != null) {
                btnFix.setOnClickListener(v ->
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
            }
        }
    }

    /** Apelat la pornire — verifică permisiunea fără a redirecționa forțat. */
    private void checkNotificationAccess() {
        refreshPermissionBanner();
    }

    /**
     * Animează un CardView cu o flash de culoare pentru feedback vizual la detectarea gestului.
     *
     * MECANISM:
     * ValueAnimator interpolează între 3 valori de culoare: curentă → highlight → curentă
     * ArgbEvaluator calculează culorile intermediare (A, R, G, B component cu component)
     * DecelerateInterpolator face ca animația să înceapă rapid și să se termine lent
     *
     * @param card Card-ul care va fi animat (corespunzător gestului detectat)
     */
    private void flashCard(CardView card) {
        int colorFrom = card.getCardBackgroundColor().getDefaultColor();
        int colorTo   = ContextCompat.getColor(this, R.color.flash_highlight);

        // ofObject() cu ArgbEvaluator interpolează culori în spațiul ARGB
        // Secvența colorFrom → colorTo → colorFrom = dus-întors (pulse effect)
        ValueAnimator animator = ValueAnimator.ofObject(
                new ArgbEvaluator(), colorFrom, colorTo, colorFrom);
        animator.setDuration(350); // 350ms = vizibil dar nu deranjant
        animator.setInterpolator(new DecelerateInterpolator()); // Ușurare la final
        // La fiecare frame al animației, aplicăm culoarea interpolată pe card
        animator.addUpdateListener(a ->
                card.setCardBackgroundColor((int) a.getAnimatedValue()));
        animator.start();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creează un ArrayAdapter standard pentru Spinner cu stilul implicit Android.
     * simple_spinner_item = aspect rând normal
     * simple_spinner_dropdown_item = aspect rând în lista expandată
     *
     * @param names Array-ul de texte afișate în dropdown
     * @return Adapter configurat, gata de atașat la un Spinner
     */
    private ArrayAdapter<String> makeAdapter(String[] names) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    /**
     * Caută un cod de acțiune în array-ul de coduri și returnează indexul său.
     * Folosit pentru a seta poziția corectă în Spinner la restaurarea setărilor.
     * Returnează 0 (primul element) dacă codul nu e găsit — fallback sigur.
     *
     * @param code  Codul căutat (ex: "ACTION_NEXT")
     * @param codes Array-ul în care căutăm
     * @return Indexul codului în array, sau 0 dacă nu există
     */
    private int indexOf(String code, String[] codes) {
        for (int i = 0; i < codes.length; i++)
            if (codes[i].equals(code)) return i;
        return 0; // Fallback la primul element dacă codul nu mai există
    }

    /**
     * Creează un OnItemSelectedListener reutilizabil pentru Spinner-uri.
     * Când utilizatorul selectează o opțiune, salvează codul corespunzător
     * în SharedPreferences (GestureService va prelua schimbarea automat).
     *
     * @param prefKey Cheia din SharedPreferences unde se salvează selecția
     * @param codes   Array-ul de coduri corespunzător pozițiilor din Spinner
     * @param editor  Editorul SharedPreferences pentru salvare
     * @return Listener configurat, gata de atașat la un Spinner
     */
    private AdapterView.OnItemSelectedListener makeListener(
            String prefKey, String[] codes, SharedPreferences.Editor editor) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                // codes[position] mapează indexul selectat la codul intern
                editor.putString(prefKey, codes[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
    }

    /**
     * Pornește GestureService ca Foreground Service.
     * startForegroundService() e obligatoriu pe Android 8+ pentru servicii
     * care vor apela startForeground() — Android le oferă 5 secunde să o facă.
     */
    private void startGestureService() {
        Intent i = new Intent(this, GestureService.class);
        ContextCompat.startForegroundService(this, i);
    }
}
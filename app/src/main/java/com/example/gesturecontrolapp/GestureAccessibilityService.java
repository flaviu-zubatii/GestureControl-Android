package com.example.gesturecontrolapp;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.content.ContextCompat;

/**
 * GestureAccessibilityService — Componenta pentru acțiuni globale de sistem.
 *
 * AccessibilityService are privilegii speciale acordate de utilizator explicit
 * în Setări → Accesibilitate. Aceste privilegii permit executarea de acțiuni
 * globale care simulează interacțiuni fizice cu sistemul.
 *
 * UTILIZARE ÎN ACEASTĂ APLICAȚIE:
 * Exclusiv pentru GLOBAL_ACTION_TAKE_SCREENSHOT — echivalentul programatic
 * al apăsării Power + Volume Down pe telefoanele vechi.
 *
 * IMPORTANT: Nu accesăm conținutul ecranului, nu citim text din alte aplicații,
 * nu modificăm setări. Serviciul e pasiv până când primește broadcast intern.
 *
 * Supresia warning-ului de policy e justificată mai jos.
 */
// Suprimăm warning-ul Android Lint "AccessibilityServicePolicy".
// Lint îl afișează pe ORICE clasă care extinde AccessibilityService,
// indiferent de ce face codul. Suprimarea e corectă și documentată deoarece:
// 1. Nu accesăm conținutul ecranului (onAccessibilityEvent e gol)
// 2. Nu modificăm setări fără consimțământ
// 3. Nu trecem peste controale de privacy
// 4. Acțiunea e declanșată EXCLUSIV de gestul explicit al utilizatorului
@SuppressLint({"AccessibilityServicePolicy", "AccessibilityPolicy"})
public class GestureAccessibilityService extends AccessibilityService {

    private static final String TAG = "GestureAccessSvc";

    /**
     * Receiver care ascultă broadcast-ul intern de screenshot.
     * Trimis de GestureService când Back-Tap e configurat pe "Screenshot".
     * Securizat cu setPackage() în GestureService — nu poate fi interceptat
     * de alte aplicații.
     */
    private final BroadcastReceiver screenshotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (GestureService.ACTION_PERFORM_SCREENSHOT.equals(intent.getAction())) {
                Log.d(TAG, "Comandă screenshot primită → execuție.");
                takeScreenshotIfSupported();
            }
        }
    };

    /**
     * onServiceConnected() — Apelat de sistem când serviciul de accesibilitate
     * e pornit și conectat cu succes (după ce utilizatorul l-a activat în setări).
     * Înregistrăm receiver-ul broadcast aici, nu în onCreate(), deoarece
     * avem nevoie ca serviciul să fie complet inițializat înainte.
     */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        IntentFilter filter = new IntentFilter(GestureService.ACTION_PERFORM_SCREENSHOT);
        // RECEIVER_NOT_EXPORTED: broadcast-ul nostru e intern, nu acceptăm
        // comenzi de screenshot de la aplicații externe (securitate)
        ContextCompat.registerReceiver(
                this, screenshotReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        Log.d(TAG, "Accessibility Service conectat. Receiver screenshot înregistrat.");
    }

    /**
     * Execută screenshot-ul global dacă API-ul e disponibil.
     *
     * GLOBAL_ACTION_TAKE_SCREENSHOT a fost adăugat în Android 9 (API 28 / Pie).
     * Pe Android 8 (API 26-27) această constantă nu există și apelul ar da crash.
     * Guard-ul de versiune previne crash-ul — pe API < 28 acțiunea e ignorată silențios.
     *
     * performGlobalAction() e echivalentul programatic al gestului fizic de screenshot.
     * Nu necesită permisiuni suplimentare față de AccesibilityService în sine.
     */
    private void takeScreenshotIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+ (Android 9+): screenshot global disponibil
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            Log.d(TAG, "Screenshot executat cu succes.");
        } else {
            // API 26-27: funcționalitate indisponibilă pe această versiune Android
            Log.w(TAG, "Screenshot nu este suportat pe API < 28 (Android < 9).");
        }
    }

    /**
     * onAccessibilityEvent() — Apelat când se produce un eveniment de accesibilitate
     * (click, scroll, schimbare text etc.) în orice aplicație.
     *
     * NU procesăm niciun eveniment — implementare intenționat goală.
     * Serviciul nostru nu are nevoie să observe ce face utilizatorul,
     * ci doar să execute o acțiune la comandă.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intenționat gol — nu monitorizăm activitatea utilizatorului
    }

    /**
     * onInterrupt() — Apelat când sistemul întrerupe serviciul de accesibilitate
     * (de ex. altă sursă de accesibilitate preia controlul).
     * Nu avem stare de restaurat sau operații de anulat.
     */
    @Override
    public void onInterrupt() {
        // Nu avem operații în curs de întrerupt
    }

    /**
     * onUnbind() — Apelat când serviciul e deconectat (utilizatorul l-a dezactivat
     * din setări sau sistemul îl oprește).
     * Ștergem receiver-ul pentru a evita memory leak-uri.
     *
     * @param intent Intent-ul de deconectare
     * @return super.onUnbind(intent) — valoarea implicită (false = nu refolosim binding)
     */
    @Override
    public boolean onUnbind(Intent intent) {
        try {
            unregisterReceiver(screenshotReceiver);
            Log.d(TAG, "Receiver screenshot golit.");
        } catch (Exception e) {
            // Poate apărea dacă onServiceConnected() nu a rulat complet
            // (serviciul a fost oprit înainte de a fi complet pornit)
            Log.e(TAG, "Eroare la golire receiver: " + e.getMessage());
        }
        return super.onUnbind(intent);
    }
}
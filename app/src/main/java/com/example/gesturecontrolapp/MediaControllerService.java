package com.example.gesturecontrolapp;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.List;

/**
 * MediaControllerService — Puntea dintre gesturi și aplicațiile media.
 *
 * Extinde NotificationListenerService NU pentru a citi notificări,
 * ci pentru că această permisiune specială acordă și accesul la
 * MediaSessionManager.getActiveSessions() — API-ul care permite
 * controlul nativ al oricărei aplicații media active (Spotify, YouTube etc.)
 * fără integrare specifică cu fiecare aplicație în parte.
 *
 * FLUX:
 * GestureService detectează gest → apelează metodele statice de aici →
 * getActiveController() găsește sesiunea activă → trimite comanda nativă
 */
public class MediaControllerService extends NotificationListenerService {

    private static final String TAG = "MediaControllerSvc";

    // ─────────────────────────────────────────────────────────────────────────
    //  CONSTANTE ACȚIUNI
    //  Definite ca constante publice pentru a fi folosite și din GestureService
    //  fără a hardcoda string-uri în două locuri (principiul DRY).
    // ─────────────────────────────────────────────────────────────────────────

    /** Cod pentru acțiunea de Play/Pause — comutare între stările de redare. */
    public static final String ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE";
    /** Cod pentru trecerea la piesa următoare. */
    public static final String ACTION_NEXT       = "ACTION_NEXT";
    /** Cod pentru revenirea la piesa anterioară. */
    public static final String ACTION_PREV       = "ACTION_PREV";

    // ═════════════════════════════════════════════════════════════════════════
    //  COMENZI MEDIA PUBLICE
    //  Toate metodele sunt statice pentru a putea fi apelate din GestureService
    //  fără a instanția MediaControllerService (care e gestionat de sistem).
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Router central pentru comenzile media.
     * Primește un cod de acțiune și îl direcționează la metoda specifică.
     * Folosit de GestureService pentru a trimite comenzi media prin un
     * singur punct de intrare.
     *
     * @param context Contextul aplicației (necesar pentru getSystemService)
     * @param action  Codul acțiunii (ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREV)
     */
    public static void sendMediaAction(Context context, String action) {
        switch (action) {
            case ACTION_PLAY_PAUSE:
                sendPlayPauseCommand(context);
                break;
            case ACTION_NEXT:
                sendNextTrackCommand(context);
                break;
            case ACTION_PREV:
                sendPreviousTrackCommand(context);
                break;
            default:
                Log.w(TAG, "Acțiune media necunoscută: " + action);
        }
    }

    /**
     * Trimite comanda "piesa următoare" sesiunii media active.
     * skipToNext() este comanda nativă recunoscută de toate playerele conforme
     * cu standardul Android MediaSession.
     *
     * @param context Contextul aplicației
     */
    public static void sendNextTrackCommand(Context context) {
        MediaController c = getActiveController(context);
        if (c != null) {
            Log.d(TAG, "Next → " + c.getPackageName());
            c.getTransportControls().skipToNext();
        }
    }

    /**
     * Trimite comanda "piesa anterioară" sesiunii media active.
     *
     * @param context Contextul aplicației
     */
    public static void sendPreviousTrackCommand(Context context) {
        MediaController c = getActiveController(context);
        if (c != null) {
            Log.d(TAG, "Previous → " + c.getPackageName());
            c.getTransportControls().skipToPrevious();
        }
    }

    /**
     * Comută între Play și Pause în funcție de starea curentă a playerului.
     * Citim PlaybackState pentru a ști dacă muzica rulează sau e în pauză,
     * apoi trimitem comanda opusă stării curente.
     *
     * @param context Contextul aplicației
     */
    public static void sendPlayPauseCommand(Context context) {
        MediaController c = getActiveController(context);
        if (c != null) {
            PlaybackState state = c.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                // Muzica rulează → trimitem Pause
                Log.d(TAG, "Pause → " + c.getPackageName());
                c.getTransportControls().pause();
            } else {
                // Muzica e în pauză sau starea e necunoscută → trimitem Play
                Log.d(TAG, "Play → " + c.getPackageName());
                c.getTransportControls().play();
            }
        }
    }

    /**
     * Oprește complet redarea (spre deosebire de pause, stop eliberează resursele audio).
     * Folosit pentru acțiunea "Oprește muzica" din Flip to Shush.
     *
     * @param context Contextul aplicației
     */
    public static void sendStopCommand(Context context) {
        MediaController c = getActiveController(context);
        if (c != null) {
            Log.d(TAG, "Stop → " + c.getPackageName());
            c.getTransportControls().stop();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  STATUS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Verifică dacă există o sesiune media activă în sistem.
     * Folosit de GestureService pentru a determina culoarea status bar-ului
     * (verde = media conectat, galben = nicio sesiune activă).
     *
     * @param context Contextul aplicației
     * @return true dacă există cel puțin un player media activ
     */
    public static boolean isMediaConnected(Context context) {
        return getActiveController(context) != null;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HELPER PRIVAT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Obține controller-ul sesiunii media active.
     *
     * MECANISM:
     * 1. MediaSessionManager gestionează toate sesiunile media din sistem.
     * 2. getActiveSessions() necesită un ComponentName care identifică un
     *    NotificationListenerService autorizat — acesta suntem noi.
     * 3. Returnăm prima sesiune din listă (cea mai recent activă).
     *
     * ERORI POSIBILE:
     * - SecurityException: utilizatorul nu a acordat Notification Access în setări.
     *   În acest caz, banner-ul din MainActivity îl va îndruma să îl acorde.
     *
     * @param context Contextul aplicației
     * @return Primul MediaController activ, sau null dacă nu există sesiuni/permisiuni
     */
    private static MediaController getActiveController(Context context) {
        MediaSessionManager msm =
                (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return null;

        try {
            // ComponentName identifică serviciul nostru ca sursă autorizată
            ComponentName cn = new ComponentName(context, MediaControllerService.class);
            List<MediaController> controllers = msm.getActiveSessions(cn);

            // getActiveSessions() returnează lista goală, nu null —
            // verificăm doar isEmpty() (fără null check redundant)
            if (!controllers.isEmpty()) {
                return controllers.get(0); // Prima sesiune = cea mai recentă
            }
        } catch (SecurityException e) {
            // Permisiunea Notification Access nu e acordată
            Log.e(TAG, "Lipsește permisiunea Notification Access: " + e.getMessage());
        }
        return null;
    }
}
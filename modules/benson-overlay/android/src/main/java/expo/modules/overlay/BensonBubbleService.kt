package expo.modules.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal floating bubble — a plain circle, no theming, meant as a functional placeholder
 * until the app's own design system covers it. Tap activates the mic (fires onBubbleTapped);
 * drag moves it anywhere on screen. Deliberately has zero dependency on any in-app UI
 * component (BensonSeal/BensonMainScreen/IdleCard) — this lives entirely outside the RN view
 * tree, drawn directly via WindowManager like any system overlay (as Android's own
 * accessibility/chat-head bubbles do).
 *
 * ROUND_BUBBLE_STATE_DESYNC_FIX_1 — the expanded status surface's SHOW/HIDE decision and its
 * auto-dismiss timer are now owned entirely natively (see updateStatus/scheduleDismiss/
 * dismissNow below). JS (app/index.tsx's existing Treapta 2/C8 + E3 state machine, unchanged)
 * still decides WHAT to show and calls updateStatus() to request it — same as before
 * (ROUND_BUBBLE_GEMINI_BEHAVIOR_1) — but native no longer trusts a JS setTimeout to ever hide a
 * stale overlay: every call arms (or re-arms) a native Handler.postDelayed dismiss, which fires
 * regardless of whether JS itself is still alive to ask for it later. `turnId` (Date.now() at
 * the JS call site) lets native detect and drop an out-of-order/stale delivery instead of
 * letting an old THINKING update clobber a newer DONE one (or vice versa).
 */
class BensonBubbleService : Service() {
  private var windowManager: WindowManager? = null
  private var bubbleView: View? = null
  private var params: WindowManager.LayoutParams? = null

  // E3-2 — the bubble is now transparent; these three counter-rotating dots ARE the visible cue.
  private var bubbleDots: BubbleDotsView? = null
  private var bubbleMotion: String = "static" // remembered so a re-created bubble keeps its state

  private var wakeRingView: FrameLayout? = null
  private var wakeOuterRing: RingArcView? = null
  private var wakeInnerRing: RingArcView? = null

  // ROUND_BENSON_BUBBLE_IME_1 — behaviour state.
  //  MANUAL_POSITION       : lp.x/lp.y set by the user's last drag, persisted; the resting place.
  //  TEMPORARY_IME_POSITION: while the software keyboard is up the bubble hops to a safe upper
  //                          spot WITHOUT touching the saved MANUAL_POSITION; it returns on hide.
  private var bubbleSizePx: Int = 0
  private var imeActive: Boolean = false

  // E2-2 (2026-09-07, product-owner-directed) — a narrow written band shown next to the bubble
  // while BENSON is operating over another app: the transcribed text of the user's last utterance
  // + a one-word state ("ascult" / "am înțeles" / "execut" / "gata"). Sits just above the bubble
  // (bottom-centre), WRAP_CONTENT width capped so it never covers the left of the app underneath.
  private var statusView: LinearLayout? = null
  private var statusStateText: TextView? = null
  private var statusTranscriptText: TextView? = null
  private var statusParams: WindowManager.LayoutParams? = null
  // URGENT_REPAIR_AND_ADVANCE_1 — real-RMS mic level bars, bottom edge of the status card.
  private var micLevelBar: MicLevelBarView? = null

  // ROUND_BUBBLE_STATE_DESYNC_FIX_1 — native-owned auto-dismiss + stale-delivery guard.
  private val dismissHandler = Handler(Looper.getMainLooper())
  private var dismissRunnable: Runnable? = null
  private var lastAppliedTurnId: Long = 0L

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    instance = this // URGENT_REPAIR_AND_ADVANCE_1 — direct-call target for high-frequency setMicLevel
    when (intent?.action) {
      ACTION_SHOW_WAKE_RING -> showWakeRing()
      ACTION_HIDE_WAKE_RING -> hideWakeRing()
      ACTION_UPDATE_STATUS -> updateStatus(
        intent.getStringExtra(EXTRA_STATE) ?: "",
        intent.getStringExtra(EXTRA_TRANSCRIPT) ?: "",
        intent.getBooleanExtra(EXTRA_VISIBLE, false),
        intent.getBooleanExtra(EXTRA_TERMINAL, false),
        intent.getLongExtra(EXTRA_TURN_ID, 0L),
        intent.getLongExtra(EXTRA_DISMISS_DELAY_MS, 0L),
      )
      ACTION_BUBBLE_MOTION -> {
        bubbleMotion = intent.getStringExtra(EXTRA_MOTION) ?: "static"
        bubbleDots?.applyMotion(bubbleMotion)
      }
      ACTION_IME_VISIBILITY -> applyImeVisibility(intent.getBooleanExtra(EXTRA_IME_VISIBLE, false))
      ACTION_FOREGROUND_PACKAGE_CHANGED -> applyForegroundState(intent.getBooleanExtra(EXTRA_IS_SELF_FOREGROUND, false))
      // ROUND_BUBBLE_VISIBILITY_POLICY_1 — no-action start (JS's showBubble()/startBackgroundService)
      // used to unconditionally create the persistent idle bubble here. That is now FORBIDDEN: the
      // overlay only ever exists while an active state calls updateStatus() with visible=true (see
      // below) — a plain start just keeps this service's process alive so it CAN react later.
      else -> {}
    }
    return START_STICKY
  }

  // ROUND_BUBBLE_VISIBILITY_POLICY_1 — CASE 1 (self-app suppression). Native, event-driven
  // (BensonAccessibilityService's existing TYPE_WINDOW_STATE_CHANGED stream — see that file's
  // pushForegroundPackageToBubble()), independent of whether JS is alive. The instant BENSON's
  // own Activity becomes foreground, any active overlay is force-torn-down — no idle bubble is
  // ever restored afterward, per this round's explicit correction of the previous one.
  @Volatile private var isSelfForeground = false
  private fun applyForegroundState(selfForeground: Boolean) {
    Log.i("BENSON_AUDIO", "OVERLAY_POLICY_EVAL isSelfForeground=$selfForeground hadActiveOverlay=${statusView != null}")
    isSelfForeground = selfForeground
    if (selfForeground) {
      hideWakeRing()
      if (statusView != null || bubbleView != null) {
        Log.i("BENSON_AUDIO", "OVERLAY_HIDE_SELF_APP")
        dismissNow("self_app_foreground")
      }
    }
  }

  override fun onDestroy() {
    instance = null
    cancelDismissTimer()
    removeBubble()
    hideWakeRing()
    removeStatus()
    super.onDestroy()
  }

  private fun addBubble() {
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    val density = resources.displayMetrics.density

    // ROUND_BUBBLE_GEMINI_BEHAVIOR_1 — was 64dp (E3-2), reported too large on real device; sized
    // down to roughly the CENTRAL circle of the wake-ring listening animation (the inner
    // RingArcView below is 110dp; the idle bubble is a resting indicator, not another ring, so it
    // sits meaningfully smaller than even that inner ring) rather than the 180dp outer arcs. Same
    // dots/colors/identity (BubbleDotsView, stroke color) — only the diameter changed. Revert: 64.
    val size = (IDLE_BUBBLE_SIZE_DP * density).toInt()
    val dots = BubbleDotsView(this)
    bubbleDots = dots
    val view = FrameLayout(this).apply {
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke((1.5f * density).toInt(), Color.parseColor("#59D4AF37")) // ~35% gold — see-through
      }
      addView(dots, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    dots.applyMotion(bubbleMotion)

    val overlayType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // Bottom, not top (product-owner-directed 2026-07-17): "Benson stays a bubble at the bottom"
    // while another app (WhatsApp, Waze, anything BENSON operates) is in the foreground — this is
    // the primary way the user sees BENSON is still present/reachable during a handoff, replacing
    // reliance on real Android PiP (multi-window), which ColorOS's own flexible-window layer kept
    // reinterpreting unpredictably. A plain WindowManager overlay has no such OS-level ambiguity.
    bubbleSizePx = size

    val lp = WindowManager.LayoutParams(
      size, size, overlayType,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
      // Restore the user's last manual position; fall back to the historic default (100dp above
      // the bottom edge). Always clamped into the current safe bounds (screen may have rotated /
      // the saved value may be stale).
      val saved = loadManualPosition()
      x = saved?.first ?: 0
      y = saved?.second ?: (100 * density).toInt()
    }
    clampToSafeBounds(lp)

    var downX = 0f; var downY = 0f
    var startX = 0; var startY = 0
    var moved = false

    view.setOnTouchListener { v, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          downX = event.rawX; downY = event.rawY
          startX = lp.x; startY = lp.y
          moved = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - downX).toInt()
          val dy = (event.rawY - downY).toInt()
          if (!moved && (abs(dx) > 12 || abs(dy) > 12)) {
            moved = true
            Log.i("BENSON_AUDIO", "BUBBLE_DRAG_START surface=idle x=${lp.x} y=${lp.y}")
          }
          lp.x = startX + dx
          // y is a bottom-edge offset (Gravity.BOTTOM): dragging DOWN (dy>0) DECREASES it.
          lp.y = startY - dy
          clampToSafeBounds(lp)
          windowManager?.updateViewLayout(v, lp)
          true
        }
        MotionEvent.ACTION_UP -> {
          if (!moved) {
            onBubbleTapped?.invoke()
          } else {
            // An explicit drag ALWAYS updates the saved MANUAL_POSITION — even while the keyboard
            // is up (the user is deliberately overriding). Auto-repositioning never persists.
            persistManualPosition(lp.x, lp.y)
            Log.i("BENSON_AUDIO", "BUBBLE_DRAG_END surface=idle x=${lp.x} y=${lp.y}")
            Log.i("BENSON_AUDIO", "BUBBLE_POSITION_SAVED x=${lp.x} y=${lp.y} imeActive=$imeActive")
          }
          true
        }
        else -> false
      }
    }

    params = lp
    bubbleView = view
    windowManager?.addView(view, lp)

    // If the keyboard came up before the bubble existed, honour it immediately.
    if (imeActive) applyImeVisibility(true)
  }

  // ── ROUND_BENSON_BUBBLE_IME_1 — IME-aware auto reposition ─────────────────────────────────────
  private fun applyImeVisibility(visible: Boolean) {
    imeActive = visible
    val v = bubbleView ?: return
    val lp = params ?: return
    if (visible) {
      Log.i("BENSON_AUDIO", "BUBBLE_IME_VISIBLE currentY=${lp.y}")
      val safeTop = safeTopYValue()
      // Only hop if the bubble would actually be near / behind the keyboard (lower part of the
      // screen). If the user already parked it up top, leave it.
      if (lp.y < safeTop) {
        lp.y = safeTop
        clampToSafeBounds(lp)
        try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
        Log.i("BENSON_AUDIO", "BUBBLE_MOVE_TO_SAFE_TOP y=${lp.y}")
      }
    } else {
      Log.i("BENSON_AUDIO", "BUBBLE_IME_HIDDEN")
      val manual = loadManualPosition()
      lp.x = manual?.first ?: lp.x
      lp.y = manual?.second ?: (100 * resources.displayMetrics.density).toInt()
      clampToSafeBounds(lp)
      try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
      Log.i("BENSON_AUDIO", "BUBBLE_POSITION_RESTORED x=${lp.x} y=${lp.y}")
    }
  }

  private data class Insets(val top: Int, val bottom: Int)
  private fun systemBarInsets(): Insets {
    val density = resources.displayMetrics.density
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
        val ins = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        return Insets(ins.top, ins.bottom)
      } catch (_: Exception) { /* fall through */ }
    }
    return Insets((24 * density).toInt(), (48 * density).toInt())
  }

  // Keep the bubble fully on-screen, clear of the status bar (top) and the navigation area
  // (bottom). x = offset from horizontal centre; y = offset of the bubble's bottom from the
  // screen's bottom edge (Gravity.BOTTOM|CENTER_HORIZONTAL). No fixed device coordinates.
  private fun clampToSafeBounds(lp: WindowManager.LayoutParams) {
    val dm = resources.displayMetrics
    val margin = (6 * dm.density).toInt()
    val size = if (bubbleSizePx > 0) bubbleSizePx else (IDLE_BUBBLE_SIZE_DP * dm.density).toInt()
    val ins = systemBarInsets()

    val maxX = max(0, (dm.widthPixels - size) / 2 - margin)
    lp.x = min(maxX, max(-maxX, lp.x))

    val minY = ins.bottom + margin
    val maxY = max(minY, dm.heightPixels - ins.top - size - margin)
    lp.y = min(maxY, max(minY, lp.y))
  }

  // The highest safe resting spot — used as the temporary IME position.
  private fun safeTopYValue(): Int {
    val dm = resources.displayMetrics
    val margin = (6 * dm.density).toInt()
    val size = if (bubbleSizePx > 0) bubbleSizePx else (IDLE_BUBBLE_SIZE_DP * dm.density).toInt()
    val ins = systemBarInsets()
    return max(ins.bottom + margin, dm.heightPixels - ins.top - size - margin)
  }

  private fun bubblePrefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private fun loadManualPosition(): Pair<Int, Int>? {
    val p = bubblePrefs()
    if (!p.getBoolean(KEY_HAS_POS, false)) return null
    return p.getInt(KEY_X, 0) to p.getInt(KEY_Y, 0)
  }
  private fun persistManualPosition(x: Int, y: Int) {
    bubblePrefs().edit().putBoolean(KEY_HAS_POS, true).putInt(KEY_X, x).putInt(KEY_Y, y).apply()
  }

  // ROUND_BUBBLE_GEMINI_BEHAVIOR_1 — the written/status card gets its own persisted position,
  // independent of the idle bubble's. Defaults to anchored just above wherever the idle bubble
  // currently rests (so on first appearance they move as one logical unit); once the user drags
  // the card, its own position is remembered from then on.
  private fun loadStatusPosition(): Pair<Int, Int>? {
    val p = bubblePrefs()
    if (!p.getBoolean(KEY_STATUS_HAS_POS, false)) return null
    return p.getInt(KEY_STATUS_X, 0) to p.getInt(KEY_STATUS_Y, 0)
  }
  private fun persistStatusPosition(x: Int, y: Int) {
    bubblePrefs().edit().putBoolean(KEY_STATUS_HAS_POS, true).putInt(KEY_STATUS_X, x).putInt(KEY_STATUS_Y, y).apply()
  }

  private fun removeBubble() {
    bubbleDots?.stop()
    bubbleDots = null
    bubbleView?.let { windowManager?.removeView(it) }
    bubbleView = null
    windowManager = null
  }

  // ── ROUND_BUBBLE_STATE_DESYNC_FIX_1 / ROUND_ASSISTANT_SESSION_UX_FIX_1 — native-owned status
  // lifecycle ──────────────────────────────────────────────────────────────────────────────────
  // JS (app/index.tsx's pushBubbleBand, unchanged) decides WHAT to show; this function is the
  // ONE place that decides whether to actually apply it (stale-turn guard) and OWNS how long it
  // stays up (native Handler.postDelayed — survives JS suspension, unlike the JS setTimeout this
  // replaces as the source of truth for hiding). `terminal` = JS has determined the result is
  // truly done being read/spoken (session-aware — see app/index.tsx's scheduleResultDismiss():
  // this is NOT sent the instant state becomes DONE/ERROR anymore, only once TTS for that result
  // has actually finished, or immediately if no TTS was needed for it) — `dismissDelayMs` is the
  // exact readable dwell JS computed for that case (~3s after TTS, ~4-5s text-only), not a
  // hardcoded native constant, so this stays session-aware rather than "another fixed delay".
  // Non-terminal (LISTENING/THINKING/EXECUTING/CONFIRMING/SPEAKING) arms the long safety-net
  // dismiss instead, so a truly abandoned overlay (JS died mid-turn) still cannot survive
  // indefinitely, without ever fighting a legitimately long CONFIRMING wait.
  private fun updateStatus(state: String, transcript: String, visible: Boolean, terminal: Boolean, turnId: Long, dismissDelayMs: Long) {
    Log.i("BENSON_AUDIO", "UI_STATE_NATIVE state=\"$state\" terminal=$terminal turnId=$turnId visible=$visible dismissDelayMs=$dismissDelayMs")

    if (turnId < lastAppliedTurnId) {
      Log.i("BENSON_AUDIO", "UI_STATE_DESYNC reason=stale_turn incoming=$turnId lastApplied=$lastAppliedTurnId")
      return
    }
    lastAppliedTurnId = turnId
    Log.i("BENSON_AUDIO", "UI_STATE_SYNC turnId=$turnId")

    if (!visible || (state.isBlank() && transcript.isBlank())) {
      dismissNow("explicit_hide")
      return
    }

    // ROUND_BUBBLE_VISIBILITY_POLICY_1 — CASE 1/self-app suppression, enforced here too (not only
    // in applyForegroundState()) so a request that races the foreground-change broadcast can
    // never slip a visible overlay onto BENSON's own screen even for one frame.
    if (isSelfForeground) {
      Log.i("BENSON_AUDIO", "OVERLAY_HIDE_SELF_APP")
      dismissNow("self_app_foreground")
      return
    }

    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    windowManager = windowManager ?: wm
    val density = resources.displayMetrics.density

    // ROUND_BUBBLE_VISIBILITY_POLICY_1 — the small dots indicator is no longer a persistent idle
    // element (that behavior is now FORBIDDEN — see the round's own correction). It exists ONLY
    // as part of this active overlay: created together with the status card here, torn down
    // together with it in dismissNow(). Never shown on its own, never restored after dismiss.
    if (bubbleView == null) {
      addBubble()
    }
    Log.i("BENSON_AUDIO", "OVERLAY_SHOW_ACTIVE state=\"$state\"")

    val wasNew = statusView == null
    if (statusView == null) {
      // E3 (2026-09-07, product-owner-directed) — lizibil din mașină, fără ochelari: font ≥18sp
      // bold, lățime max 85% din ecran, umbră puternică ÎN PLUS de fundalul semi-opac (contrast pe
      // orice fundal), etichetă de stare în MAJUSCULE gold, transcriere în alb.
      val shadowRadius = 5f * density
      val shadowDy = 2f * density
      val stateTv = TextView(this).apply {
        setTextColor(Color.parseColor("#F2C94C")) // gold ceva mai deschis pentru contrast
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setShadowLayer(shadowRadius, 0f, shadowDy, Color.parseColor("#CC000000"))
      }
      val transcriptTv = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        // Lățime max 85% din ecran (minus padding-ul containerului) — banda rămâne o fâșie, nu
        // acoperă tot ecranul aplicației de dedesubt.
        maxWidth = (resources.displayMetrics.widthPixels * 0.85f).toInt() - (2 * 16 * density).toInt()
        includeFontPadding = false
        setPadding(0, (4 * density).toInt(), 0, 0)
        setShadowLayer(shadowRadius, 0f, shadowDy, Color.parseColor("#CC000000"))
      }
      val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padH = (16 * density).toInt()
        val padV = (12 * density).toInt()
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
          shape = GradientDrawable.RECTANGLE
          cornerRadius = 20 * density
          setColor(Color.parseColor("#F51E2B38")) // ~96% opac
          setStroke((1.5f * density).toInt(), Color.parseColor("#99D4AF37"))
        }
        addView(stateTv)
        addView(transcriptTv)
        addView(MicLevelBarView(this@BensonBubbleService).apply {
          visibility = View.GONE
          layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (26 * density).toInt()).apply {
            topMargin = (6 * density).toInt()
          }
          micLevelBar = this
        })
        // Photo/video capture button — plain emoji glyph (no icon-drawable dependency needed in
        // this native module), its own click listener, independent of the card's drag detection.
        addView(TextView(this@BensonBubbleService).apply {
          text = "📷"
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
          isClickable = true
          setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), 0)
          contentDescription = "Fă o poză pentru Benson"
          setOnClickListener { onBubbleCameraTapped?.invoke() }
          layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6 * density).toInt()
            gravity = Gravity.END
          }
        })
      }

      val overlayType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

      // ROUND_BUBBLE_GEMINI_BEHAVIOR_1 — draggable (was FLAG_NOT_TOUCHABLE, the exact reason it
      // could never be dragged: that flag makes every touch pass straight through to whatever is
      // behind it). Default position anchors just above the idle bubble's CURRENT spot — "move
      // together as one logical unit" on first appearance — unless the user has already dragged
      // the card before, in which case its own saved position wins.
      val bubbleLp = params
      val anchoredX = bubbleLp?.x ?: 0
      val anchoredY = (bubbleLp?.y ?: (100 * density).toInt()) + bubbleSizePx + (10 * density).toInt()
      val savedStatusPos = loadStatusPosition()

      // ROUND_ASSISTANT_SESSION_UX_FIX_1 — keep-screen-awake for an active session while another
      // app is foreground (BENSON's own in-app expo-keep-awake mechanism, bumpSessionKeepAwake()
      // in app/index.tsx, has zero effect here — it operates on BENSON's OWN Activity window,
      // which isn't the one on screen during an overlay session). FLAG_KEEP_SCREEN_ON is Android's
      // own per-window "keep the device screen on while I'm visible" contract — self-bounded to
      // exactly this window's lifetime, so it needs no separate acquire/release bookkeeping and no
      // WakeLock object: the flag's effect ends the instant this window is removed (dismissNow(),
      // called on every terminal path already — DONE dwell-complete, self-app-foreground, explicit
      // hide, and the safety-net timeout all funnel through it), which is exactly the "bounded,
      // state-owned, released on every terminal path" requirement, automatically.
      val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        x = savedStatusPos?.first ?: anchoredX
        y = savedStatusPos?.second ?: anchoredY
      }
      Log.i("BENSON_AUDIO", "SESSION_ACTIVE_START")
      Log.i("BENSON_AUDIO", "SCREEN_AWAKE_ACQUIRE mechanism=FLAG_KEEP_SCREEN_ON")

      var downX = 0f; var downY = 0f
      var startX = 0; var startY = 0
      var moved = false
      container.setOnTouchListener { v, event ->
        when (event.action) {
          MotionEvent.ACTION_DOWN -> {
            downX = event.rawX; downY = event.rawY
            startX = lp.x; startY = lp.y
            moved = false
            true
          }
          MotionEvent.ACTION_MOVE -> {
            val dx = (event.rawX - downX).toInt()
            val dy = (event.rawY - downY).toInt()
            if (!moved && (abs(dx) > 12 || abs(dy) > 12)) {
              moved = true
              Log.i("BENSON_AUDIO", "BUBBLE_DRAG_START surface=status x=${lp.x} y=${lp.y}")
            }
            if (moved) {
              lp.x = startX + dx
              lp.y = startY - dy
              try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
            }
            true
          }
          MotionEvent.ACTION_UP -> {
            // A tap on the written card is deliberately a no-op — dragging it must never
            // accidentally fire a BENSON action (there is no onBubbleTapped-equivalent here).
            if (moved) {
              persistStatusPosition(lp.x, lp.y)
              Log.i("BENSON_AUDIO", "BUBBLE_DRAG_END surface=status x=${lp.x} y=${lp.y}")
            }
            true
          }
          else -> false
        }
      }

      try {
        wm.addView(container, lp)
        statusView = container
        statusStateText = stateTv
        statusTranscriptText = transcriptTv
        statusParams = lp
      } catch (_: Exception) {
        // No "draw over other apps" permission, or the token was revoked — same silent no-op as
        // every other overlay call in this module. Never leave the dots indicator up on its own —
        // that would be exactly the forbidden "idle bubble with no active card" state.
        dismissNow("status_add_failed")
        return
      }
    }
    Log.i("BENSON_AUDIO", if (wasNew) "BUBBLE_EXPANDED_SHOW" else "BUBBLE_STATUS_SHOW")

    statusStateText?.apply {
      text = state
      visibility = if (state.isBlank()) View.GONE else View.VISIBLE
    }
    statusTranscriptText?.apply {
      text = transcript
      visibility = if (transcript.isBlank()) View.GONE else View.VISIBLE
    }
    Log.i("BENSON_AUDIO", "BUBBLE_TEXT_SHOW state=${state.isNotBlank()} transcript=${transcript.isNotBlank()}")

    // C/D — native owns the dismiss timing from here. Any call (including a repeated one for the
    // SAME phase) re-arms the timer fresh, which is also exactly what makes "new speech cancels
    // dismiss" work: a fresh LISTENING call cancels a pending terminal dismiss and re-arms the
    // long non-terminal safety-net instead.
    // ROUND_ASSISTANT_SESSION_UX_FIX_1 — `terminal` is now sent by JS ONLY once a result is truly
    // done being read/spoken (see scheduleResultDismiss() in app/index.tsx), carrying the exact
    // session-aware dwell it computed (dismissDelayMs) rather than a hardcoded native constant.
    // A `terminal=true` call with no positive dismissDelayMs falls back to TERMINAL_DISMISS_MS
    // for backward compatibility (e.g. the explicit_hide/self_app_foreground paths, which don't
    // go through this branch at all — they return earlier — but a defensive default costs nothing).
    if (terminal) {
      Log.i("BENSON_AUDIO", "RESULT_DISMISS_SCHEDULE dwellMs=${if (dismissDelayMs > 0) dismissDelayMs else TERMINAL_DISMISS_MS}")
      scheduleDismiss(if (dismissDelayMs > 0) dismissDelayMs else TERMINAL_DISMISS_MS, "terminal")
    } else {
      scheduleDismiss(NON_TERMINAL_SAFETY_NET_MS, "stale_safety_net")
    }
  }

  private fun scheduleDismiss(delayMs: Long, reason: String) {
    cancelDismissTimer()
    val r = Runnable {
      dismissRunnable = null
      Log.i("BENSON_AUDIO", "BUBBLE_AUTO_DISMISS_NATIVE_EXECUTE reason=$reason")
      if (reason == "terminal") Log.i("BENSON_AUDIO", "RESULT_DISMISS_EXECUTE")
      dismissNow(reason)
    }
    dismissRunnable = r
    dismissHandler.postDelayed(r, delayMs)
    Log.i("BENSON_AUDIO", "BUBBLE_AUTO_DISMISS_NATIVE_SCHEDULE delayMs=$delayMs reason=$reason")
  }

  private fun cancelDismissTimer() {
    dismissRunnable?.let {
      dismissHandler.removeCallbacks(it)
      Log.i("BENSON_AUDIO", "BUBBLE_AUTO_DISMISS_CANCEL")
      Log.i("BENSON_AUDIO", "RESULT_DISMISS_CANCEL")
    }
    dismissRunnable = null
  }

  // The single native cleanup path — called by the auto-dismiss timer, by an explicit
  // visible=false request from JS, and by onDestroy(). Always: clear text first (so no stale
  // frame is ever possible), tear down the card AND the dots indicator together (ONE logical
  // overlay unit), hide the wake ring. ROUND_BUBBLE_VISIBILITY_POLICY_1 — no idle bubble is ever
  // restored afterward; ROUND_BUBBLE_GEMINI_BEHAVIOR_1's "restore the idle bubble" behavior is
  // explicitly forbidden by this round's correction. Idempotent — safe to call when nothing is
  // showing (e.g. two dismiss paths racing).
  private fun dismissNow(reason: String) {
    cancelDismissTimer()
    statusStateText?.text = ""
    statusTranscriptText?.text = ""
    val hadOverlay = statusView != null || bubbleView != null
    if (statusView != null) Log.i("BENSON_AUDIO", "BUBBLE_STATUS_CLEAR_BODY reason=$reason")
    removeStatus()
    removeBubble()
    hideWakeRing()
    if (hadOverlay) {
      // ROUND_ASSISTANT_SESSION_UX_FIX_1 — the FLAG_KEEP_SCREEN_ON window was just removed above
      // (removeStatus()) — its screen-awake effect already ended; these logs just make that
      // explicit/greppable, they don't perform a separate release step.
      Log.i("BENSON_AUDIO", "SCREEN_AWAKE_RELEASE reason=$reason")
      Log.i("BENSON_AUDIO", "SESSION_ACTIVE_END reason=$reason")
      Log.i("BENSON_AUDIO", "BUBBLE_STATUS_HIDE reason=$reason")
      Log.i("BENSON_AUDIO", "BUBBLE_EXPANDED_HIDE reason=$reason")
      val tag = when (reason) {
        "terminal" -> "OVERLAY_HIDE_DONE"
        "stale_safety_net" -> "OVERLAY_HIDE_TIMEOUT"
        "self_app_foreground" -> null // already logged OVERLAY_HIDE_SELF_APP at the call site
        else -> "OVERLAY_HIDE_IDLE"
      }
      if (tag != null) Log.i("BENSON_AUDIO", "$tag reason=$reason")
    }
  }

  private fun removeStatus() {
    statusView?.let { v ->
      // Own WindowManager handle — removeBubble() may have already nulled `windowManager`, and
      // onDestroy() calls removeBubble() before this.
      try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }
    statusView = null
    statusStateText = null
    statusTranscriptText = null
    statusParams = null
    micLevelBar = null
  }

  // URGENT_REPAIR_AND_ADVANCE_1 — pushed from app/index.tsx's real addVolumeListener RMS while a
  // JS STT capture is active; no-op (silently) if the status card isn't up, so a stray call before
  // OVERLAY_SHOW_ACTIVE can never crash or resurrect a dismissed overlay.
  fun setMicLevel(level: Float, active: Boolean) {
    // Called from the JS bridge thread (not the UI thread) — confirmed live: mutating the View
    // directly here threw "Accessibility content change on non-UI thread" repeatedly. dismissHandler
    // is already a main-looper Handler used elsewhere in this class for exactly this reason.
    dismissHandler.post {
      val bar = micLevelBar ?: return@post
      bar.visibility = if (active) View.VISIBLE else View.GONE
      if (active) bar.setLevel(level)
    }
  }

  // Wake-word reveal — the seal appears centered, semi-transparent, over whatever app is in
  // front, only while "Benson" was just heard and the real command is being captured. Drawn
  // natively (two counter-rotating RingArcViews) rather than hosting a second React Native
  // surface — far simpler and avoids a second bridge/root-view lifecycle to manage.
  private fun showWakeRing() {
    if (wakeRingView != null) return
    // ROUND_BUBBLE_VISIBILITY_POLICY_1 — "If BENSON is already foreground: use the in-app
    // listening UI only. Do NOT create floating overlay."
    if (isSelfForeground) {
      Log.i("BENSON_AUDIO", "OVERLAY_HIDE_SELF_APP reason=wake_ring_suppressed")
      return
    }
    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    windowManager = windowManager ?: wm

    val density = resources.displayMetrics.density
    val outerSize = (180 * density).toInt()
    val innerSize = (110 * density).toInt()

    val container = FrameLayout(this).apply { alpha = 0.88f }
    val outer = RingArcView(this, 6f, thin = false).apply {
      layoutParams = FrameLayout.LayoutParams(outerSize, outerSize, Gravity.CENTER)
    }
    val inner = RingArcView(this, 1.5f, thin = true).apply {
      layoutParams = FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER)
    }
    container.addView(outer)
    container.addView(inner)

    val overlayType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    val lp = WindowManager.LayoutParams(
      outerSize, outerSize, overlayType,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
      PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.CENTER }

    wm.addView(container, lp)
    wakeRingView = container
    wakeOuterRing = outer
    wakeInnerRing = inner
    outer.startSpin(3500, clockwise = true)
    inner.startSpin(3500, clockwise = false)
  }

  private fun hideWakeRing() {
    wakeOuterRing?.stopSpin()
    wakeInnerRing?.stopSpin()
    wakeRingView?.let { windowManager?.removeView(it) }
    wakeRingView = null
    wakeOuterRing = null
    wakeInnerRing = null
  }

  companion object {
    // URGENT_REPAIR_AND_ADVANCE_1 — direct-call target so the module doesn't build+dispatch an
    // Intent for every real-RMS tick (~10/sec while listening).
    @Volatile var instance: BensonBubbleService? = null
    var onBubbleTapped: (() -> Unit)? = null
    // Photo/video capture (product-owner-directed 2026-09-18) — a small camera icon on the
    // status card (the "written band"/"bula de text"), a plain clickable child View with its own
    // OnClickListener — deliberately NOT routed through the card's own setOnTouchListener below
    // (that one is drag-only by design, see its "no onBubbleTapped-equivalent here" comment); a
    // child view's click is dispatched before the parent ever sees the touch, so this doesn't
    // touch that drag-detection code at all.
    var onBubbleCameraTapped: (() -> Unit)? = null
    const val ACTION_SHOW_WAKE_RING = "expo.modules.overlay.ACTION_SHOW_WAKE_RING"
    const val ACTION_HIDE_WAKE_RING = "expo.modules.overlay.ACTION_HIDE_WAKE_RING"
    // E2-2
    const val ACTION_UPDATE_STATUS = "expo.modules.overlay.ACTION_UPDATE_STATUS"
    const val EXTRA_STATE = "state"
    const val EXTRA_TRANSCRIPT = "transcript"
    const val EXTRA_VISIBLE = "visible"
    // ROUND_BUBBLE_STATE_DESYNC_FIX_1
    const val EXTRA_TERMINAL = "terminal"
    const val EXTRA_TURN_ID = "turn_id"
    // ROUND_ASSISTANT_SESSION_UX_FIX_1 — session-aware result dwell, computed by JS per-call
    // (0/absent = use TERMINAL_DISMISS_MS as a defensive default).
    const val EXTRA_DISMISS_DELAY_MS = "dismiss_delay_ms"
    // E3-2
    const val ACTION_BUBBLE_MOTION = "expo.modules.overlay.ACTION_BUBBLE_MOTION"
    const val EXTRA_MOTION = "motion" // "static" | "listening" | "executing"
    // ROUND_BENSON_BUBBLE_IME_1 — pushed by BensonAccessibilityService on an IME visible↔hidden
    // transition (by ComponentName string; no compile dependency between the modules).
    const val ACTION_IME_VISIBILITY = "expo.modules.overlay.ACTION_IME_VISIBILITY"
    const val EXTRA_IME_VISIBLE = "ime_visible"
    // ROUND_BUBBLE_VISIBILITY_POLICY_1 — pushed by BensonAccessibilityService's
    // pushForegroundPackageToBubble() on a foreground-package transition (native, event-driven,
    // same idiom as ACTION_IME_VISIBILITY above).
    const val ACTION_FOREGROUND_PACKAGE_CHANGED = "expo.modules.overlay.ACTION_FOREGROUND_PACKAGE_CHANGED"
    const val EXTRA_IS_SELF_FOREGROUND = "is_self_foreground"
    private const val PREFS = "benson_bubble_prefs"
    private const val KEY_HAS_POS = "bubble_has_pos"
    private const val KEY_X = "bubble_x"
    private const val KEY_Y = "bubble_y"
    private const val KEY_STATUS_HAS_POS = "status_has_pos"
    private const val KEY_STATUS_X = "status_x"
    private const val KEY_STATUS_Y = "status_y"
    // ROUND_BUBBLE_GEMINI_BEHAVIOR_1 — was 64. Revert: 64.
    private const val IDLE_BUBBLE_SIZE_DP = 44
    // ROUND_BUBBLE_STATE_DESYNC_FIX_1 — native-owned dismiss timing.
    // ~2-3s per product spec for a DONE/ERROR result.
    private const val TERMINAL_DISMISS_MS = 2500L
    // Absolute last-resort backstop for a non-terminal state (LISTENING/THINKING/EXECUTING/
    // CONFIRMING) whose turn never produces a terminal update at all (JS died mid-turn while
    // backgrounded — the exact class of bug this round exists to bound). Deliberately well past
    // missionOrchestrator.ts's own PENDING_DISAMBIGUATION_TIMEOUT_MS (60000) so a legitimate
    // CONFIRMING wait is never cut short by this backstop — it only fires if JS never got the
    // chance to resolve or expire that on its own.
    private const val NON_TERMINAL_SAFETY_NET_MS = 65_000L
  }
}

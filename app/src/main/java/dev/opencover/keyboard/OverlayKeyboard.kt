package dev.opencover.keyboard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.inputmethod.EditorInfo
import dev.opencover.COVER_DISPLAY
import kotlin.concurrent.thread

/**
 * OpenCover's keyboard: Samsung only allows its own keyboard app on the cover, so this is an overlay
 * that types through the accessibility service's input method (Android 13+), which Android attaches
 * to every focused text field next to the real keyboard.
 */
class OverlayKeyboard(private val service: AccessibilityService) : KeyboardView.Listener {
    @Volatile private var dict: Dictionary? = null
    private var view: KeyboardView? = null
    private var editor: EditorInfo? = null
    private var samsungHidden = false
    /** The word just swiped in, with its space, so a suggestion tap can swap it. */
    private var lastSwipe: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideSoon = Runnable { hide() }

    private val ic get() = service.inputMethod?.currentInputConnection

    init {
        thread(name = "dictionary") {
            dict = runCatching { Dictionary.parse(service.assets.open("words_en.txt").bufferedReader().readText()) }.getOrNull()
        }
    }

    fun startInput(info: EditorInfo) {
        handler.removeCallbacks(hideSoon)
        if (!wanted(info)) { hide(); return }
        editor = info
        lastSwipe = null
        val v = view ?: attach() ?: return // no overlay: leave Samsung's keyboard alone
        if (!samsungHidden) {
            service.softKeyboardController.showMode = AccessibilityService.SHOW_MODE_HIDDEN
            samsungHidden = true
        }
        v.showSuggestions = KeyboardPrefs.suggestions(service)
        refresh()
    }

    /** Fields often finish and restart input in quick pairs; hide only if nothing restarts. */
    fun finishInput() {
        handler.removeCallbacks(hideSoon)
        handler.postDelayed(hideSoon, 150)
    }

    fun selectionChanged() = refresh()

    private fun wanted(info: EditorInfo): Boolean {
        if (!KeyboardPrefs.enabled(service)) return false
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        // Typing on the cover means the main screen is off (folded).
        val main = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (main?.state == Display.STATE_ON) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = (cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORDS) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        // Password fields keep Samsung's keyboard.
        return cls != InputType.TYPE_NULL && !password
    }

    private fun attach(): KeyboardView? {
        if (!Settings.canDrawOverlays(service)) return null
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return null
        val wctx = service.createDisplayContext(display).createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val v = KeyboardView(wctx, this)
        val params = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY, LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.BOTTOM
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0 // flush with the bottom edge; the view's own gap clears the camera
        }
        return runCatching { wctx.getSystemService(WindowManager::class.java).addView(v, params); v }.getOrNull()?.also { view = it }
    }

    fun hide() {
        handler.removeCallbacks(hideSoon)
        editor = null
        lastSwipe = null
        view?.let { v -> runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) } }
        view = null
        if (samsungHidden) {
            service.softKeyboardController.showMode = AccessibilityService.SHOW_MODE_AUTO
            samsungHidden = false
        }
    }

    /** The text around the cursor, and where that window starts in the field. */
    private class Snap(val s: EditState, val offset: Int)

    private fun snap(): Snap? {
        val st = ic?.getSurroundingText(200, 200, 0) ?: return null
        val t = st.text.toString()
        val a = st.selectionStart.coerceIn(0, t.length)
        val b = st.selectionEnd.coerceIn(0, t.length)
        return Snap(EditState(t, minOf(a, b), maxOf(a, b)), st.offset)
    }

    private fun refresh() {
        val v = view ?: return
        val s = snap()?.s ?: return
        v.preview = s.text.substring(0, s.start) + "|" + s.text.substring(s.end)
        if (lastSwipe == null) v.suggestions = dict?.complete(s.currentWord()).orEmpty()
    }

    private fun commit(s: String) = ic?.commitText(s, 1, null)

    private fun deleteBefore(n: Int) {
        if (n > 0) ic?.deleteSurroundingText(n, 0)
    }

    /** Deletes what [op] would remove before the cursor (or the selection). */
    private fun deleteBy(op: (EditState) -> EditState) {
        val s = snap()?.s ?: return
        if (s.start != s.end) commit("") else deleteBefore(s.start - op(s).start)
    }

    override fun onText(s: String) { lastSwipe = null; commit(s) }
    override fun onBackspace() { lastSwipe = null; deleteBy { it.backspace() } }
    override fun onDeleteWord() { lastSwipe = null; deleteBy { it.deleteWord() } }

    override fun onCursor(by: Int) {
        lastSwipe = null
        val sn = snap() ?: return
        val c = sn.s.moveCursor(by).start + sn.offset
        ic?.setSelection(c, c)
    }

    override fun onEnter() {
        lastSwipe = null
        val info = editor ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (multiLine || noAction) {
            ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        } else ic?.performEditorAction(action)
    }

    override fun onPaste() { lastSwipe = null; ic?.performContextMenuAction(android.R.id.paste) }

    override fun onSuggestion(index: Int) {
        val w = view?.suggestions?.getOrNull(index) ?: return
        val swiped = lastSwipe
        lastSwipe = null
        deleteBefore(swiped?.length ?: snap()?.s?.currentWord()?.length ?: 0)
        commit("$w ")
    }

    override fun onSwipe(trail: List<Pt>, centers: Map<Char, Pt>, keyWidth: Float, caps: Boolean) {
        val words = Swipe.rank(trail, centers, keyWidth, dict?.all ?: return)
        val best = words.firstOrNull() ?: return
        val w = if (caps) best.replaceFirstChar { it.uppercase() } else best
        val s = snap()?.s
        val before = s?.text?.substring(0, s.start).orEmpty()
        commit(if (before.isEmpty() || before.last().isWhitespace()) "$w " else " $w ")
        lastSwipe = "$w "
        view?.suggestions = words.drop(1)
    }

    override fun onHideSuggestions() {
        KeyboardPrefs.setSuggestions(service, false)
        view?.showSuggestions = false
    }

    private companion object {
        val PASSWORDS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
    }
}

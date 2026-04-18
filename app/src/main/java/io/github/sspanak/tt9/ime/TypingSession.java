package io.github.sspanak.tt9.ime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.languages.Language;

/**
 * Holds the "current typing session" — everything that changes per input field / language / mode
 * selection but needs to be visible to lifecycle, key dispatch, suggestion, and UI code alike.
 *
 * Previously these fields were spread across {@link BaseHandler}, {@link TypingHandler} and
 * {@link CommandHandler}, which made it easy to forget to reset a piece of state on field change
 * (see e.g. the emoji-mode restart bug, where {@code inEmojiMode} wasn't cleared). Grouping them
 * here gives one obvious place to look when we need to know "what is the IME currently doing?"
 *
 * The fields are intentionally mutable — the handlers still do the mutation — but reads and
 * writes flow through one object instead of three inheritance layers.
 */
public final class TypingSession {
	/** The active input mode (Predictive, ABC, 123, …). Never null. */
	@NonNull public InputMode mode = InputMode.getInstance(null, null, null, null, InputMode.MODE_PASSTHROUGH);

	/** Active language for the current field. May be null during early init. */
	@Nullable public Language language;

	/** Language IDs enabled by the user, in cycle order. */
	@NonNull public ArrayList<Integer> enabledLanguages = new ArrayList<>();

	/** Input modes the current field/language pair permits, in cycle order. */
	@NonNull public ArrayList<Integer> allowedInputModes = new ArrayList<>();

	/** Effective text-case to display (capitalised / upper / lower / undefined). */
	public int displayTextCase = InputMode.CASE_UNDEFINED;

	/** True when we've temporarily switched from Predictive → ABC to let the user type a word. */
	public boolean inPredictiveFallback = false;

	/** True when the bar is showing a next-word prediction that hasn't been accepted yet. */
	public boolean hasPendingNextWordPrediction = false;

	/** True between a word-accept and the next keystroke — if the next key is space, trim it. */
	public boolean waitingForSpaceTrim = false;

	/** Cached RTL flag for the active {@link #language}, so scroll/cursor directions can flip. */
	public boolean isLanguageRTL = false;
}

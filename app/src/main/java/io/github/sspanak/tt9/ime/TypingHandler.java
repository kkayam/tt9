package io.github.sspanak.tt9.ime;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.db.DataStore;
import io.github.sspanak.tt9.db.words.DictionaryLoader;
import io.github.sspanak.tt9.hacks.InputType;
import io.github.sspanak.tt9.ime.helpers.CursorOps;
import io.github.sspanak.tt9.ime.helpers.InputConnectionAsync;
import io.github.sspanak.tt9.ime.helpers.InputModeValidator;
import io.github.sspanak.tt9.ime.helpers.Key;
import io.github.sspanak.tt9.ime.helpers.SuggestionOps;
import io.github.sspanak.tt9.ime.helpers.TextField;
import io.github.sspanak.tt9.ime.helpers.TextSelection;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.UI;
import io.github.sspanak.tt9.util.Text;
import io.github.sspanak.tt9.util.Timer;
import io.github.sspanak.tt9.util.chars.Characters;
import io.github.sspanak.tt9.util.sys.Clipboard;

public abstract class TypingHandler extends BaseHandler {
	// key-event debouncing/tracking (formerly KeyPadHandler)
	private final static String DEBOUNCE_TIMER = "debounce_";
	private int ignoreNextKeyUp = 0;
	private int lastKeyCode = 0;
	private int keyRepeatCounter = 0;
	private int lastNumKeyCode = 0;
	private int numKeyRepeatCounter = 0;

	// internal settings/data
	@NonNull protected InputType inputType = new InputType(null, null);
	@NonNull protected TextField textField = new TextField(null, null, null);
	@NonNull protected TextSelection textSelection = new TextSelection(null, null);
	@NonNull protected SuggestionOps suggestionOps = new SuggestionOps(null, null, null, null, null, null);

	@Nullable private Handler shiftStateDebounceHandler;
	@Nullable private Handler suggestionHandler;

	// Mode, language, text-case flags and per-field booleans now live in {@link #session}
	// (see {@link TypingSession}). The fields below are only read/written through session.*


	protected void initSuggestionOps() {
		suggestionOps = new SuggestionOps(this, settings, appHacks, inputType, textField, this::onAcceptSuggestionsDelayed);
	}


	protected boolean shouldBeOff() {
		return getCurrentInputConnection() == null || InputModeKind.isPassthrough(session.mode);
	}


	protected boolean isInPredictiveFallback() {
		return session.inPredictiveFallback;
	}


	/**
	 * enterPredictiveFallback
	 * Temporarily switches from predictive to ABC mode for custom word entry.
	 * Does not save the mode change to settings.
	 */
	protected void enterPredictiveFallback(int initialChars) {
		if (session.inPredictiveFallback || !session.language.hasABC()) {
			return;
		}

		session.inPredictiveFallback = true;

		session.mode = InputMode.getInstance(settings, session.language, inputType, textField, InputMode.MODE_ABC);
		determineTextCase();

		getDisplayTextCase(session.language, session.mode.getTextCase());
		setStatusIcon(session.mode, session.language);
		getFinalContext().pushModeInfoToBar();
	}


	/**
	 * exitPredictiveFallback
	 * Restores predictive mode after a temporary ABC fallback.
	 */
	protected void exitPredictiveFallback() {
		if (!session.inPredictiveFallback) {
			return;
		}

		session.inPredictiveFallback = false;

		session.mode = InputMode.getInstance(settings, session.language, inputType, textField, InputMode.MODE_PREDICTIVE);
		determineTextCase();

		getDisplayTextCase(session.language, session.mode.getTextCase());
		setStatusIcon(session.mode, session.language);
		getFinalContext().pushModeInfoToBar();
	}


	/**
	 * Main initialization of the input method component.
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		settings = new SettingsStore(getApplicationContext());
		onInit();
	}


	@Override
	protected void onInit() {
		super.onInit();
	}


	protected void cleanUp() {
		InputConnectionAsync.destroy();
	}


	/********** Key event handling (formerly KeyPadHandler) **********/

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (debounceKey(keyCode, event)) {
			return true;
		}

		if (settings.getInputHandlingMode() == SettingsStore.INPUT_HANDLING_RETURN_FALSE) {
			return false;
		} else if (settings.getInputHandlingMode() == SettingsStore.INPUT_HANDLING_CALL_SUPER) {
			return super.onKeyDown(keyCode, event);
		}

		if (shouldBeOff() && !isSuggestionBarNavKey(keyCode)) {
			return false;
		}

		// "backspace" key must repeat its function when held down, so we handle it in a special way
		if (Key.isBackspace(settings, keyCode)) {
			if (onBackspace(event.getRepeatCount())) {
				return Key.setHandled(KeyEvent.KEYCODE_DEL, true);
			} else {
				Key.setHandled(KeyEvent.KEYCODE_DEL, false);
			}
		}

		// start tracking key hold
		if (Key.isNumber(keyCode)) {
			event.startTracking();
			return true;
		}
		else if (getFinalContext().isHoldHotkey(-keyCode)) {
			event.startTracking();
		}

		// on many devices there is a default back handler, so we must fall back to it when we don't
		// perform any operation
		if (Key.isBack(keyCode)) {
			Key.setHandled(keyCode, onBack());
			return Key.isHandledInSuper(keyCode) ? super.onKeyDown(keyCode, event) : Key.isHandled(keyCode);
		} else {
			Key.setHandled(KeyEvent.KEYCODE_BACK, false);
		}

		if (Key.setHandled(KeyEvent.KEYCODE_ENTER, Key.isOK(keyCode) && onOK())) return true;

		// Validate hotkey intents — just check acceptance; the side effect runs on key up.
		if (hotkeyIntent(keyCode, true, false).accepted()) return true;
		if (hotkeyIntent(keyCode, false, keyRepeatCounter + 1 > 0).accepted()) return true;

		return (Key.isPoundOrStar(keyCode) && onText(String.valueOf((char) event.getUnicodeChar()), true))
			|| super.onKeyDown(keyCode, event);
	}


	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		if (settings.getInputHandlingMode() == SettingsStore.INPUT_HANDLING_RETURN_FALSE) {
			return false;
		} else if (settings.getInputHandlingMode() == SettingsStore.INPUT_HANDLING_CALL_SUPER) {
			return super.onKeyLongPress(keyCode, event);
		}

		if (shouldBeOff()) {
			return false;
		}

		if (event.getRepeatCount() > 1) {
			return true;
		}

		ignoreNextKeyUp = keyCode;
		if (Key.isNumber(keyCode)) {
			numKeyRepeatCounter = 0;
			lastNumKeyCode = 0;
			return onNumber(Key.codeToNumber(settings, keyCode), true, 0);
		} else {
			keyRepeatCounter = 0;
			lastKeyCode = 0;
		}

		if (hotkeyIntent(keyCode, true, false).run()) {
			return true;
		}

		ignoreNextKeyUp = 0;
		return false;
	}


	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (debounceKey(keyCode, event)) {
			return true;
		}

		if (settings.getInputHandlingMode() == SettingsStore.INPUT_HANDLING_RETURN_FALSE) {
			return false;
		} else if (settings.getInputHandlingMode() == SettingsStore.INPUT_HANDLING_CALL_SUPER) {
			return super.onKeyUp(keyCode, event);
		}

		if (shouldBeOff() && !isSuggestionBarNavKey(keyCode)) {
			return false;
		}

		if (keyCode == ignoreNextKeyUp) {
			ignoreNextKeyUp = 0;
			return true;
		}

		if (Key.isBackspace(settings, keyCode) && Key.isHandled(KeyEvent.KEYCODE_DEL)) {
			return true;
		}

		keyRepeatCounter = (lastKeyCode == keyCode) ? keyRepeatCounter + 1 : 0;
		lastKeyCode = keyCode;

		if (Key.isNumber(keyCode)) {
			numKeyRepeatCounter = (lastNumKeyCode == keyCode) ? numKeyRepeatCounter + 1 : 0;
			lastNumKeyCode = keyCode;
			return onNumber(Key.codeToNumber(settings, keyCode), false, numKeyRepeatCounter);
		}

		if (Key.isBack(keyCode)) {
			return Key.isHandledInSuper(keyCode) ? super.onKeyUp(keyCode, event) : Key.isHandled(keyCode);
		}

		if (Key.isOK(keyCode) && Key.isHandled(KeyEvent.KEYCODE_ENTER)) return true;
		if (hotkeyIntent(keyCode, false, keyRepeatCounter > 0).run()) return true;
		return (Key.isPoundOrStar(keyCode) && onText(String.valueOf((char) event.getUnicodeChar()), false))
			|| super.onKeyUp(keyCode, event);
	}


	private KeyIntent hotkeyIntent(int keyCode, boolean hold, boolean repeat) {
		return onHotkey(keyCode * (hold ? -1 : 1), repeat);
	}


	/**
	 * Suggestion-bar scroll keys must be consumed whenever the bar has items to show.
	 * Otherwise the default {@link InputMethodService} onKeyDown/onKeyUp lets the event bubble
	 * up and the host window steals focus (e.g. DPAD_LEFT jumps to a neighbouring control),
	 * which is what users experience while picking emojis in some apps.
	 */
	private boolean isSuggestionBarNavKey(int keyCode) {
		if (suggestionOps.isEmpty()) return false;
		return keyCode == settings.getKeyPreviousSuggestion() || keyCode == settings.getKeyNextSuggestion();
	}


	protected void resetKeyRepeat() {
		numKeyRepeatCounter = 0;
		keyRepeatCounter = 0;
		lastNumKeyCode = 0;
		lastKeyCode = 0;
	}


	private boolean debounceKey(int keyCode, KeyEvent event) {
		if (settings.getKeyPadDebounceTime() <= 0 || event.isLongPress()) {
			return false;
		}

		String keyTimer = DEBOUNCE_TIMER + keyCode;

		if (Timer.get(keyTimer) > 0 && Timer.get(keyTimer) < settings.getKeyPadDebounceTime()) {
			return true;
		}

		if (event.getAction() == KeyEvent.ACTION_UP) {
			Timer.start(keyTimer);
		}

		return false;
	}


	@Override
	protected boolean onStart(EditorInfo field, boolean restarting) {
		boolean restart = restarting || textField.equals(getCurrentInputConnection(), field);

		setInputField(field);

		// 1. In case we are back from Settings screen, update the language list
		// 2. If the connected app hints it is in a language different than the current one,
		// we try to switch.
		boolean languageChanged = determineLanguage();

		// ignore multiple calls for the same field, caused by requestShowSelf() -> showWindow(),
		// or weirdly functioning apps, such as the Qin SMS app
		if (restart && !languageChanged && session.mode.getId() == determineInputModeId()) {
			return false;
		}
		session.inPredictiveFallback = false;
		settings.setDefaultCharOrder(session.language, false);
		resetKeyRepeat();
		session.mode = determineInputMode();
		determineTextCase();
		suggestionOps.set(null);

		// don't use surroundingText cache on start up
		final String[] surroundingText = textField.getSurroundingStringForAutoAssistance(settings, session.mode);
		updateShiftState(surroundingText[0], false, false);

		return true;
	}


	protected void setInputField(EditorInfo field) {
		if (textField.equals(getCurrentInputConnection(), field)) {
			return;
		}

		InputMethodService context = field != null ? this : null;
		inputType = new InputType(context, field);
		textField = new TextField(context, settings, field);
		textSelection = new TextSelection(context, inputType);

		// changing the TextField and notifying all interested classes is an atomic operation
		appHacks.setDependencies(inputType, textField, textSelection);
		suggestionOps.setDependencies(appHacks, inputType, textField);
	}


	protected void validateLanguages() {
		session.enabledLanguages = InputModeValidator.validateEnabledLanguages(session.enabledLanguages);
		session.language = InputModeValidator.validateLanguage(session.language, session.enabledLanguages);
		settings.saveInputLanguage(session.language.getId());
		settings.saveEnabledLanguageIds(session.enabledLanguages);
	}


	protected void onFinishTyping() {
		session.inPredictiveFallback = false;
		if (shiftStateDebounceHandler != null) {
			shiftStateDebounceHandler.removeCallbacksAndMessages(null);
			shiftStateDebounceHandler = null;
		}
		if (suggestionHandler != null) {
			suggestionHandler.removeCallbacksAndMessages(null);
			suggestionHandler = null;
		}
		suggestionOps.cancelDelayedAccept();
		session.mode = InputMode.getInstance(null, null, null, null, InputMode.MODE_PASSTHROUGH);
		setInputField(null);
	}


	@Override
	public boolean onBackspace(int repeat) {
		// Dialer fields seem to handle backspace on their own and we must ignore it,
		// otherwise, keyDown race condition occur for all keys.
		if (InputModeKind.isPassthrough(session.mode)) {
			return false;
		}

		if (appHacks.onBackspace(settings, session.mode)) {
			session.mode.reset();
			return false;
		}

		suggestionOps.cancelDelayedAccept();
		resetKeyRepeat();

		if (settings.getBackspaceAcceleration() && repeat > 0 && repeat % SettingsStore.BACKSPACE_ACCELERATION_REPEAT_DEBOUNCE != 0) {
			return true;
		}

		// Track whether the user is mid-letter-selection (ABC mode) before backspace modifies state
		boolean wasMidLetterSelection = session.inPredictiveFallback && session.mode.isTyping();

		session.mode.beforeDeleteText();

		// load new words only if there is no selected text, because it would be confusing
		if (repeat == 0 && session.mode.onBackspace() && textSelection.isEmpty()) {
			final Runnable onLoad = InputModeKind.isRecomposing(session.mode) ? null : () -> recompose(repeat, false);
			getSuggestions(0, null, onLoad);
		} else {
			suggestionOps.acceptAndClear(false);
			session.mode.reset();
			deleteText(settings.getBackspaceAcceleration() && repeat > 0);
			updateShiftStateDebounced(null, session.mode.noSuggestions(), false); // backspace may change the text too much, so no beforeCursor cache for now
			recompose(repeat, !textSelection.isEmpty());
		}

		// In predictive fallback (ABC mode): exit when the cursor is at the start of the field
		// or immediately after a space — meaning the entire custom word has been erased.
		if (session.inPredictiveFallback && !wasMidLetterSelection) {
			String beforeCursor = textField.getStringBeforeCursor(1);
			if (beforeCursor.isEmpty() || beforeCursor.equals(Characters.getSpace(session.language))) {
				exitPredictiveFallback();
			}
		}

		return true;
	}


	/**
	 * onNumber
	 *
	 * @param key     Must be a number from 1 to 9, not a "KeyEvent.KEYCODE_X"
	 * @param hold    If "true" we are calling the handler, because the key is being held.
	 * @param repeat  If "true" we are calling the handler, because the key was pressed more than once
	 * @return boolean
	 */
	protected boolean onNumber(int key, boolean hold, int repeat) {
		suggestionOps.cancelDelayedAccept();

		hold = hold && settings.getHoldToType();

		// Key 0 is pure Space in all non-numeric modes. No character cycling —
		// onText commits any in-progress word (or pending prediction) and types the
		// language-appropriate space. onText then shows the next-word prediction, if any.
		if (key == 0 && !hold && !InputModeKind.isNumeric(session.mode)) {
			onText(Characters.getSpace(session.language), false);
			return true;
		}

		// User is typing a different word — cancel any pending next-word prediction so
		// it doesn't leak into the new word.
		session.hasPendingNextWordPrediction = false;

		String[] surroundingChars = textField.getSurroundingStringForAutoAssistance(settings, session.mode);
		String lastWord = null;

		// Automatically accept the previous word, when the next one is a space or punctuation,
		// instead of requiring "OK" before that.
		// First pass, analyze the incoming key press and decide whether it could be the start of
		// a new word. In case we do accept it, we preserve the suggestion list instead of clearing,
		// to prevent flashing while the next suggestions are being loaded.

		// In ABC fallback mode, key 0 (space) must commit the in-progress letter before
		// the mode resets it. ModeABC's 3-arg shouldAcceptPreviousSuggestion returns false,
		// so we handle it explicitly here.
		if (session.inPredictiveFallback && key == 0 && !suggestionOps.isEmpty()) {
			lastWord = suggestionOps.acceptAndKeep(false);
			session.mode.onAcceptSuggestion(lastWord);
			surroundingChars = autoCorrectSpace(lastWord, surroundingChars, false, key);
		} else if (session.mode.shouldAcceptPreviousSuggestion(suggestionOps.getCurrent(), key, hold)) {
			// WARNING! Ensure the code after "acceptIncompleteAndKeepList()" does not depend on
			// the suggestions in SuggestionOps, since we don't clear that list.
			lastWord = suggestionOps.acceptAndKeep(false);
			session.mode.onAcceptSuggestion(lastWord);
			surroundingChars = autoCorrectSpace(lastWord, surroundingChars, false, key);
		}

		// Auto-add unknown words to dictionary when spacebar (key 0) finishes a word
		if (key == 0 && surroundingChars[0] != null && !surroundingChars[0].isEmpty()) {
			String space = Characters.getSpace(session.language);
			int spaceIdx = surroundingChars[0].lastIndexOf(space);
			String finishedWord = spaceIdx >= 0 ? surroundingChars[0].substring(spaceIdx + space.length()) : surroundingChars[0];
			if (!finishedWord.isEmpty()) {
				DataStore.putSilently(session.language, finishedWord);
			}
			
			// Space finishes the current word in fallback mode, restoring predictive
			if (session.inPredictiveFallback) {
				exitPredictiveFallback();
			}
		}

		// Auto-adjust the text case before each word/char, if the InputMode supports it.
		session.mode.determineNextWordTextCase(surroundingChars[0], key);

		if (!session.mode.onNumber(key, hold, repeat, surroundingChars)) {
			forceShowWindow();
			return false;
		}

		if (session.mode.shouldSelectNextSuggestion() && !session.mode.noSuggestions()) {
			scrollSuggestions(false);
			suggestionOps.scheduleDelayedAccept(session.mode.getAutoAcceptTimeout());
		} else {
			getSuggestions(Math.random(), null, null);
		}

		return true;
	}


	public boolean onText(String text, boolean validateOnly) {
		if (session.mode.shouldIgnoreText(text)) {
			return false;
		}

		if (validateOnly) {
			return true;
		}

		suggestionOps.cancelDelayedAccept();

		String[] surroundingChars;

		// A pending next-word prediction is shown in the suggestion bar only (no composing
		// text). Accepting it = typing it as real text via acceptCurrent, which calls
		// setComposingText + finishComposingText under the hood.
		String lastWord;
		if (session.hasPendingNextWordPrediction && !suggestionOps.isEmpty()) {
			lastWord = suggestionOps.acceptAndClear(true);
		} else {
			lastWord = suggestionOps.acceptAndClear(false);
		}
		session.hasPendingNextWordPrediction = false;

		if (lastWord.isEmpty()) {
			surroundingChars = textField.getSurroundingStringForAutoAssistance(settings, session.mode);
		} else {
			session.mode.onAcceptSuggestion(lastWord);
			surroundingChars = autoCorrectSpace(
				lastWord,
				textField.getSurroundingStringForAutoAssistance(settings, session.mode),
				false,
				-1
			);
		}

		// "type" and accept the new word
		session.mode.onAcceptSuggestion(text);
		textField.setText(text);
		surroundingChars[0] += text;
		surroundingChars = autoCorrectSpace(text, surroundingChars, true, -1);

		if (surroundingChars[0].endsWith(Characters.getSpace(session.language))) {
			waitForSpaceTrimKey();
		}

		forceShowWindow();

		session.mode.determineNextWordTextCase(surroundingChars[0], -1);
		updateShiftState(surroundingChars[0], false, false);

		// After a space, show the most-frequent next-word hint from the word-pair store.
		// Appears as both a suggestion and composing text so space/OK accept it the same way
		// any in-progress word does.
		if (Characters.getSpace(session.language).equals(text)) {
			showNextWordPrediction(lastWord);
		}

		return true;
	}


	private void showNextWordPrediction(@Nullable String lastCommittedWord) {
		if (!InputModeKind.isPredictive(session.mode) || !settings.getPredictWordPairs()) {
			return;
		}

		String word1 = (lastCommittedWord != null && !lastCommittedWord.isEmpty())
			? lastCommittedWord
			: textField.getTextBeforeCursor(session.language, 50).getPreviousWord(false, false, false);

		if (word1 == null || word1.isEmpty()) {
			return;
		}

		String predicted = DataStore.getNextWord(session.language, word1);
		if (predicted == null || predicted.isEmpty()) {
			return;
		}

		// Suggestion-bar only — no inline composing text. The pending-prediction flag
		// tells onNumber/onText to accept this via acceptCurrent on the next space/OK.
		ArrayList<String> suggestions = new ArrayList<>();
		suggestions.add(predicted);
		suggestionOps.set(suggestions, 0, false);
		session.hasPendingNextWordPrediction = true;
	}


	@NonNull
	protected String[] autoCorrectSpace(@Nullable String currentWord, @NonNull String[] surroundingChars, boolean isWordAcceptedManually, int nextKey) {
		if (currentWord == null || currentWord.isEmpty() || !settings.isAutoAssistanceOn(session.mode)) {
			return surroundingChars;
		}

		String previousChars = surroundingChars[0];
		final String nextChars = surroundingChars[1];

		if (!inputType.isRustDesk() && session.mode.shouldDeletePrecedingSpace(previousChars)) {
			textField.deletePrecedingSpace(currentWord);
			if (previousChars.endsWith(" " + currentWord) && previousChars.length() > currentWord.length()) {
				final int precedingSpace = previousChars.length() - currentWord.length() - 1;
				previousChars = previousChars.substring(0, precedingSpace) + currentWord;
			}
		}

		if (session.mode.shouldAddPrecedingSpace(previousChars)) {
			textField.addPrecedingSpace(currentWord);
			if (previousChars.endsWith(currentWord)) {
				final int startOfWord = previousChars.length() - currentWord.length();
				previousChars = previousChars.substring(0, startOfWord) + " " + currentWord;
			}
		}

		if (session.mode.shouldAddTrailingSpace(previousChars, nextChars, isWordAcceptedManually, nextKey)) {
			textField.setText(" ");
			previousChars += " ";
		}

		return new String[] { previousChars, nextChars };
	}


	private void deleteText(boolean deleteMany) {
		int charsToDelete = 1;

		if (!textSelection.isEmpty()) {
			charsToDelete = textSelection.length();
			textSelection.clear(false);
		} else if (deleteMany) {
			charsToDelete = textField.getComposingText().length();
			charsToDelete = charsToDelete > 0 ? charsToDelete : Math.max(textField.getPaddedWordBeforeCursorLength(), 1);
		}

		textField.deleteChars(session.language, charsToDelete);
	}


	/**
	 * determineLanguage
	 * Restore the last language or auto-select a more appropriate one, if the application hints so.
	 * In case the settings are not valid, we will fallback to the default language.
	 */
	private boolean determineLanguage() {
		session.enabledLanguages = settings.getEnabledLanguageIds();

		int oldLang = session.language != null ? session.language.getId() : -1;
		session.language = LanguageCollection.getLanguage(settings.getInputLanguage());
		validateLanguages();

		Language appLanguage = textField.getLanguage(session.enabledLanguages);
		if (appLanguage != null) {
			session.language = appLanguage;
		}

		return oldLang != session.language.getId();
	}


	/**
	 * determineTextCase
	 * Restore the last used text case or auto-select a new one based on the input field properties.
	 */
	protected void determineTextCase() {
		InputModeValidator.validateTextCase(session.mode, settings.getTextCase());
	}


	/**
	 * determineInputModeId
	 * Return the last input mode ID or choose a more appropriate one.
	 * Some input fields support only numbers or are not suited for predictions (e.g. password fields).
	 * Others do not support text retrieval or composing text, or the AppHacks detected them as incompatible with us.
	 * We do not want to handle any of these, hence we pass through all input to the system.
	 */
	protected int determineInputModeId() {
		if (!inputType.isValid() || (inputType.isLimited() && !inputType.isTeams() && !inputType.isTermux())) {
			return InputMode.MODE_PASSTHROUGH;
		}

		session.allowedInputModes = new ArrayList<>(inputType.determineInputModes(getApplicationContext()));
		if (LanguageKind.isJapanese(session.language)) {
			determineJapaneseInputModes();
		}

		if (!session.language.hasABC()) {
			session.allowedInputModes.remove((Integer) InputMode.MODE_ABC);
		}

		if (!settings.getPredictiveMode()) {
			session.allowedInputModes.remove((Integer) InputMode.MODE_PREDICTIVE);
		}

		return InputModeValidator.validateMode(settings.getInputMode(), session.allowedInputModes);
	}


	/**
	 * In Japanese, Hiragana and Katakana modes are the equivalents of ABC mode in other languages.
	 * So when typing letters is possible (ABC mode allowed), we replace ABC with these two modes.
	 */
	private void determineJapaneseInputModes() {
		if (session.allowedInputModes.contains(InputMode.MODE_ABC)) {
			session.allowedInputModes.add(InputMode.MODE_HIRAGANA);
			session.allowedInputModes.add(InputMode.MODE_KATAKANA);
		}
	}


	/**
	 * determineInputMode
	 * Same as determineInputModeId(), but returns an actual InputMode.
	 */
	protected InputMode determineInputMode() {
		return InputMode.getInstance(settings, session.language, inputType, textField, determineInputModeId());
	}


	/**
	 * Try to recompose the current word after a backspace operation. If successful, load new
	 * suggestions. Otherwise, reset the InputMode.
	 */
	private void recompose(int backspaceRepeat, boolean isTextSelected) {
		if (!settings.getBackspaceRecomposing() || backspaceRepeat > 0 || isFnPanelVisible() || isTextSelected || !suggestionOps.isEmpty() || DictionaryLoader.getInstance(this).isRunning()) {
			return;
		}

		final String previousWord = session.mode.recompose();
		if (textField.recompose(previousWord)) {
			getSuggestions(0, previousWord, null);
		} else {
			session.mode.reset();
		}
	}


	@Override
	public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
//		Logger.d("onUpdateSelection", "old (" + oldSelStart + ", " + oldSelEnd + ") => new (" + newSelStart + ", " + newSelEnd + "); candidates = (" + candidatesStart + ", " + candidatesEnd + ")");

		super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
		textSelection.onSelectionUpdate(newSelStart, newSelEnd);

		// in case the app has modified the InputField and moved the cursor without notifying us...
		if (CursorOps.isInputReset(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)) {
			stopWaitingForSpaceTrimKey();
			if (!appHacks.acceptComposingTextOnCursorReset(session.mode, suggestionOps, textField)) {
				suggestionOps.clear();
			}
			return;
		}

		// If the cursor moves while composing a word (usually, because the user has touched the screen outside the word), we must
		// end typing end accept the word. Otherwise, the cursor would jump back at the end of the word, after the next key press.
		// This is confusing from user perspective, so we want to avoid it.
		if (CursorOps.isMovedWhileTyping(newSelStart, newSelEnd, candidatesStart, candidatesEnd)) {
			stopWaitingForSpaceTrimKey();
			session.inPredictiveFallback = false;
			session.mode.onCursorMove(suggestionOps.acceptAndClear(false));
			return;
		}

		// Prevent deleting a space using the left arrow key, if the user has moved the cursor to another
		// location. This prevents undesired deletion of the space, in the middle of the text.
		if (CursorOps.isMovedFar(newSelStart, newSelEnd, oldSelStart, oldSelEnd)) {
			stopWaitingForSpaceTrimKey();
		}
	}


	protected void scrollSuggestions(boolean backward) {
		suggestionOps.cancelDelayedAccept();
		suggestionOps.scrollTo(backward ? -1 : 1);

		// In emoji mode, scrolling is purely a bar-navigation action — the emoji is only committed
		// on OK. Writing composing text here triggers some apps (e.g. WhatsApp) to restart the IME,
		// which clears the suggestion list and lets the next DPAD press fall through to focus
		// navigation in the host UI.
		if (getFinalContext().isInEmojiMode()) {
			return;
		}

		session.mode.setWordStem(suggestionOps.getCurrent(), true);
		if (InputModeKind.isRecomposing(session.mode)) {
			appHacks.setComposingTextPartsWithHighlightedJoining(session.mode.getWordStem() + suggestionOps.getCurrent(), session.mode.getRecomposingSuffix());
		} else {
			appHacks.setComposingTextWithHighlightedStem(suggestionOps.getCurrent(), session.mode.getWordStem(), session.mode.isStemFilterFuzzy());
		}
	}


	protected void updateShiftStateDebounced(@Nullable String beforeCursor, boolean determineTextCase, boolean onlyWhenLetters) {
		if (shiftStateDebounceHandler == null) {
			shiftStateDebounceHandler = new Handler(Looper.getMainLooper());
		} else {
			shiftStateDebounceHandler.removeCallbacksAndMessages(null);
		}
		shiftStateDebounceHandler.postDelayed(() -> updateShiftState(beforeCursor, determineTextCase, onlyWhenLetters), SettingsStore.SHIFT_STATE_DEBOUNCE_TIME);
	}


	protected void updateShiftState(@Nullable String beforeCursor, boolean determineTextCase, boolean onlyWhenLetters) {
		if (onlyWhenLetters && !new Text(suggestionOps.getCurrent()).isAlphabetic()) {
			return;
		}

		if (determineTextCase) {
			beforeCursor = beforeCursor != null ? beforeCursor : textField.getStringBeforeCursor();
			session.mode.determineNextWordTextCase(beforeCursor, -1);
		}

		getDisplayTextCase(session.language, session.mode.getTextCase());
		setStatusIcon(session.mode, session.language);
	}


	/********** Suggestions pipeline (formerly SuggestionHandler) **********/

	private Handler getAsyncSuggestionHandler() {
		if (suggestionHandler == null) {
			suggestionHandler = new Handler(Looper.getMainLooper());
		}
		return suggestionHandler;
	}


	private String[] onAcceptPreviousSuggestion() {
		final int lastWordLength = InputModeKind.isABC(session.mode) ? 1 : session.mode.getSequenceLength() - 1;
		String lastWord = suggestionOps.getCurrent(session.language, lastWordLength);
		if (Characters.PLACEHOLDER.equals(lastWord)) {
			lastWord = "";
		}

		suggestionOps.acceptAndClear(false);
		session.mode.onAcceptSuggestion(lastWord, true);
		final String[] surroundingText = autoCorrectSpace(
			lastWord,
			textField.getSurroundingStringForAutoAssistance(settings, session.mode),
			false,
			session.mode.getFirstKey()
		);
		session.mode.determineNextWordTextCase(surroundingText[0], -1);

		return surroundingText;
	}


	protected void onAcceptSuggestionsDelayed(String word) {
		onAcceptSuggestionManually(word, -1);
		forceShowWindow();
	}


	protected void onAcceptSuggestionManually(String word, int fromKey) {
		session.mode.onAcceptSuggestion(word);
		if (Clipboard.contains(word)) {
			Clipboard.copy(this, word);
		}

		if (!word.isEmpty()) {
			String[] surroundingText = autoCorrectSpace(
				word,
				textField.getSurroundingStringForAutoAssistance(settings, session.mode),
				true,
				fromKey
			);

			session.mode.determineNextWordTextCase(surroundingText[0], -1);
			updateShiftState(surroundingText[0], false, false);
			resetKeyRepeat();
		}

		if (!Characters.getSpace(session.language).equals(word)) {
			waitForSpaceTrimKey();
		}

		// In fallback mode: exit when a space is accepted
		if (isInPredictiveFallback() && Characters.getSpace(session.language).equals(word)) {
			exitPredictiveFallback();
		}
	}


	@NonNull
	@Override
	public SuggestionOps getSuggestionOps() {
		return suggestionOps;
	}


	/**
	 * Ask the InputMode to load suggestions for the current state. No action is taken if the dictionary
	 * is still loading. Note that onComplete is called even if the loading was skipped.
	 */
	protected void getSuggestions(double loadingId, @Nullable String currentWord, @Nullable Runnable onComplete) {
		if (InputModeKind.isPredictive(session.mode) && DictionaryLoader.getInstance(this).isRunning()) {
			session.mode.reset();
			UI.toastShortSingle(this, R.string.dictionary_loading_please_wait);
			if (onComplete != null) {
				onComplete.run();
			}
		} else {
			session.mode
				.setOnSuggestionsUpdated(() -> handleSuggestionsAsync(loadingId, onComplete))
				.loadSuggestions(currentWord == null ? suggestionOps.getCurrent() : currentWord);
		}
	}


	@WorkerThread
	protected void handleSuggestionsAsync() {
		handleSuggestionsAsync(0, null);
	}


	@WorkerThread
	protected void handleSuggestionsAsync(double loadingId, @Nullable Runnable onComplete) {
		final Handler handler = getAsyncSuggestionHandler();
		handler.removeCallbacksAndMessages(null);
		handler.post(() -> handleSuggestions(loadingId, onComplete));
	}


	@MainThread
	protected void handleSuggestions(double loadingId, @Nullable Runnable onComplete) {
		// Second pass, analyze the available suggestions and decide if combining them with the
		// last key press makes up a compound word like: (it)'s, (I)'ve, l'(oiseau), or it is
		// just the end of a sentence, like: "word." or "another?"
		String[] surroundingText = null;
		if (session.mode.shouldAcceptPreviousSuggestion(suggestionOps.getCurrent())) {
			surroundingText = onAcceptPreviousSuggestion();
		}

		final ArrayList<String> suggestions = session.mode.getSuggestions();
		suggestionOps.set(suggestions, session.mode.getRecommendedSuggestionIdx(), session.mode.containsGeneratedSuggestions());

		// Predictive fallback: when no dictionary words match, commit everything except the
		// last key press, switch to ABC mode, and replay the last key so it shows ABC letters.
		if (!isInPredictiveFallback() && InputModeKind.isPredictive(session.mode) && session.mode.shouldFallbackToManual()) {
			int lastKey = session.mode.getLastKey();
			int seqLen = session.mode.getSequenceLength();

			String wordBeforeLastKey = seqLen > 1
				? suggestionOps.getCurrent(session.language, seqLen - 1)
				: "";
			if (!wordBeforeLastKey.isEmpty()) {
				appHacks.setComposingText(wordBeforeLastKey);
			}
			textField.finishComposingText();
			session.mode.onAcceptSuggestion(wordBeforeLastKey);
			suggestionOps.set(null);

			enterPredictiveFallback(wordBeforeLastKey.length());
			if (lastKey >= 0) {
				String[] surroundingChars = textField.getSurroundingStringForAutoAssistance(settings, session.mode);
				session.mode.onNumber(lastKey, false, 0, surroundingChars);
				getSuggestions(0, null, null);
			}
			return;
		}

		// either accept the first one automatically (when switching from punctuation to text
		// or vice versa), or schedule auto-accept in N seconds (in ABC mode)
		if (suggestionOps.scheduleDelayedAccept(session.mode.getAutoAcceptTimeout())) {
			if (onComplete != null) {
				onComplete.run();
			}
			return;
		}

		// We have not accepted anything yet, which means the user is composing a word.
		// put the first suggestion in the text field, but cut it off to the length of the sequence
		// (the count of key presses), for a more intuitive experience.
		String trimmedWord;

		if (InputModeKind.isRecomposing(session.mode)) {
			trimmedWord = session.mode.getWordStem() + suggestionOps.getCurrent();
			appHacks.setComposingTextPartsWithHighlightedJoining(trimmedWord, session.mode.getRecomposingSuffix());
		} else {
			trimmedWord = suggestionOps.getCurrent(session.language, session.mode.getSequenceLength());
			appHacks.setComposingTextWithHighlightedStem(trimmedWord, session.mode.getWordStem(), session.mode.isStemFilterFuzzy());
		}

		onAfterSuggestionsHandled(onComplete, surroundingText, trimmedWord, suggestions.isEmpty());
	}


	private void onAfterSuggestionsHandled(@Nullable Runnable callback, @Nullable String[] surroundingText, @Nullable String trimmedWord, boolean noSuggestions) {
		final String shiftStateContext = surroundingText != null ? surroundingText[0] + trimmedWord : trimmedWord;
		if (noSuggestions) {
			updateShiftStateDebounced(shiftStateContext, true, false);
		} else {
			updateShiftStateDebounced(shiftStateContext, false, true);
		}

		forceShowWindow();

		if (callback != null) {
			callback.run();
		}
	}
}

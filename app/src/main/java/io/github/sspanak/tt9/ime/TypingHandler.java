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
	@NonNull protected SuggestionOps suggestionOps = new SuggestionOps(null, null, null, null, null, null, null, null, null);

	@Nullable private Handler shiftStateDebounceHandler;
	@Nullable private Handler suggestionHandler;

	// input
	@NonNull protected ArrayList<Integer> allowedInputModes = new ArrayList<>();
	@NonNull protected InputMode mInputMode = InputMode.getInstance(null, null, null, null, InputMode.MODE_PASSTHROUGH);

	// language
	protected ArrayList<Integer> mEnabledLanguages;
	protected Language mLanguage;

	// predictive-to-manual fallback
	protected boolean inPredictiveFallback = false;


	protected void createSuggestionBar() {
		suggestionOps = new SuggestionOps(this, settings, mainView, appHacks, inputType, textField, statusBar, this::onAcceptSuggestionsDelayed, this::onOK);
	}


	protected boolean shouldBeOff() {
		return getCurrentInputConnection() == null || InputModeKind.isPassthrough(mInputMode);
	}


	protected boolean isInPredictiveFallback() {
		return inPredictiveFallback;
	}


	/**
	 * enterPredictiveFallback
	 * Temporarily switches from predictive to ABC mode for custom word entry.
	 * Does not save the mode change to settings.
	 */
	protected void enterPredictiveFallback(int initialChars) {
		if (inPredictiveFallback || !mLanguage.hasABC()) {
			return;
		}

		inPredictiveFallback = true;

		mInputMode = InputMode.getInstance(settings, mLanguage, inputType, textField, InputMode.MODE_ABC);
		determineTextCase();

		getDisplayTextCase(mLanguage, mInputMode.getTextCase());
		setStatusIcon(mInputMode, mLanguage);
		statusBar.setText(mInputMode);
		mainView.render();
	}


	/**
	 * exitPredictiveFallback
	 * Restores predictive mode after a temporary ABC fallback.
	 */
	protected void exitPredictiveFallback() {
		if (!inPredictiveFallback) {
			return;
		}

		inPredictiveFallback = false;

		mInputMode = InputMode.getInstance(settings, mLanguage, inputType, textField, InputMode.MODE_PREDICTIVE);
		determineTextCase();

		getDisplayTextCase(mLanguage, mInputMode.getTextCase());
		setStatusIcon(mInputMode, mLanguage);
		statusBar.setText(mInputMode);
		mainView.render();
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

		if (shouldBeOff()) {
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

		return
			Key.setHandled(KeyEvent.KEYCODE_ENTER, Key.isOK(keyCode) && onOK())
			|| handleHotkey(keyCode, true, false, true)
			|| handleHotkey(keyCode, false, keyRepeatCounter + 1 > 0, true)
			|| Key.isPoundOrStar(keyCode) && onText(String.valueOf((char) event.getUnicodeChar()), true)
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

		if (handleHotkey(keyCode, true, false, false)) {
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

		if (shouldBeOff()) {
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

		return
			(Key.isOK(keyCode) && Key.isHandled(KeyEvent.KEYCODE_ENTER))
			|| handleHotkey(keyCode, false, keyRepeatCounter > 0, false)
			|| Key.isPoundOrStar(keyCode) && onText(String.valueOf((char) event.getUnicodeChar()), false)
			|| super.onKeyUp(keyCode, event);
	}


	private boolean handleHotkey(int keyCode, boolean hold, boolean repeat, boolean validateOnly) {
		return onHotkey(keyCode * (hold ? -1 : 1), repeat, validateOnly);
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
		if (restart && !languageChanged && mInputMode.getId() == determineInputModeId()) {
			return false;
		}
		inPredictiveFallback = false;
		settings.setDefaultCharOrder(mLanguage, false);
		resetKeyRepeat();
		mInputMode = determineInputMode();
		determineTextCase();
		suggestionOps.set(null);

		// don't use surroundingText cache on start up
		final String[] surroundingText = textField.getSurroundingStringForAutoAssistance(settings, mInputMode);
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
		suggestionOps.setDependencies(appHacks, inputType, textField, statusBar);
	}


	protected void validateLanguages() {
		mEnabledLanguages = InputModeValidator.validateEnabledLanguages(mEnabledLanguages);
		mLanguage = InputModeValidator.validateLanguage(mLanguage, mEnabledLanguages);
		settings.saveInputLanguage(mLanguage.getId());
		settings.saveEnabledLanguageIds(mEnabledLanguages);
	}


	protected void onFinishTyping() {
		inPredictiveFallback = false;
		if (shiftStateDebounceHandler != null) {
			shiftStateDebounceHandler.removeCallbacksAndMessages(null);
			shiftStateDebounceHandler = null;
		}
		if (suggestionHandler != null) {
			suggestionHandler.removeCallbacksAndMessages(null);
			suggestionHandler = null;
		}
		suggestionOps.cancelDelayedAccept();
		mInputMode = InputMode.getInstance(null, null, null, null, InputMode.MODE_PASSTHROUGH);
		setInputField(null);
	}


	@Override
	public boolean onBackspace(int repeat) {
		// Dialer fields seem to handle backspace on their own and we must ignore it,
		// otherwise, keyDown race condition occur for all keys.
		if (InputModeKind.isPassthrough(mInputMode)) {
			return false;
		}

		if (appHacks.onBackspace(settings, mInputMode)) {
			mInputMode.reset();
			mainView.renderDynamicKeys();
			return false;
		}

		suggestionOps.cancelDelayedAccept();
		resetKeyRepeat();

		if (settings.getBackspaceAcceleration() && repeat > 0 && repeat % SettingsStore.BACKSPACE_ACCELERATION_REPEAT_DEBOUNCE != 0) {
			return true;
		}

		// Track whether the user is mid-letter-selection (ABC mode) before backspace modifies state
		boolean wasMidLetterSelection = inPredictiveFallback && mInputMode.isTyping();

		mInputMode.beforeDeleteText();

		// load new words only if there is no selected text, because it would be confusing
		if (repeat == 0 && mInputMode.onBackspace() && textSelection.isEmpty()) {
			final Runnable onLoad = InputModeKind.isRecomposing(mInputMode) ? null : () -> recompose(repeat, false);
			getSuggestions(0, null, onLoad);
		} else {
			suggestionOps.commitCurrent(false, true);
			mInputMode.reset();
			deleteText(settings.getBackspaceAcceleration() && repeat > 0);
			updateShiftStateDebounced(null, mInputMode.noSuggestions(), false); // backspace may change the text too much, so no beforeCursor cache for now
			recompose(repeat, !textSelection.isEmpty());
		}

		// In predictive fallback (ABC mode): exit when the cursor is at the start of the field
		// or immediately after a space — meaning the entire custom word has been erased.
		if (inPredictiveFallback && !wasMidLetterSelection) {
			String beforeCursor = textField.getStringBeforeCursor(1);
			if (beforeCursor.isEmpty() || beforeCursor.equals(Characters.getSpace(mLanguage))) {
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

		// Key 0 is pure Space in all non-numeric modes. No character cycling, no suggestions —
		// onText commits any in-progress word, types the language-appropriate space character,
		// and resets the mode, so the suggestion bar falls back to showing the status text.
		if (key == 0 && !hold && !InputModeKind.isNumeric(mInputMode)) {
			onText(Characters.getSpace(mLanguage), false);
			resetStatus();
			mainView.renderDynamicKeys();
			return true;
		}

		String[] surroundingChars = textField.getSurroundingStringForAutoAssistance(settings, mInputMode);
		String lastWord = null;

		// Automatically accept the previous word, when the next one is a space or punctuation,
		// instead of requiring "OK" before that.
		// First pass, analyze the incoming key press and decide whether it could be the start of
		// a new word. In case we do accept it, we preserve the suggestion list instead of clearing,
		// to prevent flashing while the next suggestions are being loaded.

		// In ABC fallback mode, key 0 (space) must commit the in-progress letter before
		// the mode resets it. ModeABC's 3-arg shouldAcceptPreviousSuggestion returns false,
		// so we handle it explicitly here.
		if (inPredictiveFallback && key == 0 && !suggestionOps.isEmpty()) {
			lastWord = suggestionOps.acceptIncompleteAndKeepList();
			mInputMode.onAcceptSuggestion(lastWord);
			surroundingChars = autoCorrectSpace(lastWord, surroundingChars, false, key);
		} else if (mInputMode.shouldAcceptPreviousSuggestion(suggestionOps.getCurrent(), key, hold)) {
			// WARNING! Ensure the code after "acceptIncompleteAndKeepList()" does not depend on
			// the suggestions in SuggestionOps, since we don't clear that list.
			lastWord = suggestionOps.acceptIncompleteAndKeepList();
			mInputMode.onAcceptSuggestion(lastWord);
			surroundingChars = autoCorrectSpace(lastWord, surroundingChars, false, key);
		}

		// Auto-add unknown words to dictionary when spacebar (key 0) finishes a word
		if (key == 0 && surroundingChars[0] != null && !surroundingChars[0].isEmpty()) {
			String space = Characters.getSpace(mLanguage);
			int spaceIdx = surroundingChars[0].lastIndexOf(space);
			String finishedWord = spaceIdx >= 0 ? surroundingChars[0].substring(spaceIdx + space.length()) : surroundingChars[0];
			if (!finishedWord.isEmpty()) {
				DataStore.putSilently(mLanguage, finishedWord);
			}
			
			// Space finishes the current word in fallback mode, restoring predictive
			if (inPredictiveFallback) {
				exitPredictiveFallback();
			}
		}

		// Auto-adjust the text case before each word/char, if the InputMode supports it.
		mInputMode.determineNextWordTextCase(surroundingChars[0], key);

		if (!mInputMode.onNumber(key, hold, repeat, surroundingChars)) {
			forceShowWindow();
			return false;
		}

		if (mInputMode.shouldSelectNextSuggestion() && !mInputMode.noSuggestions()) {
			scrollSuggestions(false);
			suggestionOps.scheduleDelayedAccept(mInputMode.getAutoAcceptTimeout());
		} else {
			getSuggestions(Math.random(), null, null);
		}

		return true;
	}


	public boolean onText(String text, boolean validateOnly) {
		if (mInputMode.shouldIgnoreText(text)) {
			return false;
		}

		if (validateOnly) {
			return true;
		}

		suggestionOps.cancelDelayedAccept();

		String[] surroundingChars;

		// accept the previously typed word (if any)
		String lastWord = suggestionOps.acceptIncomplete();
		if (lastWord.isEmpty()) {
			surroundingChars = textField.getSurroundingStringForAutoAssistance(settings, mInputMode);
		} else {
			mInputMode.onAcceptSuggestion(lastWord);
			surroundingChars = autoCorrectSpace(
				lastWord,
				textField.getSurroundingStringForAutoAssistance(settings, mInputMode),
				false,
				-1
			);
		}

		// "type" and accept the new word
		mInputMode.onAcceptSuggestion(text);
		textField.setText(text);
		surroundingChars[0] += text;
		surroundingChars = autoCorrectSpace(text, surroundingChars, true, -1);

		if (surroundingChars[0].endsWith(Characters.getSpace(mLanguage))) {
			waitForSpaceTrimKey();
		}

		forceShowWindow();

		mInputMode.determineNextWordTextCase(surroundingChars[0], -1);
		updateShiftState(surroundingChars[0], false, false);

		return true;
	}


	@NonNull
	protected String[] autoCorrectSpace(@Nullable String currentWord, @NonNull String[] surroundingChars, boolean isWordAcceptedManually, int nextKey) {
		if (currentWord == null || currentWord.isEmpty() || !settings.isAutoAssistanceOn(mInputMode)) {
			return surroundingChars;
		}

		String previousChars = surroundingChars[0];
		final String nextChars = surroundingChars[1];

		if (!inputType.isRustDesk() && mInputMode.shouldDeletePrecedingSpace(previousChars)) {
			textField.deletePrecedingSpace(currentWord);
			if (previousChars.endsWith(" " + currentWord) && previousChars.length() > currentWord.length()) {
				final int precedingSpace = previousChars.length() - currentWord.length() - 1;
				previousChars = previousChars.substring(0, precedingSpace) + currentWord;
			}
		}

		if (mInputMode.shouldAddPrecedingSpace(previousChars)) {
			textField.addPrecedingSpace(currentWord);
			if (previousChars.endsWith(currentWord)) {
				final int startOfWord = previousChars.length() - currentWord.length();
				previousChars = previousChars.substring(0, startOfWord) + " " + currentWord;
			}
		}

		if (mInputMode.shouldAddTrailingSpace(previousChars, nextChars, isWordAcceptedManually, nextKey)) {
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

		textField.deleteChars(mLanguage, charsToDelete);
	}


	/**
	 * determineLanguage
	 * Restore the last language or auto-select a more appropriate one, if the application hints so.
	 * In case the settings are not valid, we will fallback to the default language.
	 */
	private boolean determineLanguage() {
		mEnabledLanguages = settings.getEnabledLanguageIds();

		int oldLang = mLanguage != null ? mLanguage.getId() : -1;
		mLanguage = LanguageCollection.getLanguage(settings.getInputLanguage());
		validateLanguages();

		Language appLanguage = textField.getLanguage(mEnabledLanguages);
		if (appLanguage != null) {
			mLanguage = appLanguage;
		}

		return oldLang != mLanguage.getId();
	}


	/**
	 * determineTextCase
	 * Restore the last used text case or auto-select a new one based on the input field properties.
	 */
	protected void determineTextCase() {
		InputModeValidator.validateTextCase(mInputMode, settings.getTextCase());
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

		allowedInputModes = new ArrayList<>(inputType.determineInputModes(getApplicationContext()));
		if (LanguageKind.isJapanese(mLanguage)) {
			determineJapaneseInputModes();
		}

		if (!mLanguage.hasABC()) {
			allowedInputModes.remove((Integer) InputMode.MODE_ABC);
		}

		if (!settings.getPredictiveMode()) {
			allowedInputModes.remove((Integer) InputMode.MODE_PREDICTIVE);
		}

		return InputModeValidator.validateMode(settings.getInputMode(), allowedInputModes);
	}


	/**
	 * In Japanese, Hiragana and Katakana modes are the equivalents of ABC mode in other languages.
	 * So when typing letters is possible (ABC mode allowed), we replace ABC with these two modes.
	 */
	private void determineJapaneseInputModes() {
		if (allowedInputModes.contains(InputMode.MODE_ABC)) {
			allowedInputModes.add(InputMode.MODE_HIRAGANA);
			allowedInputModes.add(InputMode.MODE_KATAKANA);
		}
	}


	/**
	 * determineInputMode
	 * Same as determineInputModeId(), but returns an actual InputMode.
	 */
	protected InputMode determineInputMode() {
		return InputMode.getInstance(settings, mLanguage, inputType, textField, determineInputModeId());
	}


	/**
	 * Try to recompose the current word after a backspace operation. If successful, load new
	 * suggestions. Otherwise, reset the InputMode.
	 */
	private void recompose(int backspaceRepeat, boolean isTextSelected) {
		if (!settings.getBackspaceRecomposing() || backspaceRepeat > 0 || isFnPanelVisible() || isTextSelected || !suggestionOps.isEmpty() || DictionaryLoader.getInstance(this).isRunning()) {
			return;
		}

		final String previousWord = mInputMode.recompose();
		if (textField.recompose(previousWord)) {
			getSuggestions(0, previousWord, null);
		} else {
			mInputMode.reset();
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
			if (!appHacks.acceptComposingTextOnCursorReset(mInputMode, suggestionOps, textField)) {
				suggestionOps.clear();
			}
			return;
		}

		// If the cursor moves while composing a word (usually, because the user has touched the screen outside the word), we must
		// end typing end accept the word. Otherwise, the cursor would jump back at the end of the word, after the next key press.
		// This is confusing from user perspective, so we want to avoid it.
		if (CursorOps.isMovedWhileTyping(newSelStart, newSelEnd, candidatesStart, candidatesEnd)) {
			stopWaitingForSpaceTrimKey();
			inPredictiveFallback = false;
			mInputMode.onCursorMove(suggestionOps.acceptIncomplete());
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
		mInputMode.setWordStem(suggestionOps.getCurrent(), true);
		if (InputModeKind.isRecomposing(mInputMode)) {
			appHacks.setComposingTextPartsWithHighlightedJoining(mInputMode.getWordStem() + suggestionOps.getCurrent(), mInputMode.getRecomposingSuffix());
		} else {
			appHacks.setComposingTextWithHighlightedStem(suggestionOps.getCurrent(), mInputMode.getWordStem(), mInputMode.isStemFilterFuzzy());
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
			mInputMode.determineNextWordTextCase(beforeCursor, -1);
		}

		getDisplayTextCase(mLanguage, mInputMode.getTextCase());
		setStatusIcon(mInputMode, mLanguage);
		mainView.renderDynamicKeys();
		if (!mainView.isTextEditingPaletteShown() && !mainView.isCommandPaletteShown()) {
			statusBar.setText(mInputMode);
		}
	}


	/********** Suggestions pipeline (formerly SuggestionHandler) **********/

	private Handler getAsyncSuggestionHandler() {
		if (suggestionHandler == null) {
			suggestionHandler = new Handler(Looper.getMainLooper());
		}
		return suggestionHandler;
	}


	private String[] onAcceptPreviousSuggestion() {
		final int lastWordLength = InputModeKind.isABC(mInputMode) ? 1 : mInputMode.getSequenceLength() - 1;
		String lastWord = suggestionOps.getCurrent(mLanguage, lastWordLength);
		if (Characters.PLACEHOLDER.equals(lastWord)) {
			lastWord = "";
		}

		suggestionOps.commitCurrent(false, true);
		mInputMode.onAcceptSuggestion(lastWord, true);
		final String[] surroundingText = autoCorrectSpace(
			lastWord,
			textField.getSurroundingStringForAutoAssistance(settings, mInputMode),
			false,
			mInputMode.getFirstKey()
		);
		mInputMode.determineNextWordTextCase(surroundingText[0], -1);

		return surroundingText;
	}


	protected void onAcceptSuggestionsDelayed(String word) {
		onAcceptSuggestionManually(word, -1);
		forceShowWindow();
	}


	protected void onAcceptSuggestionManually(String word, int fromKey) {
		mInputMode.onAcceptSuggestion(word);
		if (Clipboard.contains(word)) {
			Clipboard.copy(this, word);
		}

		if (!word.isEmpty()) {
			String[] surroundingText = autoCorrectSpace(
				word,
				textField.getSurroundingStringForAutoAssistance(settings, mInputMode),
				true,
				fromKey
			);

			mInputMode.determineNextWordTextCase(surroundingText[0], -1);
			updateShiftState(surroundingText[0], false, false);
			resetKeyRepeat();
		}

		if (!Characters.getSpace(mLanguage).equals(word)) {
			waitForSpaceTrimKey();
		}

		// In fallback mode: exit when a space is accepted
		if (isInPredictiveFallback() && Characters.getSpace(mLanguage).equals(word)) {
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
		if (InputModeKind.isPredictive(mInputMode) && DictionaryLoader.getInstance(this).isRunning()) {
			mInputMode.reset();
			UI.toastShortSingle(this, R.string.dictionary_loading_please_wait);
			if (onComplete != null) {
				onComplete.run();
			}
		} else {
			mInputMode
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
		if (mInputMode.shouldAcceptPreviousSuggestion(suggestionOps.getCurrent())) {
			surroundingText = onAcceptPreviousSuggestion();
		}

		final ArrayList<String> suggestions = mInputMode.getSuggestions();
		suggestionOps.set(suggestions, mInputMode.getRecommendedSuggestionIdx(), mInputMode.containsGeneratedSuggestions());

		// Predictive fallback: when no dictionary words match, commit everything except the
		// last key press, switch to ABC mode, and replay the last key so it shows ABC letters.
		if (!isInPredictiveFallback() && InputModeKind.isPredictive(mInputMode) && mInputMode.shouldFallbackToManual()) {
			int lastKey = mInputMode.getLastKey();
			int seqLen = mInputMode.getSequenceLength();

			String wordBeforeLastKey = seqLen > 1
				? suggestionOps.getCurrent(mLanguage, seqLen - 1)
				: "";
			if (!wordBeforeLastKey.isEmpty()) {
				appHacks.setComposingText(wordBeforeLastKey);
			}
			textField.finishComposingText();
			mInputMode.onAcceptSuggestion(wordBeforeLastKey);
			suggestionOps.set(null);

			enterPredictiveFallback(wordBeforeLastKey.length());
			if (lastKey >= 0) {
				String[] surroundingChars = textField.getSurroundingStringForAutoAssistance(settings, mInputMode);
				mInputMode.onNumber(lastKey, false, 0, surroundingChars);
				getSuggestions(0, null, null);
			}
			return;
		}

		// either accept the first one automatically (when switching from punctuation to text
		// or vice versa), or schedule auto-accept in N seconds (in ABC mode)
		if (suggestionOps.scheduleDelayedAccept(mInputMode.getAutoAcceptTimeout())) {
			if (onComplete != null) {
				onComplete.run();
			}
			return;
		}

		// We have not accepted anything yet, which means the user is composing a word.
		// put the first suggestion in the text field, but cut it off to the length of the sequence
		// (the count of key presses), for a more intuitive experience.
		String trimmedWord;

		if (InputModeKind.isRecomposing(mInputMode)) {
			trimmedWord = mInputMode.getWordStem() + suggestionOps.getCurrent();
			appHacks.setComposingTextPartsWithHighlightedJoining(trimmedWord, mInputMode.getRecomposingSuffix());
		} else {
			trimmedWord = suggestionOps.getCurrent(mLanguage, mInputMode.getSequenceLength());
			appHacks.setComposingTextWithHighlightedStem(trimmedWord, mInputMode.getWordStem(), mInputMode.isStemFilterFuzzy());
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

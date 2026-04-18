package io.github.sspanak.tt9.ime;

import android.Manifest;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedList;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.commands.CmdAddWord;
import io.github.sspanak.tt9.commands.CmdEditWord;
import io.github.sspanak.tt9.commands.CmdMoveCursor;
import io.github.sspanak.tt9.commands.CommandCollection;
import io.github.sspanak.tt9.db.words.DictionaryLoader;
import io.github.sspanak.tt9.ime.helpers.InputConnectionAsync;
import io.github.sspanak.tt9.ime.helpers.Key;
import io.github.sspanak.tt9.ime.helpers.OrientationListener;
import io.github.sspanak.tt9.ime.helpers.TextField;
import io.github.sspanak.tt9.ime.helpers.TextSelection;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.ime.modes.ModeRecomposing;
import io.github.sspanak.tt9.ime.modes.helpers.AutoTextCase;
import io.github.sspanak.tt9.ime.modes.helpers.Sequences;
import io.github.sspanak.tt9.ime.voice.VoiceInputError;
import io.github.sspanak.tt9.ime.voice.VoiceInputOps;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.UI;
import io.github.sspanak.tt9.ui.dialogs.RequestPermissionDialog;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.Ternary;
import io.github.sspanak.tt9.util.chars.Characters;
import io.github.sspanak.tt9.util.chars.Emoji;
import io.github.sspanak.tt9.util.sys.Clipboard;
import io.github.sspanak.tt9.util.sys.DeviceInfo;
import io.github.sspanak.tt9.util.sys.SystemSettings;

/**
 * Dispatches everything that isn't plain digit-key typing: hotkeys, command palette, emoji mode,
 * text-editing palette, voice input, OK/Back semantics, language and mode switching, UI queries.
 *
 * Formed by merging the former VoiceHandler, TextEditingHandler, HotkeyHandler, and MainViewHandler
 * layers plus the original CommandHandler into a single class.
 */
public abstract class CommandHandler extends TypingHandler {
	private final static String LOG_TAG = "CommandHandler";

	protected VoiceInputOps voiceInputOps;
	private AutoTextCase autoTextCase;
	private String beforeSpeech = "";

	protected boolean isLanguageRTL;

	protected boolean inEmojiMode = false;
	private int emojiCategoryIndex = 0;

	private boolean waitingForSpaceTrim = false;

	// Main view / orientation
	private OrientationListener orientationListener;
	private float normalizedWidth = -1;
	private float normalizedHeight = -1;
	private int width = 0;


	/********** Lifecycle **********/

	@Override
	protected void onInit() {
		super.onInit();

		voiceInputOps = new VoiceInputOps(
			this,
			this::onVoiceInputStarted,
			this::onVoiceInputStopped,
			this::onVoiceInputPartial,
			this::onVoiceInputError
		);

		if (settings.areHotkeysInitialized()) {
			settings.setDefaultKeys();
		}

		if (orientationListener == null) {
			orientationListener = new OrientationListener(this, this::onOrientationChanged);
			orientationListener.start();
		}
	}


	@Override
	protected boolean onStart(EditorInfo field, boolean restarting) {
		detectRTL();
		suggestionOps.setLanguage(LanguageCollection.getLanguage(settings.getInputLanguage()));
		resetNormalizedDimensions();
		return super.onStart(field, restarting);
	}


	@Override
	protected void cleanUp() {
		super.cleanUp();
		if (orientationListener != null) {
			orientationListener.stop();
			orientationListener = null;
		}
	}


	/********** Hardware-key decorators **********/

	@Override
	public boolean onBackspace(int repeat) {
		if (inEmojiMode) {
			exitEmojiMode();
			return true;
		}
		return super.onBackspace(repeat);
	}


	@Override
	public boolean onOK() {
		suggestionOps.cancelDelayedAccept();
		stopWaitingForSpaceTrimKey();

		if (isInEmojiMode()) {
			onEmojiSelected();
			return true;
		}

		if (!suggestionOps.isEmpty()) {
			boolean shouldEnterFallback = false;
			String acceptedWord = "";

			if (mInputMode.shouldReplacePreviousSuggestion(suggestionOps.getCurrent())) {
				mInputMode.onReplaceSuggestion(suggestionOps.getCurrentRaw());
			} else if (InputModeKind.isRecomposing(mInputMode)) {
				onAcceptSuggestionManually(suggestionOps.acceptEdited(), KeyEvent.KEYCODE_ENTER);
			} else {
				shouldEnterFallback = InputModeKind.isPredictive(mInputMode) && !isInPredictiveFallback();
				acceptedWord = suggestionOps.acceptCurrent();
				onAcceptSuggestionManually(acceptedWord, KeyEvent.KEYCODE_ENTER);
			}

			if (shouldEnterFallback) {
				enterPredictiveFallback(acceptedWord.length());
			}
			return true;
		}

		int action = textField.getAction();
		boolean actionPerformed;

		if (action == TextField.IME_ACTION_ENTER) {
			actionPerformed = appHacks.onEnter();
			if (actionPerformed) {
				forceShowWindow();
			}
			updateShiftState(null, true, false);
			return actionPerformed;
		}

		actionPerformed = appHacks.onAction(action) || textField.performAction(action);
		updateShiftState(null, true, false);
		return actionPerformed;
	}


	@Override
	protected boolean onNumber(int key, boolean hold, int repeat) {
		// Stop voice input on any number key
		stopVoiceInput();
		stopWaitingForSpaceTrimKey();

		// Hold a number key = hotkey lookup
		if (hold && onHotkey(-Key.numberToCode(key), false, false)) {
			return true;
		}

		// Emoji mode exit
		if (inEmojiMode) {
			exitEmojiMode();
		}

		return super.onNumber(key, hold, repeat);
	}


	@Override
	public boolean onHotkey(int keyCode, boolean repeat, boolean validateOnly) {
		// Voice-input and recomposing-aware handling for star/pound
		if (voiceInputOps != null && voiceInputOps.isListening()) {
			switch (keyCode) {
				case KeyEvent.KEYCODE_STAR:
					if (validateOnly) return true;
					return navigateBack();
				case KeyEvent.KEYCODE_POUND:
					return isFnPanelVisible();
			}
		}

		if (keyCode == KeyEvent.KEYCODE_UNKNOWN || (keyCode < 0 && Key.isNumber(-keyCode) && !settings.getHoldToType())) {
			return false;
		}

		return onHardcodedKey(keyCode, validateOnly) || onDynamicKey(keyCode, repeat, validateOnly);
	}


	@Override
	public Ternary onBack() {
		waitingForSpaceTrim = false;

		// voice input: just stop, don't abort anything else
		if (voiceInputOps != null && voiceInputOps.isListening()) {
			stopVoiceInput();
			return Ternary.FALSE;
		}

		if (hideCommandPalette()) {
			return Ternary.TRUE;
		}

		if (navigateBack()) {
			return Ternary.TRUE;
		}

		return settings.isMainLayoutLarge() ? Ternary.ALTERNATIVE : Ternary.FALSE;
	}


	private boolean onHardcodedKey(int keyCode, boolean validateOnly) {
		if (Key.isArrowUp(keyCode) && onKeyEditDuplicateLetter(validateOnly)) {
			return true;
		}

		if (Key.isArrowLeft(-keyCode) || Key.isArrowRight(-keyCode)) {
			if (onKeyEditAdjacentLetter(validateOnly, -keyCode)) {
				return true;
			}
		}

		if (Key.isArrowLeft(keyCode) && onTrimTrailingSpace(validateOnly)) {
			return true;
		}

		return false;
	}


	private boolean onDynamicKey(int keyCode, boolean repeat, boolean validateOnly) {
		if (keyCode == settings.getKeyAddWord()) return onKeyAddWord(validateOnly);
		if (keyCode == settings.getKeyCommandPalette()) return onKeyEmoji(validateOnly);
		if (keyCode == settings.getKeyEditText()) return onKeyEditText(validateOnly);
		if (keyCode == settings.getKeyEditWord()) return onKeyEditWord(validateOnly);
		if (keyCode == settings.getKeyFilterClear()) return onKeyFilterClear(validateOnly);
		if (keyCode == settings.getKeyFilterSuggestions()) return onKeyFilterSuggestions(validateOnly, repeat);
		if (keyCode == settings.getKeyNextLanguage()) return onKeyNextLanguage(validateOnly);
		if (keyCode == settings.getKeyNextInputMode()) return onKeyNextInputMode(validateOnly);
		if (keyCode == settings.getKeyPreviousSuggestion()) return onKeyScrollSuggestion(validateOnly, true);
		if (keyCode == settings.getKeyNextSuggestion()) return onKeyScrollSuggestion(validateOnly, false);
		if (keyCode == settings.getKeySelectKeyboard()) return onKeySelectKeyboard(validateOnly);
		if (keyCode == settings.getKeyShift()) {
			return onKeyNextTextCase(validateOnly)
				|| (keyCode == settings.getKeySpaceKorean() && onKeySpaceKorean(validateOnly));
		}
		if (keyCode == settings.getKeySpaceKorean()) return onKeySpaceKorean(validateOnly);
		if (keyCode == settings.getKeyShowSettings()) return onKeyShowSettings(validateOnly);
		if (keyCode == settings.getKeyUndo()) return onKeyUndo(validateOnly);
		if (keyCode == settings.getKeyRedo()) return onKeyRedo(validateOnly);
		if (keyCode == settings.getKeyVoiceInput()) return onKeyVoiceInput(validateOnly);
		return false;
	}


	protected boolean isHoldHotkey(int keyCode) {
		return
			keyCode < 0
			&& (
				Key.isHotkey(settings, -keyCode)
				|| (Key.isArrowLeft(-keyCode) && InputModeKind.isRecomposing(mInputMode))
				|| (Key.isArrowRight(-keyCode) && InputModeKind.isRecomposing(mInputMode))
			);
	}


	/********** Hotkey handlers (dynamic) **********/

	private boolean onKeyAddWord(boolean validateOnly) {
		if (!isInputViewShown() || shouldBeOff()) return false;
		if (!validateOnly) addWord();
		return true;
	}


	public boolean onKeyCommandPalette(boolean validateOnly) {
		if (shouldBeOff()) return false;
		return validateOnly;
	}


	private boolean onKeyEmoji(boolean validateOnly) {
		if (shouldBeOff()) return false;
		if (validateOnly) return true;
		if (isInEmojiMode()) {
			nextEmojiCategory();
		} else {
			enterEmojiMode();
		}
		forceShowWindow();
		return true;
	}


	private boolean onKeyEditAdjacentLetter(boolean validateOnly, int keyCode) {
		if (shouldBeOff() || !InputModeKind.isRecomposing(mInputMode)) return false;
		if (!validateOnly) ((ModeRecomposing) mInputMode).skipLetter(Key.isArrowLeft(keyCode));
		return true;
	}


	private boolean onKeyEditDuplicateLetter(boolean validateOnly) {
		if (shouldBeOff() || !InputModeKind.isRecomposing(mInputMode)) return false;
		if (!validateOnly) ((ModeRecomposing) mInputMode).duplicateLetter();
		return true;
	}


	private boolean onKeyEditText(boolean validateOnly) {
		if (!isInputViewShown() || shouldBeOff()) return false;
		if (!validateOnly && !hideTextEditingPalette()) {
			showTextEditingPalette();
			forceShowWindow();
		}
		return true;
	}


	public boolean onKeyEditWord(boolean validateOnly) {
		if (shouldBeOff()) return false;
		if (!validateOnly) {
			forceShowWindow();
			editWord();
		}
		return true;
	}


	public boolean onKeyMoveCursor(int direction) {
		suggestionOps.cancelDelayedAccept();
		mInputMode.onAcceptSuggestion(suggestionOps.acceptIncomplete());
		resetKeyRepeat();

		final boolean backward = direction == CmdMoveCursor.CURSOR_MOVE_LEFT;

		if (textSelection.isEmpty()) {
			return
				appHacks.onMoveCursor(direction)
				|| (backward && onTrimTrailingSpace(false))
				|| textField.moveCursor(direction);
		} else {
			textSelection.clear(backward);
			return true;
		}
	}


	public boolean onKeyFilterClear(boolean validateOnly) {
		if (suggestionOps.isEmpty()) return false;
		if (validateOnly) return true;

		suggestionOps.cancelDelayedAccept();

		int stemLength = mInputMode.getWordStem().length();
		boolean isFilteringOn = mInputMode.isStemFilterFuzzy() || (stemLength > 0 && mInputMode.getSequenceLength() != stemLength);

		if (mInputMode.clearWordStem() && isFilteringOn) {
			mInputMode
				.setOnSuggestionsUpdated(this::handleSuggestionsAsync)
				.loadSuggestions(suggestionOps.getCurrent(mLanguage, mInputMode.getSequenceLength()));
			return true;
		}

		mInputMode.onAcceptSuggestion(suggestionOps.acceptIncomplete());
		resetKeyRepeat();
		return true;
	}


	public boolean onKeyFilterSuggestions(boolean validateOnly, boolean repeat) {
		if (suggestionOps.isEmpty()) return false;

		if (!mInputMode.supportsFiltering()) {
			UI.toastShortSingle(this, R.string.function_filter_suggestions_not_available);
			return true;
		}

		if (validateOnly) return true;

		suggestionOps.cancelDelayedAccept();

		String filter;
		if (repeat && !suggestionOps.get(1).isEmpty()) {
			filter = suggestionOps.get(1);
		} else {
			filter = suggestionOps.getCurrent(mLanguage, mInputMode.getSequenceLength());
		}

		if (filter.isEmpty()) {
			mInputMode.reset();
		} else if (mInputMode.setWordStem(filter, repeat)) {
			mInputMode
				.setOnSuggestionsUpdated(this::handleSuggestionsAsync)
				.loadSuggestions(filter);
		}

		return true;
	}


	public boolean onKeyScrollSuggestion(boolean validateOnly, boolean backward) {
		if (suggestionOps.isEmpty()) return false;
		if (validateOnly) return true;

		backward = isLanguageRTL != backward;
		scrollSuggestions(backward);
		return true;
	}


	public boolean onKeyNextLanguage(boolean validateOnly) {
		if (InputModeKind.isNumeric(mInputMode) || mEnabledLanguages.size() < 2) return false;
		if (validateOnly) return true;
		if (settings.getQuickSwitchLanguage() || !changeLang()) nextLang();
		return true;
	}


	public boolean onKeyNextInputMode(boolean validateOnly) {
		if (allowedInputModes.size() == 1) return false;
		if (validateOnly) return true;

		suggestionOps.scheduleDelayedAccept(mInputMode.getAutoAcceptTimeout());
		final int nextModeId = nextInputMode();
		if (nextModeId != mInputMode.getId()) {
			setInputMode(nextModeId);
		}

		forceShowWindow();
		return true;
	}


	public boolean onKeyNextTextCase(boolean validateOnly) {
		if (voiceInputOps.isListening() || inputType.isNumeric() || inputType.isPhoneNumber()) return false;
		if (validateOnly) return true;

		suggestionOps.scheduleDelayedAccept(mInputMode.getAutoAcceptTimeout());
		if (!nextTextCase()) return false;

		getDisplayTextCase(mLanguage, mInputMode.getTextCase());
		setStatusIcon(mInputMode, mLanguage);

		if (settings.isMainLayoutStealth() && !settings.isStatusIconEnabled()) {
			UI.toastShortSingle(this, mInputMode.getClass().getSimpleName(), mInputMode.toString());
		}
		return true;
	}


	private boolean onKeySelectKeyboard(boolean validateOnly) {
		if (!isInputViewShown() || shouldBeOff()) return false;
		if (!validateOnly) selectKeyboard();
		return true;
	}


	private boolean onKeyShowSettings(boolean validateOnly) {
		if (!isInputViewShown() || shouldBeOff()) return false;
		if (!validateOnly) showSettings();
		return true;
	}


	public boolean onKeySpaceKorean(boolean validateOnly) {
		if (shouldBeOff()) return false;

		if (!suggestionOps.isEmpty() && LanguageKind.isCJK(mLanguage)) {
			if (!validateOnly) {
				onAcceptSuggestionManually(suggestionOps.acceptCurrent(), KeyEvent.KEYCODE_ENTER);
			}
			return true;
		}

		return onText(Characters.getSpace(mLanguage), validateOnly);
	}


	public boolean onKeyUndo(boolean validateOnly) {
		if (!isInputViewShown() || shouldBeOff()) return false;
		if (validateOnly) return true;
		suggestionOps.cancelDelayedAccept();
		suggestionOps.acceptCurrent();
		return undo();
	}


	public boolean onKeyRedo(boolean validateOnly) {
		if (!isInputViewShown() || shouldBeOff()) return false;
		if (validateOnly) return true;
		suggestionOps.cancelDelayedAccept();
		suggestionOps.acceptCurrent();
		return redo();
	}


	private boolean onKeyVoiceInput(boolean validateOnly) {
		if (!isInputViewShown() || shouldBeOff() || !voiceInputOps.isAvailable()) return false;
		if (!validateOnly) toggleVoiceInput();
		return true;
	}


	@Override
	protected void waitForSpaceTrimKey() {
		waitingForSpaceTrim = true;
	}


	@Override
	protected void stopWaitingForSpaceTrimKey() {
		waitingForSpaceTrim = false;
	}


	private boolean onTrimTrailingSpace(boolean validateOnly) {
		if (!waitingForSpaceTrim || !settings.getAutoTrimTrailingSpace() || !suggestionOps.isEmpty()) return false;

		String after = textField.getStringAfterCursor(1);
		if (!after.isEmpty() && after.charAt(0) != '\n') {
			stopWaitingForSpaceTrimKey();
			return false;
		}

		String before = textField.getStringBeforeCursor(2);
		if (before.equals(InputConnectionAsync.TIMEOUT_SENTINEL) || before.length() != 2 || Character.isWhitespace(before.charAt(0)) || before.charAt(1) != Characters.getSpace(mLanguage).charAt(0)) {
			stopWaitingForSpaceTrimKey();
			return false;
		}

		if (!validateOnly) {
			textField.deleteChars(mLanguage, 1);
			stopWaitingForSpaceTrimKey();
		}

		return true;
	}


	/********** Text-editing palette **********/

	protected void detectRTL() {
		isLanguageRTL = LanguageKind.isRTL(LanguageCollection.getLanguage(settings.getInputLanguage()));
	}


	private void onTextEditingCommand(int key) {
		if (!suggestionOps.isEmpty() && key != 9) {
			suggestionOps.acceptCurrent();
		}

		if (key == 0) {
			if (!InputModeKind.isNumeric(mInputMode)) {
				onText(Characters.getSpace(mLanguage), false);
			}
		} else {
			CommandCollection.getByHardKey(CommandCollection.COLLECTION_TEXT_EDITING, key).run(getFinalContext());
		}
	}


	protected boolean navigateBack() {
		// voice stop takes precedence
		if (voiceInputOps != null && voiceInputOps.isListening()) {
			stopVoiceInput();
			return true;
		}

		return hideTextEditingPalette();
	}


	public void cut() {
		if (copy()) {
			suggestionOps.clear();
		}
	}


	public boolean copy() {
		CharSequence selectedText = textSelection.getSelectedText();
		if (selectedText.length() == 0) {
			return false;
		}
		Clipboard.copy(this, selectedText);
		return true;
	}


	public void paste() {
		if (!suggestionOps.isEmpty()) {
			suggestionOps.clear();
			return;
		}

		LinkedList<CharSequence> clips = Clipboard.getAll(this);
		if (clips.isEmpty()) {
			UI.toast(this, R.string.commands_clipboard_is_empty);
			return;
		}

		mInputMode.reset();
		suggestionOps.setClipboardItems(clips);
		appHacks.setComposingTextWithHighlightedStem(suggestionOps.getCurrent(), null, false);
	}


	public void showTextEditingPalette() {
		// No-op: there is no UI to show anymore.
	}


	public boolean hideTextEditingPalette() {
		return false;
	}


	/********** Voice input **********/

	public void toggleVoiceInput() {
		if (voiceInputOps.isListening() || !voiceInputOps.isAvailable()) {
			stopVoiceInput();
			return;
		}

		suggestionOps.cancelDelayedAccept();
		mInputMode.onAcceptSuggestion(suggestionOps.acceptIncomplete());
		autoTextCase = new AutoTextCase(settings, new Sequences(), inputType);
		beforeSpeech = textField.getStringBeforeCursor();
		voiceInputOps.listen(mLanguage);
	}


	protected void stopVoiceInput() {
		if (voiceInputOps != null && voiceInputOps.isListening()) {
			voiceInputOps.stop();
		}
	}


	private void onVoiceInputStarted() {}


	private String autoCapitalize(String str) {
		if (autoTextCase == null || !settings.isAutoTextCaseOn(mInputMode)) {
			return str;
		}
		return autoTextCase.adjustParagraphTextCase(mLanguage, str, beforeSpeech, mInputMode.getTextCase(), inputType.determineTextCase());
	}


	private void onVoiceInputStopped(String text) {
		onText(autoCapitalize(text), false);
		resetStatus();
	}


	private void onVoiceInputPartial(String text) {
		textField.setComposingText(autoCapitalize(text), 1);
	}


	private void onVoiceInputError(VoiceInputError error) {
		if (error.isStartTimeout()) {
			Logger.i(LOG_TAG, "Google SpeechRecognizer timed out. Enforcing alternative listening mode for the current session.");
			voiceInputOps.forceAlternativeInput(true).listen(mLanguage);
		} else if (error.isLanguageMissing() && voiceInputOps.enableOfflineMode(mLanguage, false)) {
			Logger.i(LOG_TAG, "Voice input package for language '" + mLanguage.getName() + "' is missing. Enforcing online mode for the current session.");
			voiceInputOps.listen(mLanguage);
		} else if (error.isIrrelevantToUser()) {
			Logger.i(LOG_TAG, "Ignoring voice input. " + error.debugMessage);
			resetStatus();
		} else {
			Logger.e(LOG_TAG, "Failed to listen. " + error.debugMessage);
			if (error.isNoPermission()) {
				RequestPermissionDialog.show(this, Manifest.permission.RECORD_AUDIO);
			}
		}
	}


	/********** Commands (word add/edit, language & mode switching) **********/

	protected void resetStatus() {
		// No-op: the visible status bar has been removed. Callers still invoke this
		// at transition points; a future suggestion bar should hook in here.
	}


	public void addWord() {
		// The add-word dialog has been removed along with the IME UI.
		Logger.d(LOG_TAG, "addWord: dialog removed — no-op");
	}


	protected void editWord() {
		if (!CmdEditWord.validate(getFinalContext(), settings, mLanguage)) return;

		final int previousMode = mInputMode.getId();
		if (previousMode == InputMode.MODE_RECOMPOSING) {
			Logger.d(getClass().getSimpleName(), "Already in recomposing mode. Nothing to do.");
			return;
		}

		String word = suggestionOps.getCurrent(mLanguage, mInputMode.getSequenceLength());
		if (word.isEmpty()) {
			word = textField.recomposeSurroundingWord(mLanguage);
		} else {
			suggestionOps.set(null);
		}

		if (word.isEmpty()) {
			UI.toastShortSingle(this, R.string.edit_word_no_selection);
			return;
		}

		setInputMode(InputMode.MODE_RECOMPOSING);
		if (mInputMode.setWordStem(word, false)) {
			((ModeRecomposing) mInputMode).setOnFinishListener(() -> setInputMode(previousMode));
			getSuggestions(0, "", null);
		} else {
			textField.finishComposingText();
			setInputMode(previousMode);
			UI.toastShortSingle(
				this,
				"edit_word_invalid_characters",
				getString(R.string.edit_word_invalid_characters, word, mLanguage.getName())
			);
		}
	}


	public void selectKeyboard() {
		suggestionOps.cancelDelayedAccept();
		stopVoiceInput();
		UI.showChangeKeyboardDialog(this);
	}


	public void nextKeyboard() {
		suggestionOps.cancelDelayedAccept();
		stopVoiceInput();

		if (DeviceInfo.AT_LEAST_ANDROID_9) {
			switchToPreviousInputMethod();
			return;
		}

		try {
			switchInputMethod(SystemSettings.getPreviousIME(this));
		} catch (Exception e) {
			Logger.d(getClass().getSimpleName(), "Could not switch to previous input method. " + e);
		}
	}


	protected int nextInputMode() {
		if (InputModeKind.isPassthrough(mInputMode) || voiceInputOps.isListening()) {
			return mInputMode.getId();
		}

		if (allowedInputModes.size() == 1 && allowedInputModes.contains(InputMode.MODE_123) && !InputModeKind.is123(mInputMode)) {
			return InputMode.MODE_123;
		} else {
			final int nextModeIndex = (allowedInputModes.indexOf(mInputMode.getId()) + 1) % allowedInputModes.size();
			return allowedInputModes.get(nextModeIndex);
		}
	}


	protected void setInputMode(int modeId) {
		if (!allowedInputModes.contains(modeId) && modeId != InputMode.MODE_RECOMPOSING) return;

		inPredictiveFallback = false;

		suggestionOps.cancelDelayedAccept();
		mInputMode.onAcceptSuggestion(suggestionOps.acceptIncomplete());
		resetKeyRepeat();

		mInputMode = InputMode.getInstance(settings, mLanguage, inputType, textField, modeId);
		determineTextCase();

		if (modeId != InputMode.MODE_RECOMPOSING) {
			settings.saveInputMode(mInputMode.getId());
		}

		getDisplayTextCase(mLanguage, mInputMode.getTextCase());
		setStatusIcon(mInputMode, mLanguage);

		if (settings.isMainLayoutStealth() && !settings.isStatusIconEnabled()) {
			UI.toastShortSingle(this, mInputMode.getClass().getSimpleName(), mInputMode.toString());
		}

		getFinalContext().pushModeInfoToBar();
	}


	protected boolean changeLang() {
		// The change-language dialog has been removed; fall through to nextLang cycling.
		return false;
	}


	protected void nextLang() {
		int previous = mEnabledLanguages.indexOf(mLanguage.getId());
		int next = (previous + 1) % mEnabledLanguages.size();
		setLang(mEnabledLanguages.get(next));
	}


	public void setLang(int langId) {
		if (!mEnabledLanguages.contains(langId)) return;

		inPredictiveFallback = false;

		suggestionOps.cancelDelayedAccept();
		stopVoiceInput();

		mLanguage = LanguageCollection.getLanguage(langId);
		validateLanguages();

		detectRTL();
		settings.setDefaultCharOrder(mLanguage, false);

		mInputMode = InputMode
			.getInstance(settings, mLanguage, inputType, textField, determineInputModeId())
			.copy(mInputMode);

		if (mInputMode.isTyping()) {
			getSuggestions(0, null, this::onAfterLanguageChange);
		} else {
			onAfterLanguageChange();
		}

		if (InputModeKind.isPredictive(mInputMode)) {
			DictionaryLoader.autoLoad(this, settings, mLanguage);
		}

		forceShowWindow();
	}


	private void onAfterLanguageChange() {
		getDisplayTextCase(mLanguage, mInputMode.getTextCase());
		setStatusIcon(mInputMode, mLanguage);
		suggestionOps.setLanguage(mLanguage);
		if (settings.isMainLayoutStealth() && !settings.isStatusIconEnabled()) {
			UI.toastShortSingle(this, mInputMode.getClass().getSimpleName(), mInputMode.toString());
		}
		getFinalContext().pushModeInfoToBar();
	}


	protected boolean nextTextCase() {
		final String currentWord = !suggestionOps.isEmpty() && mInputMode.isTyping() ? suggestionOps.getCurrent() : "";

		if (!mInputMode.nextTextCase(currentWord, displayTextCase)) return false;

		mInputMode.skipNextTextCaseDetection();
		settings.saveTextCase(mInputMode.getTextCase());

		if (currentWord.isEmpty() && !suggestionOps.isEmpty()) {
			suggestionOps.setTextCase(mLanguage, mInputMode.getTextCase());
			appHacks.setComposingText(suggestionOps.getCurrent());
			return true;
		} else if (currentWord.isEmpty() || (currentWord.length() == 1 && !Character.isAlphabetic(currentWord.charAt(0)))) {
			return true;
		}

		int currentSuggestionIndex = suggestionOps.getCurrentIndex();
		currentSuggestionIndex = suggestionOps.containsStem() ? currentSuggestionIndex - 1 : currentSuggestionIndex;

		suggestionOps.set(mInputMode.getSuggestions(), currentSuggestionIndex, mInputMode.containsGeneratedSuggestions());

		if (InputModeKind.isRecomposing(mInputMode)) {
			appHacks.setComposingTextPartsWithHighlightedJoining(mInputMode.getWordStem() + suggestionOps.getCurrent(), mInputMode.getRecomposingSuffix());
		} else {
			appHacks.setComposingText(suggestionOps.getCurrent());
		}

		return true;
	}


	/********** Emoji mode **********/

	public boolean isInEmojiMode() {
		return inEmojiMode;
	}


	public void enterEmojiMode() {
		suggestionOps.cancelDelayedAccept();
		mInputMode.onAcceptSuggestion(suggestionOps.acceptIncomplete());
		inEmojiMode = true;
		emojiCategoryIndex = 0;
		showEmojiCategory();
	}


	public void nextEmojiCategory() {
		emojiCategoryIndex = (emojiCategoryIndex + 1) % Emoji.getMaxEmojiLevel();
		showEmojiCategory();
	}


	private void showEmojiCategory() {
		ArrayList<String> emojis = Emoji.getEmoji(getApplicationContext(), emojiCategoryIndex);
		if (emojis.isEmpty() && emojiCategoryIndex == 0) {
			emojiCategoryIndex = 1;
			emojis = Emoji.getEmoji(getApplicationContext(), emojiCategoryIndex);
		}
		suggestionOps.set(emojis, 0, false);
	}


	public void exitEmojiMode() {
		inEmojiMode = false;
		emojiCategoryIndex = 0;
		suggestionOps.set(null);
		resetStatus();
	}


	public void onEmojiSelected() {
		int currentIndex = suggestionOps.getCurrentIndex();
		String emoji = suggestionOps.getCurrent();
		if (!emoji.isEmpty()) {
			textField.setText(emoji);
			Emoji.recordEmojiUsage(getApplicationContext(), emoji);
		}
		// Recently Used tab: keep list stable during this session.
		if (emojiCategoryIndex == 0) {
			return;
		}
		ArrayList<String> emojis = Emoji.getEmoji(getApplicationContext(), emojiCategoryIndex);
		suggestionOps.set(emojis, currentIndex, false);
	}


	/********** Settings, palette, undo/redo **********/

	public void showSettings() {
		suggestionOps.cancelDelayedAccept();
		stopVoiceInput();
		UI.showSettingsScreen(this, null);
	}


	public void showCommandPalette() {
		// No-op: there is no UI.
	}


	public boolean hideCommandPalette() {
		return false;
	}


	protected boolean undo() {
		return textField.sendDownUpKeyEvents(KeyEvent.KEYCODE_Z, false, true);
	}


	protected boolean redo() {
		return textField.sendDownUpKeyEvents(KeyEvent.KEYCODE_Z, true, true);
	}


	/********** View queries (formerly MainViewHandler) **********/

	private void onOrientationChanged() {
		width = 0;
		resetNormalizedDimensions();
	}


	public boolean isFilteringFuzzy() { return mInputMode.isStemFilterFuzzy(); }
	public boolean isFilteringOn() {
		String stem = mInputMode.getWordStem();
		return stem != null && !stem.isEmpty();
	}
	public boolean isFnPanelVisible() { return false; }
	public boolean isInputLimited() { return inputType.isLimited(); }
	public boolean isInputModeABC() { return InputModeKind.isABC(mInputMode); }
	public boolean isInputModeNumeric() { return InputModeKind.isNumeric(mInputMode); }
	public boolean isInputTypeNumeric() { return inputType.isNumeric(); }
	public boolean isInputTypeDecimal() { return inputType.isDecimal() || inputType.isUnspecifiedNumber(); }
	public boolean isInputTypeSigned() { return inputType.isSignedNumber() || inputType.isUnspecifiedNumber(); }
	public boolean isInputTypePhone() { return inputType.isPhoneNumber(); }
	public boolean isTextEditingActive() { return false; }
	public boolean isVoiceInputActive() { return voiceInputOps != null && voiceInputOps.isListening(); }
	public boolean isVoiceInputMissing() {
		return !(new VoiceInputOps(this, null, null, null, null)).isAvailable();
	}


	public String getABCString() {
		return mLanguage == null ? "ABC" : mLanguage.getAbcString().toUpperCase(mLanguage.getLocale());
	}


	public int getDisplayTextCase() {
		return getDisplayTextCase(mLanguage, mInputMode.getTextCase());
	}


	public InputMode getInputMode() { return mInputMode; }


	@NonNull
	public String getInputModeName() {
		if (InputModeKind.isHiragana(mInputMode)) return "あ";
		if (InputModeKind.isKatakana(mInputMode)) return "ア";
		if (InputModeKind.isPredictive(mInputMode)) {
			return mLanguage != null ? mLanguage.getCode().toUpperCase(mLanguage.getLocale()) : "T9";
		}
		if (InputModeKind.isNumeric(mInputMode)) return "123";
		return getABCString();
	}


	public int getTextCase() { return mInputMode.getTextCase(); }

	@Nullable
	public Language getLanguage() { return mLanguage; }

	public SettingsStore getSettings() { return settings; }

	@Nullable
	public TextSelection getTextSelection() { return textSelection; }


	public int getWidth() {
		if (width == 0) {
			width = DeviceInfo.getScreenWidth(getApplicationContext());
		}
		return width;
	}


	public float getNormalizedWidth() {
		if (normalizedWidth < 0) {
			normalizedWidth = settings.getWidthPercent(!DeviceInfo.isLandscapeOrientation(this)) / 100f;
		}
		return normalizedWidth;
	}


	public float getNormalizedHeight() {
		if (normalizedHeight < 0) {
			normalizedHeight = (float) settings.getNumpadKeyHeight() / (float) settings.getNumpadKeyDefaultHeight();
		}
		return normalizedHeight;
	}


	private void resetNormalizedDimensions() {
		normalizedWidth = -1;
		normalizedHeight = -1;
	}
}

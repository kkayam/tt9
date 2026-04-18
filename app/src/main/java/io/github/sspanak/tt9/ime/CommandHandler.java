package io.github.sspanak.tt9.ime;

import android.Manifest;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.IntSupplier;

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

	@NonNull protected final EmojiMode emojiMode = new EmojiMode(
		this::getApplicationContext,
		() -> suggestionOps,
		() -> textField
	);

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
		if (emojiMode.isActive()) {
			exitEmojiMode();
			return true;
		}
		return super.onBackspace(repeat);
	}


	@Override
	public boolean onOK() {
		suggestionOps.cancelDelayedAccept();
		stopWaitingForSpaceTrimKey();

		if (emojiMode.isActive()) {
			emojiMode.onSelect();
			return true;
		}

		if (!suggestionOps.isEmpty()) {
			boolean shouldEnterFallback = false;
			String acceptedWord = "";

			if (session.mode.shouldReplacePreviousSuggestion(suggestionOps.getCurrent())) {
				session.mode.onReplaceSuggestion(suggestionOps.getCurrentRaw());
			} else if (InputModeKind.isRecomposing(session.mode)) {
				onAcceptSuggestionManually(suggestionOps.acceptEdited(), KeyEvent.KEYCODE_ENTER);
			} else {
				shouldEnterFallback = InputModeKind.isPredictive(session.mode) && !isInPredictiveFallback();
				acceptedWord = suggestionOps.acceptAndClear(true);
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
		if (hold && onHotkey(-Key.numberToCode(key), false).run()) {
			return true;
		}

		// Emoji mode exit
		if (emojiMode.isActive()) {
			exitEmojiMode();
		}

		return super.onNumber(key, hold, repeat);
	}


	@Override
	public KeyIntent onHotkey(int keyCode, boolean repeat) {
		// Voice-input override for star/pound while listening.
		if (voiceInputOps != null && voiceInputOps.isListening()) {
			switch (keyCode) {
				case KeyEvent.KEYCODE_STAR:
					return KeyIntent.accept(this::navigateBack);
				case KeyEvent.KEYCODE_POUND:
					return isFnPanelVisible() ? KeyIntent.ACCEPT_NOOP : KeyIntent.REJECT;
			}
		}

		if (keyCode == KeyEvent.KEYCODE_UNKNOWN || (keyCode < 0 && Key.isNumber(-keyCode) && !settings.getHoldToType())) {
			return KeyIntent.REJECT;
		}

		final KeyIntent hardcoded = onHardcodedKey(keyCode);
		if (hardcoded.accepted()) return hardcoded;
		return onDynamicKey(keyCode, repeat);
	}


	@Override
	public Ternary onBack() {
		session.waitingForSpaceTrim = false;

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


	private KeyIntent onHardcodedKey(int keyCode) {
		if (Key.isArrowUp(keyCode)) {
			final KeyIntent intent = onKeyEditDuplicateLetter();
			if (intent.accepted()) return intent;
		}

		if (Key.isArrowLeft(-keyCode) || Key.isArrowRight(-keyCode)) {
			final KeyIntent intent = onKeyEditAdjacentLetter(-keyCode);
			if (intent.accepted()) return intent;
		}

		if (Key.isArrowLeft(keyCode)) {
			final KeyIntent intent = onTrimTrailingSpace();
			if (intent.accepted()) return intent;
		}

		return KeyIntent.REJECT;
	}


	@FunctionalInterface
	private interface HotkeyAction {
		KeyIntent run(boolean repeat);
	}

	private record DynamicHotkey(@NonNull IntSupplier keyCode, @NonNull HotkeyAction action) {}

	private List<DynamicHotkey> dynamicHotkeys;


	private List<DynamicHotkey> dynamicHotkeys() {
		if (dynamicHotkeys == null) {
			dynamicHotkeys = List.of(
				new DynamicHotkey(settings::getKeyAddWord,          (r) -> onKeyAddWord()),
				new DynamicHotkey(settings::getKeyCommandPalette,   (r) -> onKeyEmoji()),
				new DynamicHotkey(settings::getKeyEditText,         (r) -> onKeyEditText()),
				new DynamicHotkey(settings::getKeyEditWord,         (r) -> onKeyEditWord()),
				new DynamicHotkey(settings::getKeyFilterClear,      (r) -> onKeyFilterClear()),
				new DynamicHotkey(settings::getKeyFilterSuggestions,(r) -> onKeyFilterSuggestions(r)),
				new DynamicHotkey(settings::getKeyNextLanguage,     (r) -> onKeyNextLanguage()),
				new DynamicHotkey(settings::getKeyNextInputMode,    (r) -> onKeyNextInputMode()),
				new DynamicHotkey(settings::getKeyPreviousSuggestion,(r) -> onKeyScrollSuggestion(true)),
				new DynamicHotkey(settings::getKeyNextSuggestion,   (r) -> onKeyScrollSuggestion(false)),
				new DynamicHotkey(settings::getKeySelectKeyboard,   (r) -> onKeySelectKeyboard()),
				// Shift can be bound to the same key as Korean Space — try both from the Shift slot.
				new DynamicHotkey(settings::getKeyShift,            (r) -> {
					final KeyIntent shiftIntent = onKeyNextTextCase();
					if (shiftIntent.accepted()) return shiftIntent;
					if (settings.getKeyShift() == settings.getKeySpaceKorean()) return onKeySpaceKorean();
					return KeyIntent.REJECT;
				}),
				new DynamicHotkey(settings::getKeySpaceKorean,      (r) -> onKeySpaceKorean()),
				new DynamicHotkey(settings::getKeyShowSettings,     (r) -> onKeyShowSettings()),
				new DynamicHotkey(settings::getKeyUndo,             (r) -> onKeyUndo()),
				new DynamicHotkey(settings::getKeyRedo,             (r) -> onKeyRedo()),
				new DynamicHotkey(settings::getKeyVoiceInput,       (r) -> onKeyVoiceInput())
			);
		}
		return dynamicHotkeys;
	}


	private KeyIntent onDynamicKey(int keyCode, boolean repeat) {
		for (DynamicHotkey hk : dynamicHotkeys()) {
			if (hk.keyCode().getAsInt() == keyCode) {
				return hk.action().run(repeat);
			}
		}
		return KeyIntent.REJECT;
	}


	protected boolean isHoldHotkey(int keyCode) {
		return
			keyCode < 0
			&& (
				Key.isHotkey(settings, -keyCode)
				|| (Key.isArrowLeft(-keyCode) && InputModeKind.isRecomposing(session.mode))
				|| (Key.isArrowRight(-keyCode) && InputModeKind.isRecomposing(session.mode))
			);
	}


	/********** Hotkey handlers (dynamic) **********/

	private KeyIntent onKeyAddWord() {
		if (!isInputViewShown() || shouldBeOff()) return KeyIntent.REJECT;
		return KeyIntent.accept(this::addWord);
	}


	/**
	 * The command palette is reached via the palette key directly, not a hotkey press — so we
	 * simply decline, letting the standard * handler route the press to {@link #onKeyEmoji()}.
	 */
	public KeyIntent onKeyCommandPalette() {
		return KeyIntent.REJECT;
	}


	private KeyIntent onKeyEmoji() {
		if (shouldBeOff()) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> {
			if (emojiMode.isActive()) {
				emojiMode.nextCategory();
			} else {
				enterEmojiMode();
			}
			forceShowWindow();
		});
	}


	private KeyIntent onKeyEditAdjacentLetter(int keyCode) {
		if (shouldBeOff() || !InputModeKind.isRecomposing(session.mode)) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> ((ModeRecomposing) session.mode).skipLetter(Key.isArrowLeft(keyCode)));
	}


	private KeyIntent onKeyEditDuplicateLetter() {
		if (shouldBeOff() || !InputModeKind.isRecomposing(session.mode)) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> ((ModeRecomposing) session.mode).duplicateLetter());
	}


	private KeyIntent onKeyEditText() {
		if (!isInputViewShown() || shouldBeOff()) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> {
			if (!hideTextEditingPalette()) {
				showTextEditingPalette();
				forceShowWindow();
			}
		});
	}


	public KeyIntent onKeyEditWord() {
		if (shouldBeOff()) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> {
			forceShowWindow();
			editWord();
		});
	}


	public boolean onKeyMoveCursor(int direction) {
		suggestionOps.cancelDelayedAccept();
		session.mode.onAcceptSuggestion(suggestionOps.acceptAndClear(false));
		resetKeyRepeat();

		final boolean backward = direction == CmdMoveCursor.CURSOR_MOVE_LEFT;

		if (textSelection.isEmpty()) {
			return
				appHacks.onMoveCursor(direction)
				|| (backward && onTrimTrailingSpace().run())
				|| textField.moveCursor(direction);
		} else {
			textSelection.clear(backward);
			return true;
		}
	}


	public KeyIntent onKeyFilterClear() {
		if (suggestionOps.isEmpty()) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> {
			suggestionOps.cancelDelayedAccept();

			int stemLength = session.mode.getWordStem().length();
			boolean isFilteringOn = session.mode.isStemFilterFuzzy() || (stemLength > 0 && session.mode.getSequenceLength() != stemLength);

			if (session.mode.clearWordStem() && isFilteringOn) {
				session.mode
					.setOnSuggestionsUpdated(this::handleSuggestionsAsync)
					.loadSuggestions(suggestionOps.getCurrent(session.language, session.mode.getSequenceLength()));
				return;
			}

			session.mode.onAcceptSuggestion(suggestionOps.acceptAndClear(false));
			resetKeyRepeat();
		});
	}


	public KeyIntent onKeyFilterSuggestions(boolean repeat) {
		if (suggestionOps.isEmpty()) return KeyIntent.REJECT;

		// Emit a toast at validation time (no real side effect; acceptable).
		if (!session.mode.supportsFiltering()) {
			UI.toastShortSingle(this, R.string.function_filter_suggestions_not_available);
			return KeyIntent.ACCEPT_NOOP;
		}

		return KeyIntent.accept(() -> {
			suggestionOps.cancelDelayedAccept();

			String filter;
			if (repeat && !suggestionOps.get(1).isEmpty()) {
				filter = suggestionOps.get(1);
			} else {
				filter = suggestionOps.getCurrent(session.language, session.mode.getSequenceLength());
			}

			if (filter.isEmpty()) {
				session.mode.reset();
			} else if (session.mode.setWordStem(filter, repeat)) {
				session.mode
					.setOnSuggestionsUpdated(this::handleSuggestionsAsync)
					.loadSuggestions(filter);
			}
		});
	}


	public KeyIntent onKeyScrollSuggestion(boolean backward) {
		if (suggestionOps.isEmpty()) return KeyIntent.REJECT;
		final boolean effectiveBackward = session.isLanguageRTL != backward;
		return KeyIntent.accept(() -> scrollSuggestions(effectiveBackward));
	}


	public KeyIntent onKeyNextLanguage() {
		if (InputModeKind.isNumeric(session.mode) || session.enabledLanguages.size() < 2) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> {
			if (settings.getQuickSwitchLanguage() || !changeLang()) nextLang();
		});
	}


	public KeyIntent onKeyNextInputMode() {
		if (session.allowedInputModes.size() == 1) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> {
			suggestionOps.scheduleDelayedAccept(session.mode.getAutoAcceptTimeout());
			final int nextModeId = nextInputMode();
			if (nextModeId != session.mode.getId()) {
				setInputMode(nextModeId);
			}
			forceShowWindow();
		});
	}


	public KeyIntent onKeyNextTextCase() {
		if (voiceInputOps.isListening() || inputType.isNumeric() || inputType.isPhoneNumber()) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> {
			suggestionOps.scheduleDelayedAccept(session.mode.getAutoAcceptTimeout());
			if (!nextTextCase()) return;

			getDisplayTextCase(session.language, session.mode.getTextCase());
			setStatusIcon(session.mode, session.language);

			if (settings.isMainLayoutStealth() && !settings.isStatusIconEnabled()) {
				UI.toastShortSingle(this, session.mode.getClass().getSimpleName(), session.mode.toString());
			}

			getFinalContext().pushModeInfoToBar();
		});
	}


	private KeyIntent onKeySelectKeyboard() {
		if (!isInputViewShown() || shouldBeOff()) return KeyIntent.REJECT;
		return KeyIntent.accept(this::selectKeyboard);
	}


	private KeyIntent onKeyShowSettings() {
		if (!isInputViewShown() || shouldBeOff()) return KeyIntent.REJECT;
		return KeyIntent.accept(this::showSettings);
	}


	public KeyIntent onKeySpaceKorean() {
		if (shouldBeOff()) return KeyIntent.REJECT;

		if (!suggestionOps.isEmpty() && LanguageKind.isCJK(session.language)) {
			return KeyIntent.accept(() -> onAcceptSuggestionManually(suggestionOps.acceptAndClear(true), KeyEvent.KEYCODE_ENTER));
		}

		// Delegate to onText: it still uses validateOnly — treat the call as a validate probe.
		if (!onText(Characters.getSpace(session.language), true)) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> onText(Characters.getSpace(session.language), false));
	}


	public KeyIntent onKeyUndo() {
		if (!isInputViewShown() || shouldBeOff()) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> {
			suggestionOps.cancelDelayedAccept();
			suggestionOps.acceptAndClear(true);
			undo();
		});
	}


	public KeyIntent onKeyRedo() {
		if (!isInputViewShown() || shouldBeOff()) return KeyIntent.REJECT;
		return KeyIntent.accept(() -> {
			suggestionOps.cancelDelayedAccept();
			suggestionOps.acceptAndClear(true);
			redo();
		});
	}


	private KeyIntent onKeyVoiceInput() {
		if (!isInputViewShown() || shouldBeOff() || !voiceInputOps.isAvailable()) return KeyIntent.REJECT;
		return KeyIntent.accept(this::toggleVoiceInput);
	}


	@Override
	protected void waitForSpaceTrimKey() {
		session.waitingForSpaceTrim = true;
	}


	@Override
	protected void stopWaitingForSpaceTrimKey() {
		session.waitingForSpaceTrim = false;
	}


	private KeyIntent onTrimTrailingSpace() {
		if (!session.waitingForSpaceTrim || !settings.getAutoTrimTrailingSpace() || !suggestionOps.isEmpty()) return KeyIntent.REJECT;

		String after = textField.getStringAfterCursor(1);
		if (!after.isEmpty() && after.charAt(0) != '\n') {
			stopWaitingForSpaceTrimKey();
			return KeyIntent.REJECT;
		}

		String before = textField.getStringBeforeCursor(2);
		if (before.equals(InputConnectionAsync.TIMEOUT_SENTINEL) || before.length() != 2 || Character.isWhitespace(before.charAt(0)) || before.charAt(1) != Characters.getSpace(session.language).charAt(0)) {
			stopWaitingForSpaceTrimKey();
			return KeyIntent.REJECT;
		}

		return KeyIntent.accept(() -> {
			textField.deleteChars(session.language, 1);
			stopWaitingForSpaceTrimKey();
		});
	}


	/********** Text-editing palette **********/

	protected void detectRTL() {
		session.isLanguageRTL = LanguageKind.isRTL(LanguageCollection.getLanguage(settings.getInputLanguage()));
	}


	private void onTextEditingCommand(int key) {
		if (!suggestionOps.isEmpty() && key != 9) {
			suggestionOps.acceptAndClear(true);
		}

		if (key == 0) {
			if (!InputModeKind.isNumeric(session.mode)) {
				onText(Characters.getSpace(session.language), false);
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

		session.mode.reset();
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
		session.mode.onAcceptSuggestion(suggestionOps.acceptAndClear(false));
		autoTextCase = new AutoTextCase(settings, new Sequences(), inputType);
		beforeSpeech = textField.getStringBeforeCursor();
		voiceInputOps.listen(session.language);
	}


	protected void stopVoiceInput() {
		if (voiceInputOps != null && voiceInputOps.isListening()) {
			voiceInputOps.stop();
		}
	}


	private void onVoiceInputStarted() {}


	private String autoCapitalize(String str) {
		if (autoTextCase == null || !settings.isAutoTextCaseOn(session.mode)) {
			return str;
		}
		return autoTextCase.adjustParagraphTextCase(session.language, str, beforeSpeech, session.mode.getTextCase(), inputType.determineTextCase());
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
			voiceInputOps.forceAlternativeInput(true).listen(session.language);
		} else if (error.isLanguageMissing() && voiceInputOps.enableOfflineMode(session.language, false)) {
			Logger.i(LOG_TAG, "Voice input package for language '" + session.language.getName() + "' is missing. Enforcing online mode for the current session.");
			voiceInputOps.listen(session.language);
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
		if (!CmdEditWord.validate(getFinalContext(), settings, session.language)) return;

		final int previousMode = session.mode.getId();
		if (previousMode == InputMode.MODE_RECOMPOSING) {
			Logger.d(getClass().getSimpleName(), "Already in recomposing mode. Nothing to do.");
			return;
		}

		String word = suggestionOps.getCurrent(session.language, session.mode.getSequenceLength());
		if (word.isEmpty()) {
			word = textField.recomposeSurroundingWord(session.language);
		} else {
			suggestionOps.set(null);
		}

		if (word.isEmpty()) {
			UI.toastShortSingle(this, R.string.edit_word_no_selection);
			return;
		}

		setInputMode(InputMode.MODE_RECOMPOSING);
		if (session.mode.setWordStem(word, false)) {
			((ModeRecomposing) session.mode).setOnFinishListener(() -> setInputMode(previousMode));
			getSuggestions(0, "", null);
		} else {
			textField.finishComposingText();
			setInputMode(previousMode);
			UI.toastShortSingle(
				this,
				"edit_word_invalid_characters",
				getString(R.string.edit_word_invalid_characters, word, session.language.getName())
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
		if (InputModeKind.isPassthrough(session.mode) || voiceInputOps.isListening()) {
			return session.mode.getId();
		}

		if (session.allowedInputModes.size() == 1 && session.allowedInputModes.contains(InputMode.MODE_123) && !InputModeKind.is123(session.mode)) {
			return InputMode.MODE_123;
		} else {
			final int nextModeIndex = (session.allowedInputModes.indexOf(session.mode.getId()) + 1) % session.allowedInputModes.size();
			return session.allowedInputModes.get(nextModeIndex);
		}
	}


	protected void setInputMode(int modeId) {
		if (!session.allowedInputModes.contains(modeId) && modeId != InputMode.MODE_RECOMPOSING) return;

		session.inPredictiveFallback = false;

		suggestionOps.cancelDelayedAccept();
		session.mode.onAcceptSuggestion(suggestionOps.acceptAndClear(false));
		resetKeyRepeat();

		session.mode = InputMode.getInstance(settings, session.language, inputType, textField, modeId);
		determineTextCase();

		if (modeId != InputMode.MODE_RECOMPOSING) {
			settings.saveInputMode(session.mode.getId());
		}

		getDisplayTextCase(session.language, session.mode.getTextCase());
		setStatusIcon(session.mode, session.language);

		if (settings.isMainLayoutStealth() && !settings.isStatusIconEnabled()) {
			UI.toastShortSingle(this, session.mode.getClass().getSimpleName(), session.mode.toString());
		}

		getFinalContext().pushModeInfoToBar();
	}


	protected boolean changeLang() {
		// The change-language dialog has been removed; fall through to nextLang cycling.
		return false;
	}


	protected void nextLang() {
		int previous = session.enabledLanguages.indexOf(session.language.getId());
		int next = (previous + 1) % session.enabledLanguages.size();
		setLang(session.enabledLanguages.get(next));
	}


	public void setLang(int langId) {
		if (!session.enabledLanguages.contains(langId)) return;

		session.inPredictiveFallback = false;

		suggestionOps.cancelDelayedAccept();
		stopVoiceInput();

		session.language = LanguageCollection.getLanguage(langId);
		validateLanguages();

		detectRTL();
		settings.setDefaultCharOrder(session.language, false);

		session.mode = InputMode
			.getInstance(settings, session.language, inputType, textField, determineInputModeId())
			.copy(session.mode);

		if (session.mode.isTyping()) {
			getSuggestions(0, null, this::onAfterLanguageChange);
		} else {
			onAfterLanguageChange();
		}

		if (InputModeKind.isPredictive(session.mode)) {
			DictionaryLoader.autoLoad(this, settings, session.language);
		}

		forceShowWindow();
	}


	private void onAfterLanguageChange() {
		getDisplayTextCase(session.language, session.mode.getTextCase());
		setStatusIcon(session.mode, session.language);
		suggestionOps.setLanguage(session.language);
		if (settings.isMainLayoutStealth() && !settings.isStatusIconEnabled()) {
			UI.toastShortSingle(this, session.mode.getClass().getSimpleName(), session.mode.toString());
		}
		getFinalContext().pushModeInfoToBar();
	}


	protected boolean nextTextCase() {
		final String currentWord = !suggestionOps.isEmpty() && session.mode.isTyping() ? suggestionOps.getCurrent() : "";

		if (!session.mode.nextTextCase(currentWord, session.displayTextCase)) return false;

		session.mode.skipNextTextCaseDetection();
		settings.saveTextCase(session.mode.getTextCase());

		if (currentWord.isEmpty() && !suggestionOps.isEmpty()) {
			suggestionOps.setTextCase(session.language, session.mode.getTextCase());
			appHacks.setComposingText(suggestionOps.getCurrent());
			return true;
		} else if (currentWord.isEmpty() || (currentWord.length() == 1 && !Character.isAlphabetic(currentWord.charAt(0)))) {
			return true;
		}

		int currentSuggestionIndex = suggestionOps.getCurrentIndex();
		currentSuggestionIndex = suggestionOps.containsStem() ? currentSuggestionIndex - 1 : currentSuggestionIndex;

		suggestionOps.set(session.mode.getSuggestions(), currentSuggestionIndex, session.mode.containsGeneratedSuggestions());

		if (InputModeKind.isRecomposing(session.mode)) {
			appHacks.setComposingTextPartsWithHighlightedJoining(session.mode.getWordStem() + suggestionOps.getCurrent(), session.mode.getRecomposingSuffix());
		} else {
			appHacks.setComposingText(suggestionOps.getCurrent());
		}

		return true;
	}


	/********** Emoji mode **********/

	public boolean isInEmojiMode() {
		return emojiMode.isActive();
	}


	public void enterEmojiMode() {
		suggestionOps.cancelDelayedAccept();
		session.mode.onAcceptSuggestion(suggestionOps.acceptAndClear(false));
		emojiMode.enter();
	}


	public void exitEmojiMode() {
		emojiMode.exit();
		resetStatus();
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


	public boolean isFilteringFuzzy() { return session.mode.isStemFilterFuzzy(); }
	public boolean isFilteringOn() {
		String stem = session.mode.getWordStem();
		return stem != null && !stem.isEmpty();
	}
	public boolean isFnPanelVisible() { return false; }
	public boolean isInputLimited() { return inputType.isLimited(); }
	public boolean isInputModeABC() { return InputModeKind.isABC(session.mode); }
	public boolean isInputModeNumeric() { return InputModeKind.isNumeric(session.mode); }
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
		return session.language == null ? "ABC" : session.language.getAbcString().toUpperCase(session.language.getLocale());
	}


	public int getDisplayTextCase() {
		return getDisplayTextCase(session.language, session.mode.getTextCase());
	}


	public InputMode getInputMode() { return session.mode; }


	@NonNull
	public String getInputModeName() {
		if (InputModeKind.isHiragana(session.mode)) return "あ";
		if (InputModeKind.isKatakana(session.mode)) return "ア";
		if (InputModeKind.isPredictive(session.mode)) {
			return session.language != null ? session.language.getCode().toUpperCase(session.language.getLocale()) : "T9";
		}
		if (InputModeKind.isNumeric(session.mode)) return "123";
		return getABCString();
	}


	public int getTextCase() { return session.mode.getTextCase(); }

	@Nullable
	public Language getLanguage() { return session.language; }

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

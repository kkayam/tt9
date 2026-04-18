package io.github.sspanak.tt9.ime;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.hacks.AppHacks;
import io.github.sspanak.tt9.ime.helpers.SuggestionOps;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.StatusIcon;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.Ternary;
import io.github.sspanak.tt9.util.Text;
import io.github.sspanak.tt9.util.sys.DeviceInfo;
import io.github.sspanak.tt9.util.sys.SystemSettings;

/**
 * Base IME handler: lifecycle hooks, status-bar icon, visibility forcing, and the abstract
 * contract every handler in the chain must fulfil.
 *
 * There is no on-screen keyboard UI anymore — the service consumes hardware key events and
 * commits text to the focused field.
 */
abstract class BaseHandler extends InputMethodService {
	private final static String LOG_TAG = "BaseHandler";

	@NonNull protected final AppHacks appHacks = new AppHacks();
	@NonNull protected final TypingSession session = new TypingSession();
	protected SettingsStore settings;

	protected boolean isMainViewShown = false;


	/********** Abstract contract **********/

	// hardware key handlers
	abstract public Ternary onBack();
	abstract public boolean onBackspace(int repeat);
	abstract public KeyIntent onHotkey(int keyCode, boolean repeat);
	abstract protected boolean onNumber(int key, boolean hold, int repeat);
	abstract public boolean onOK();
	abstract public boolean onText(String text, boolean validateOnly);

	// lifecycle
	abstract protected boolean onStart(EditorInfo inputField, boolean restarting);
	abstract protected void onFinishTyping();
	abstract protected void onStop();
	abstract protected void setInputField(EditorInfo inputField);
	abstract protected void waitForSpaceTrimKey();
	abstract protected void stopWaitingForSpaceTrimKey();

	// informational
	abstract protected InputMode determineInputMode();
	abstract protected int determineInputModeId();
	abstract protected SuggestionOps getSuggestionOps();
	abstract protected boolean shouldBeOff();
	abstract protected TraditionalT9 getFinalContext();
	abstract public boolean isFnPanelVisible();


	/********** UI lifecycle **********/

	@Override
	public boolean onEvaluateInputViewShown() {
		super.onEvaluateInputViewShown();
		if (!SystemSettings.isTT9Selected(this)) {
			isMainViewShown = false;
			return false;
		}

		setInputField(getCurrentInputEditorInfo());
		return isMainViewShown = shouldBeVisible();
	}


	@Override
	public boolean onEvaluateFullscreenMode() {
		return false;
	}


	protected void onInit() {}


	protected void initUi(InputMode inputMode) {
		setStatusIcon(inputMode, getFinalContext().getLanguage());

		if (appHacks.isBrutalForceShowNeeded()) {
			brutalForceShowWindow();
		} else if (!isInputViewShown()) {
			updateInputViewShown();
		}
	}


	protected int getDisplayTextCase(@Nullable Language language, int modeTextCase) {
		boolean hasUpperCase = language != null && language.hasUpperCase();
		if (!hasUpperCase) {
			return session.displayTextCase = InputMode.CASE_UNDEFINED;
		}

		if (modeTextCase == InputMode.CASE_UPPER) {
			return session.displayTextCase = InputMode.CASE_UPPER;
		}

		Text currentWord = new Text(language, getSuggestionOps().getCurrent());
		if (currentWord.isEmpty() || !currentWord.isAlphabetic()) {
			return session.displayTextCase = modeTextCase;
		}

		final int wordTextCase = currentWord.getTextCase();
		return session.displayTextCase = wordTextCase == InputMode.CASE_UPPER ? InputMode.CASE_CAPITALIZE : wordTextCase;
	}


	protected void setStatusIcon(@Nullable InputMode mode, @Nullable Language language) {
		if (!settings.isStatusIconEnabled()) {
			return;
		}

		final int resId = new StatusIcon(settings.isStatusIconEnabled() ? mode : null, language, session.displayTextCase).resourceId;
		if (resId == 0) {
			hideStatusIcon();
		} else {
			showStatusIcon(resId);
		}
	}


	protected boolean shouldBeVisible() {
		return determineInputModeId() != InputMode.MODE_PASSTHROUGH;
	}


	/**
	 * Some applications may hide our window and it remains invisible until the screen is touched or OK is pressed.
	 * This is fine for touchscreen keyboards, but the hardware keyboard allows typing even when the window and the suggestions
	 * are invisible. This function forces the InputMethodManager to show our window.
	 * WARNING! Calling this may cause a restart, which will cause InputMode to be recreated. Depending
	 * on how much time the restart takes, this may erase the current user input.
	 */
	protected void forceShowWindow() {
		if (isInputViewShown() || !shouldBeVisible()) {
			return;
		}

		if (DeviceInfo.AT_LEAST_ANDROID_9) {
			requestShowSelf(DeviceInfo.isSonimGen2(getApplicationContext()) ? 0 : InputMethodManager.SHOW_IMPLICIT);
		} else {
			showWindow(true);
		}
	}


	/**
	 * Shows the IME window using brutal force, ignoring IME flags and state, and any (invalid) app
	 * requests for passthrough mode. Note that this should not be randomly used, because it will
	 * cause the UI to appear in calculators, banking apps or others where it is not desired.
	 */
	private void brutalForceShowWindow() {
		if (!isShowInputRequested() || !isMainViewShown) {
			forceShowWindow();
		}

		if (!isShowInputRequested() || !isMainViewShown) {
			Logger.d(LOG_TAG, "InputMethodManager refused show request. Forcing visibility with showWindow().");
			showWindow(true);
		}
	}
}

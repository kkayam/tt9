package io.github.sspanak.tt9.preferences.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import androidx.core.graphics.ColorUtils;
import io.github.sspanak.tt9.BuildConfig;
import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.colors.AbstractColorScheme;
import io.github.sspanak.tt9.colors.CollectionColorScheme;
import io.github.sspanak.tt9.commands.CmdAddWord;
import io.github.sspanak.tt9.commands.CmdBackspace;
import io.github.sspanak.tt9.commands.CmdCommandPalette;
import io.github.sspanak.tt9.commands.CmdEditText;
import io.github.sspanak.tt9.commands.CmdEditWord;
import io.github.sspanak.tt9.commands.CmdFilterClear;
import io.github.sspanak.tt9.commands.CmdFilterSuggestions;
import io.github.sspanak.tt9.commands.CmdNextInputMode;
import io.github.sspanak.tt9.commands.CmdNextLanguage;
import io.github.sspanak.tt9.commands.CmdRedo;
import io.github.sspanak.tt9.commands.CmdSelectKeyboard;
import io.github.sspanak.tt9.commands.CmdShift;
import io.github.sspanak.tt9.commands.CmdShowSettings;
import io.github.sspanak.tt9.commands.CmdSpaceKorean;
import io.github.sspanak.tt9.commands.CmdSuggestionNext;
import io.github.sspanak.tt9.commands.CmdSuggestionPrevious;
import io.github.sspanak.tt9.commands.CmdUndo;
import io.github.sspanak.tt9.commands.CmdVoiceInput;
import io.github.sspanak.tt9.commands.Command;
import io.github.sspanak.tt9.commands.CommandCollection;
import io.github.sspanak.tt9.commands.NullCommand;
import io.github.sspanak.tt9.ime.modes.InputMode;
import io.github.sspanak.tt9.ime.modes.InputModeKind;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.languages.LanguageKind;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownAlignment;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownBottomPaddingPortrait;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownColorScheme;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownKeyHeight;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownLayoutType;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownNumpadFnKeyScale;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownNumpadKeyFontSize;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownNumpadShape;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownSettingsFontSize;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownSuggestionFontSize;
import io.github.sspanak.tt9.preferences.screens.appearance.DropDownWidth;
import io.github.sspanak.tt9.preferences.screens.appearance.SwitchDoubleTapResize;
import io.github.sspanak.tt9.preferences.screens.appearance.SwitchDragResize;
import io.github.sspanak.tt9.preferences.screens.appearance.SwitchKeyShadows;
import io.github.sspanak.tt9.preferences.screens.appearance.SwitchLeftRightArrows;
import io.github.sspanak.tt9.preferences.screens.appearance.SwitchShowArrowsUpDown;
import io.github.sspanak.tt9.preferences.screens.fnKeyOrder.FnKeyOrderValidator;
import io.github.sspanak.tt9.preferences.screens.keypad.DropDownKeyPadDebounceTime;
import io.github.sspanak.tt9.preferences.screens.keypad.SwitchUpsideDownKeys;
import io.github.sspanak.tt9.preferences.screens.languages.SwitchAddWordsWithoutConfirmation;
import io.github.sspanak.tt9.preferences.screens.languages.SwitchRaiseImportLimits;
import io.github.sspanak.tt9.preferences.screens.modeAbc.DropDownAbcAutoAcceptTime;
import io.github.sspanak.tt9.preferences.screens.modePredictive.DropDownOneKeyEmoji;
import io.github.sspanak.tt9.preferences.screens.modePredictive.DropDownPredictiveAutoAcceptTime;
import io.github.sspanak.tt9.preferences.screens.modePredictive.DropDownZeroKeyCharacter;
import io.github.sspanak.tt9.preferences.screens.modePredictive.OneKeyEmojiOptions;
import io.github.sspanak.tt9.ime.helpers.SuggestionOps;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.chars.Characters;
import io.github.sspanak.tt9.util.sys.DeviceInfo;
import io.github.sspanak.tt9.util.sys.SystemSettings;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Flat settings facade. Replaces the old 13-level inheritance chain
 * (BaseSettings → SettingsAddedWords → SettingsHacks → ... → SettingsStore)
 * with a single class whose methods are grouped by domain via section comments.
 */
public class SettingsStore {
	protected final String LOG_TAG = SettingsStore.class.getSimpleName();

	private static final String FIRST_INSTALL_VERSION_KEY = "first_install_version";

	protected final Context context;
	protected final SharedPreferences prefs;
	private SharedPreferences.Editor prefsEditor;


	public SettingsStore(@NonNull Context context) {
		this.context = context;
		prefs = PreferenceManager.getDefaultSharedPreferences(context);
		setFirstInstallVersion();
	}


	public SharedPreferences.Editor getPrefsEditor() {
		if (prefsEditor == null) {
			prefsEditor = prefs.edit();
		}
		return prefsEditor;
	}


	protected int getStringifiedInt(String key, int defaultValue) {
		try {
			return Integer.parseInt(prefs.getString(key, String.valueOf(defaultValue)));
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}


	protected float getStringifiedFloat(String key, float defaultValue) {
		try {
			return Float.parseFloat(prefs.getString(key, String.valueOf(defaultValue)));
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}


	private void setFirstInstallVersion() {
		if (prefs.getInt(FIRST_INSTALL_VERSION_KEY, -1) == -1) {
			getPrefsEditor().putInt(FIRST_INSTALL_VERSION_KEY, io.github.sspanak.tt9.BuildConfig.VERSION_CODE).apply();
		}
	}


	protected boolean isFirstInstall() {
		return prefs.getInt(FIRST_INSTALL_VERSION_KEY, io.github.sspanak.tt9.BuildConfig.VERSION_CODE) == io.github.sspanak.tt9.BuildConfig.VERSION_CODE;
	}


	/********** SettingsAddedWords **********/
public final static int IMPORT_DEFAULT_MAX_FILE_LINES = 250;
	public final static int IMPORT_DEFAULT_MAX_WORDS = 1000;
	public final static int IMPORT_RAISED_MAX_FILE_LINES = 10_000;
	public final static int IMPORT_RAISED_MAX_WORDS = 100_000;

	public boolean getAddWordsNoConfirmation() {
		return prefs.getBoolean(SwitchAddWordsWithoutConfirmation.NAME, false);
	}

	public boolean getRaiseImportLimits() {
		return prefs.getBoolean(SwitchRaiseImportLimits.NAME, SwitchRaiseImportLimits.DEFAULT);
	}

	public int getImportWordsMaxFileLines() {
		return getRaiseImportLimits() ? IMPORT_RAISED_MAX_FILE_LINES : IMPORT_DEFAULT_MAX_FILE_LINES;
	}

	public int getImportWordsMaxWords() {
		return getRaiseImportLimits() ? IMPORT_RAISED_MAX_WORDS : IMPORT_DEFAULT_MAX_WORDS;
	}

	/********** SettingsHacks **********/
public static final int COMPOSING_TEXT_RESTART_THRESHOLD = 150; // ms

	// Input handling modes — previously backed by DropDownInputHandlingMode, now stored as
	// a raw preference read for users who set it before the debug screen was removed.
	public static final int INPUT_HANDLING_NORMAL = 0;
	public static final int INPUT_HANDLING_RETURN_FALSE = 1;
	public static final int INPUT_HANDLING_CALL_SUPER = 2;

	private boolean demoMode = false;

	/************* debugging settings *************/

	public boolean getDemoMode() {
		return demoMode;
	}

	public void setDemoMode(boolean demoMode) {
		this.demoMode = demoMode;
	}

	public int getLogLevel() {
		return getStringifiedInt("pref_log_level", Logger.LEVEL);
	}

	public boolean getEnableSystemLogs() {
		return prefs.getBoolean("pref_enable_system_logs", false);
	}

	public int getInputHandlingMode() {
		return getStringifiedInt("pref_input_handling_mode", INPUT_HANDLING_NORMAL);
	}


	/************* hack settings *************/

	public int getSuggestionScrollingDelay() {
		boolean defaultOn = DeviceInfo.noTouchScreen(context) && !DeviceInfo.AT_LEAST_ANDROID_10;
		return prefs.getBoolean("pref_alternative_suggestion_scrolling", defaultOn) ? 200 : 0;
	}

	public boolean clearInsets() {
		return prefs.getBoolean("pref_clear_insets", DeviceInfo.isSonimGen2(context));
	}

	/**
	 * Protection for lagging devices that detect key press as a long press.
	 * See <a href="https://github.com/sspanak/tt9/issues/882">#882</a> for more info.
	 */
	public boolean getHoldToType() {
		return prefs.getBoolean("pref_hold_to_type", true);
	}

	/**
	 * Protection against faulty devices, that sometimes send two (or more) click events
	 * per a single key press, which absolutely undesirable side effects.
	 * There were reports about this on <a href="https://github.com/sspanak/tt9/issues/117">Kyocera KYF31</a>
	 * and on <a href="https://github.com/sspanak/tt9/issues/399">CAT S22</a>.
	 */
	public int getKeyPadDebounceTime() {
		int defaultTime = DeviceInfo.IS_CAT_S22_FLIP ? 50 : 0;
		defaultTime = DeviceInfo.IS_QIN_F21 ? 20 : defaultTime;
		return getStringifiedInt(DropDownKeyPadDebounceTime.NAME, defaultTime);
	}

	public boolean getSystemLogs() {
		return prefs.getBoolean("pref_enable_system_logs", false);
	}

	public boolean getDonationsVisible() {
		return prefs.getBoolean("pref_show_donations", false);
	}

	public void setDonationsVisible(boolean yes) {
		getPrefsEditor().putBoolean("pref_show_donations", yes).apply();
	}

	public boolean getAllowComposingText() {
		return prefs.getBoolean("pref_allow_composing_text", true);
	}

	public boolean getAutoDisableComposing() {
		return getAllowComposingText() && prefs.getBoolean("hack_auto_disable_composing", true);
	}


	/**
	 * Facebook Messenger has a bug where when trying to reply to a message, and when the keyboard
	 * has certain height, it somehow switches the focus outside of the text field. The problematic
	 * height is exactly the height when the Main View is Small or when the Command Palette is shown.
	 * With this hack, we tell the Main View to become taller and mitigate the issue.
	 * More info: <a href="https://github.com/sspanak/tt9/issues/815">Issue 815</a>. Note that the
	 * bug happens on every phone, not only on Freetel.
	 */
	public boolean getMessengerReplyExtraPadding() {
		return prefs.getBoolean("hack_messenger_reply_extra_padding", false);
	}

	public void setMessengerReplyExtraPadding(boolean enabled) {
		getPrefsEditor().putBoolean("hack_messenger_reply_extra_padding", enabled).apply();
	}

	/********** SettingsInput **********/

	public boolean areEnabledLanguagesMoreThanN(int N) {
		final Set<String> langs = prefs.getStringSet("pref_languages", null);
		return langs != null && langs.size() > N;
	}


	@NonNull
	public ArrayList<Integer> getEnabledLanguageIds() {
		final Set<String> rawLangIds = prefs.getStringSet("pref_languages", null);
		final HashSet<String> langIds = new HashSet<>(rawLangIds != null ? rawLangIds : Collections.emptySet());

		final ArrayList<Integer> list = new ArrayList<>();
		for (String languageId : langIds) {
			try {
				list.add(Integer.parseInt(languageId));
			} catch (NumberFormatException e) {
				Logger.w(LOG_TAG, "Ignoring invalid language ID in preferences: '" + languageId + "'");
			}
		}

		if (list.isEmpty()) {
			list.add(LanguageCollection.getDefault().getId());
		}

		return list;
	}


	public void saveEnabledLanguageIds(ArrayList<Integer> languageIds) {
		Set<String> idsAsStrings = new HashSet<>();
		for (int langId : languageIds) {
			idsAsStrings.add(String.valueOf(langId));
		}

		saveEnabledLanguageIds(idsAsStrings);
	}


	public void saveEnabledLanguageIds(Set<String> languageIds) {
		Set<String> validLanguageIds = new HashSet<>();

		for (String langId : languageIds) {
			if (!Validators.validateInputLanguage(Integer.parseInt(langId), "saveEnabledLanguageIds")){
				continue;
			}

			validLanguageIds.add(langId);
		}

		if (validLanguageIds.isEmpty()) {
			Logger.w(LOG_TAG, "Refusing to save an empty language list");
			return;
		}

		getPrefsEditor().putStringSet("pref_languages", validLanguageIds);
		getPrefsEditor().apply();
	}


	public int getInputLanguage() {
		return prefs.getInt("pref_input_language", LanguageCollection.getDefault().getId());
	}


	public void saveInputLanguage(int language) {
		if (Validators.validateInputLanguage(language, "saveInputLanguage")){
			getPrefsEditor().putInt("pref_input_language", language);
			getPrefsEditor().apply();
		}
	}


	public int getInputMode() {
		return prefs.getInt("pref_input_mode", Validators.DEFAULT_INPUT_MODE);
	}


	public void saveInputMode(int mode) {
		boolean isModeValid = Validators.validateInputMode(mode, LOG_TAG, "Not saving invalid input mode: " + mode);
		if (isModeValid) {
			getPrefsEditor().putInt("pref_input_mode", mode);
			getPrefsEditor().apply();
		}
	}


	public int getTextCase() {
		return prefs.getInt("pref_text_case", Validators.DEFAULT_TEXT_CASE);
	}


	public void saveTextCase(int textCase) {
		boolean isTextCaseValid = Validators.validateTextCase(textCase, LOG_TAG,"Not saving invalid text case: " + textCase);
		if (isTextCaseValid) {
			getPrefsEditor().putInt("pref_text_case", textCase);
			getPrefsEditor().apply();
		}
	}

	/********** SettingsPunctuation **********/
private final static String CHARS_1_PREFIX = "punctuation_order_key_1_initial_";
	public final static String CHARS_GROUP_1 = "punctuation_order_key_1_group";
	public final static String CHARS_AFTER_GROUP_1 = "punctuation_order_key_1_after_group";

	private final static String CHARS_0_PREFIX = "punctuation_order_key_0_initial_";
	public final static String CHARS_GROUP_0 = "punctuation_order_key_0_group";
	public final static String CHARS_AFTER_GROUP_0 = "punctuation_order_key_0_after_group";

	private final static char[] MANDATORY_CHARS_1_EU = new char[] {'\'', '"', '-'};
	public final static char[] FORBIDDEN_CHARS_0 = new char[] {' ', '\n', '\t'};

	public void setDefaultCharOrder(@NonNull Language language, boolean overwrite) {
		if (overwrite) {
			setIncludeNewlineInChars0(language, true);
			setIncludeTabInChars0(language, true);
		}

		if (overwrite || noDefault0Chars(language)) {
			String chars = new String(FORBIDDEN_CHARS_0) + String.join("", language.getKeyCharacters(0));
			chars = chars.replace(" ", Characters.getSpace(language));
			final int splitPosition = 7;
			if (chars.length() < splitPosition) {
				saveChars0(language, chars);
				saveCharsExtra(language, CHARS_AFTER_GROUP_0, "");
			} else {
				saveChars0(language, String.join("", chars.substring(0, splitPosition)));
				saveCharsExtra(language, CHARS_AFTER_GROUP_0, chars.substring(splitPosition));
			}
			saveCharsExtra(language, CHARS_GROUP_0, String.join("", Characters.getCurrencies(language)));
		}

		if (overwrite || noDefault1Chars(language)) {
			saveChars1(language, String.join("", language.getKeyCharacters(1)));
			saveCharsExtra(language, CHARS_GROUP_1, "");
			saveCharsExtra(language, CHARS_AFTER_GROUP_1, "");
		}
	}


	private boolean noDefault0Chars(@NonNull Language language) {
		return prefs.getString(CHARS_0_PREFIX + language.getId(), null) == null;
	}


	private boolean noDefault1Chars(@NonNull Language language) {
		return prefs.getString(CHARS_1_PREFIX + language.getId(), null) == null;
	}


	@NonNull
	public char[] getMandatoryChars0(@Nullable Language language) {
		return LanguageKind.isCyrillic(language) || LanguageKind.isLatinBased(language) ? MANDATORY_CHARS_1_EU : new char[0];
	}


	public void saveChars1(@NonNull Language language, @NonNull String chars) {
		getPrefsEditor().putString(CHARS_1_PREFIX + language.getId(), chars);
		getPrefsEditor().apply();
	}


	public void saveChars0(@NonNull Language language, @NonNull String chars) {
		String safeChars = chars
			.replace("\n", "⏎")
			.replace("\t", Characters.TAB);
		getPrefsEditor().putString(CHARS_0_PREFIX + language.getId(), safeChars);
		getPrefsEditor().apply();
	}


	public void saveCharsExtra(@NonNull Language language, @NonNull String listKey, @NonNull String chars) {
		getPrefsEditor().putString(listKey + "_" + language.getId(), chars);
		getPrefsEditor().apply();
	}


	@NonNull public String getChars1(@Nullable Language language) {
		return String.join("", getChars1AsList(language));
	}


	@NonNull public String getChars0(@Nullable Language language) {
		return String.join("", getChars0AsList(language));
	}


	@NonNull public String getCharsExtra(@NonNull Language language, @NonNull String listKey) {
		return prefs.getString(listKey + "_" + language.getId(), "");
	}


	@NonNull
	public ArrayList<String> getChars1AsList(@Nullable Language language) {
		if (language == null) {
			return new ArrayList<>();
		}

		return getCharsAsList(
			language,
			prefs.getString(CHARS_1_PREFIX + language.getId(), null),
			language.getKeyCharacters(1)
		);
	}


	@NonNull
	public ArrayList<String> getChars0AsList(@Nullable Language language) {
		if (language == null) {
			return new ArrayList<>();
		}

		String safeChars = prefs.getString(CHARS_0_PREFIX + language.getId(), null);
		if (safeChars != null) {
			safeChars = safeChars
				.replace("⏎", "\n")
				.replace(Characters.TAB, "\t")
				.replace("Tab", "\t") // also convert the legacy "Tab" string
				.replace(" ", Characters.getSpace(language));

		}

		return getCharsAsList(language, safeChars, language.getKeyCharacters(0));
	}


	@NonNull
	public ArrayList<String> getCharsExtraAsList(@NonNull Language language, @NonNull String listKey) {
		return getCharsAsList(language, getCharsExtra(language, listKey), new ArrayList<>());
	}


	@NonNull
	public ArrayList<String> getOrderedKeyChars(@Nullable Language language, int number) {
		if (language == null) {
			return new ArrayList<>();
		}

		ArrayList<String> chars;

		switch (number) {
			case 0 -> {
				chars = getChars0AsList(language);
				if (!getCharsExtra(language, CHARS_GROUP_0).isEmpty()) {
					chars.add(SuggestionOps.SHOW_GROUP_0_SUGGESTION);
				}
				chars.addAll(getCharsExtraAsList(language, CHARS_AFTER_GROUP_0));
			}
			case 1 -> {
				chars = getChars1AsList(language);
				if (!getCharsExtra(language, CHARS_GROUP_1).isEmpty()) {
					chars.add(SuggestionOps.SHOW_GROUP_1_SUGGESTION);
				}
				chars.addAll(getCharsExtraAsList(language, CHARS_AFTER_GROUP_1));
			}
			default -> {
				return language.getKeyCharacters(number);
			}
		}

		return chars;
	}


	@NonNull
	private ArrayList<String> getCharsAsList(@Nullable Language language, @Nullable String chars, @NonNull ArrayList<String> defaultValue) {
		if (chars == null) {
			return defaultValue;
		}

		BreakIterator iterator = BreakIterator.getCharacterInstance(language != null ? language.getLocale() : null);
		iterator.setText(chars);

		ArrayList<String> charsList = new ArrayList<>();
		for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
			charsList.add(chars.substring(start, end));
		}

		return charsList;
	}

	public boolean getIncludeNewlineInChars0(@NonNull Language language) {
		return prefs.getBoolean("punctuation_order_include_newline_" + language.getId(), true);
	}

	public void setIncludeNewlineInChars0(@NonNull Language language, boolean include) {
		getPrefsEditor().putBoolean("punctuation_order_include_newline_" + language.getId(), include);
		getPrefsEditor().apply();
	}

	public boolean getIncludeTabInChars0(@NonNull Language language) {
		return prefs.getBoolean("punctuation_order_include_tab_" + language.getId(), true);
	}

	public void setIncludeTabInChars0(@NonNull Language language, boolean include) {
		getPrefsEditor().putBoolean("punctuation_order_include_tab_" + language.getId(), include);
		getPrefsEditor().apply();
	}

	/********** SettingsTyping **********/

	public int getAutoAcceptTimeoutAbc() {
		int time = getStringifiedInt(DropDownAbcAutoAcceptTime.NAME, DropDownAbcAutoAcceptTime.DEFAULT);
		return time > 0 ? time + getKeyPadDebounceTime() : time;
	}
	public boolean getAutoSpaceAbc() {
		return prefs.getBoolean("auto_space_abc_v2", true);
	}
	public boolean getAutoTextCaseAbc() {
		return prefs.getBoolean("auto_text_case_abc_v2", true);
	}

	public int getAutoAcceptTimeoutPredictive() {
		int time = getStringifiedInt(DropDownPredictiveAutoAcceptTime.NAME, DropDownPredictiveAutoAcceptTime.DEFAULT);
		return time > 0 ? time + getKeyPadDebounceTime() : time;
	}
	public boolean getAutoSpacePredictive() { return prefs.getBoolean("auto_space_predictive", true); }
	public boolean getAutoTextCasePredictive() { return prefs.getBoolean("auto_text_case_predictive", true); }
	public boolean getAutoCapitalsAfterNewline() {
		return getAutoTextCasePredictive() && prefs.getBoolean("auto_capitals_after_newline", false);
	}

	public boolean getAutoTrimTrailingSpace() {
		return prefs.getBoolean("auto_trim_trailing_space", true);
	}

	public boolean isAutoTextCaseOn(@Nullable InputMode mode) {
		return
			(InputModeKind.isPredictive(mode) && getAutoTextCasePredictive()) ||
			(InputModeKind.isABC(mode) && getAutoTextCaseAbc());
	}

	public boolean isAutoAssistanceOn(@Nullable InputMode mode) {
		return
			(InputModeKind.isPredictive(mode) && (getAutoSpacePredictive() || getAutoTextCasePredictive() || getPredictWordPairs())) ||
			(InputModeKind.isABC(mode) && (getAutoSpaceAbc() || getAutoTextCaseAbc()));
	}

	public boolean getBackspaceAcceleration() {
		return prefs.getBoolean("backspace_acceleration", false);
	}

	public boolean getBackspaceRecomposing() {
		return prefs.getBoolean("backspace_recomposing", true);
	}

	@NonNull
	public String getDoubleZeroChar() {
		String character = prefs.getString(DropDownZeroKeyCharacter.NAME, DropDownZeroKeyCharacter.DEFAULT);

		// SharedPreferences return a corrupted string when using the real "\n"... :(
		return  character.equals("\\n") ? "\n" : character;
	}

	public boolean areEmojisEnabled() {
		return getOneKeyEmojiMode() != OneKeyEmojiOptions.OPTIONS.NONE;
	}

	public OneKeyEmojiOptions.OPTIONS getOneKeyEmojiMode() {
		try {
			return OneKeyEmojiOptions.OPTIONS.valueOf(prefs.getString(DropDownOneKeyEmoji.NAME, OneKeyEmojiOptions.DEFAULT));
		} catch (IllegalArgumentException e) {
			return OneKeyEmojiOptions.OPTIONS.valueOf(OneKeyEmojiOptions.DEFAULT);
		}
	}

	public boolean getPredictiveMode() {
		return prefs.getBoolean("pref_predictive_mode", true);
	}

	public boolean getPredictWordPairs() {
		return prefs.getBoolean("pref_predict_word_pairs", true);
	}

	public boolean getShowSuggestions() {
		final int inputMode = getInputMode();
		final boolean showInAbc = prefs.getBoolean("show_suggestions_abc", false);

		return inputMode != InputMode.MODE_ABC || showInAbc;
	}

	public boolean getUpsideDownKeys() { return prefs.getBoolean(SwitchUpsideDownKeys.NAME, SwitchUpsideDownKeys.DEFAULT); }

	/********** SettingsUI **********/
public final static int FONT_SIZE_DEFAULT = 0;
	public final static int FONT_SIZE_LARGE = 2;

	public final static float KEY_SHADOW_ELEVATION = 3f;
	public final static float KEY_SHADOW_TRANSLATION = 2f;

	public final static int LAYOUT_SMALL = 3;
	// Retained as unused sentinels so layout-conditional code (preference screens, soft-key
	// behaviour) keeps compiling after the multi-layout system was collapsed to Small only.
	// getMainViewLayout() always returns LAYOUT_SMALL, so these never match.
	public final static int LAYOUT_STEALTH = -1;
	public final static int LAYOUT_TRAY = -2;
	public final static int LAYOUT_NUMPAD = -3;
	public final static int LAYOUT_CLASSIC = -4;

	public final static int MIN_WIDTH_PERCENT = 50;
	private int DEFAULT_WIDTH_LANDSCAPE = 0;
	private Boolean DEFAULT_QUICK_SWITCH_LANGUAGE = null;


	public boolean getNotificationsApproved() {
		return !DeviceInfo.AT_LEAST_ANDROID_13 || getStringifiedInt("pref_asked_for_notifications_version", 0) == Integer.MAX_VALUE;
	}

	public boolean shouldAskForNotifications() {
		return DeviceInfo.AT_LEAST_ANDROID_13 && getStringifiedInt("pref_asked_for_notifications_version", 0) < BuildConfig.VERSION_CODE;
	}

	public int getBottomPaddingPortrait() {
		return getStringifiedInt(DropDownBottomPaddingPortrait.NAME, DropDownBottomPaddingPortrait.DEFAULT);
	}

	public int getBottomPaddingPortraitPx() {
		return Math.round(getBottomPaddingPortrait() * DeviceInfo.getScreenPixelDensity(context));
	}

	/**
	 * Samsung devices with Android 15+ SOMETIMES report bottom inset = navigational bar height, but
	 * but they still move up the IME window up, the Android 14 way. So, if we apply our bottom padding,
	 * we end up with double padding. To avoid this, we read the reported device bottom inset and
	 * overwrite the default bottom padding accordingly.
	 * Safe to call on non-Samsung devices and pre-Android 15 devices. It will just do nothing.
	 */
	public void setSamsungBottomPaddingPortrait(int paddingDp) {
		if (
			DeviceInfo.IS_SAMSUNG
			&& DeviceInfo.AT_LEAST_ANDROID_15
			&& paddingDp > 0
			&& getStringifiedInt(DropDownBottomPaddingPortrait.NAME, -1) == -1
		) {
			getPrefsEditor().putString(DropDownBottomPaddingPortrait.NAME, Integer.toString(paddingDp)).apply();
		}
	}

	public void setNotificationsApproved(boolean yes) {
		getPrefsEditor().putString(
			"pref_asked_for_notifications_version",
			Integer.toString(yes ? Integer.MAX_VALUE : BuildConfig.VERSION_CODE)
		);
		getPrefsEditor().apply();
	}

	public boolean isStatusIconEnabled() {
		return prefs.getBoolean("pref_status_icon", DeviceInfo.IS_QIN_F21 || !DeviceInfo.noKeyboard(context));
	}

	public boolean getDragResize() {
		return prefs.getBoolean(SwitchDragResize.NAME, SwitchDragResize.DEFAULT);
	}

	public boolean getDoubleTapResize() {
		return prefs.getBoolean(SwitchDoubleTapResize.NAME, SwitchDoubleTapResize.DEFAULT);
	}

	public boolean getHapticFeedback() {
		return prefs.getBoolean("pref_haptic_feedback", true);
	}

	public int getAlignment() {
		return getStringifiedInt(DropDownAlignment.NAME, Gravity.CENTER_HORIZONTAL);
	}

	public void setAlignment(int alignment) {
		if (alignment != Gravity.CENTER_HORIZONTAL && alignment != Gravity.START && alignment != Gravity.END) {
			Logger.w(getClass().getSimpleName(), "Ignoring invalid numpad key alignment: " + alignment);
		}

		getPrefsEditor().putString(DropDownAlignment.NAME, Integer.toString(alignment));
		getPrefsEditor().apply();
	}

	public boolean getQuickSwitchLanguage() {
		if (DEFAULT_QUICK_SWITCH_LANGUAGE == null) {
			DEFAULT_QUICK_SWITCH_LANGUAGE = !isMainLayoutStealth() && !areEnabledLanguagesMoreThanN(2);
		}

		return prefs.getBoolean("pref_quick_switch_language", DEFAULT_QUICK_SWITCH_LANGUAGE);
	}

	public boolean getKeyShadows() {
		return prefs.getBoolean(SwitchKeyShadows.NAME, SwitchKeyShadows.DEFAULT);
	}

	public int getSettingsFontSize() {
		int defaultSize = DeviceInfo.IS_QIN_F21 || DeviceInfo.IS_LG_X100S ? FONT_SIZE_LARGE : FONT_SIZE_DEFAULT;
		return getStringifiedInt(DropDownSettingsFontSize.NAME, defaultSize);
	}

	public float getSuggestionFontScale() {
		return getSuggestionFontSizePercent() / 100f;
	}

	public int getSuggestionFontSizePercent() {
		return getStringifiedInt(DropDownSuggestionFontSize.NAME, 100);
	}

	public boolean getSuggestionSmoothScroll() {
		return prefs.getBoolean("pref_suggestion_smooth_scroll", !DeviceInfo.noTouchScreen(context));
	}

	public int getDefaultWidthPercent(boolean isPortrait) {
		if (isPortrait) {
			return 100;
		}

		if (DEFAULT_WIDTH_LANDSCAPE > 0) {
			return DEFAULT_WIDTH_LANDSCAPE;
		}

		int screenWidth = DeviceInfo.getScreenWidth(context.getApplicationContext());
		if (screenWidth < 1) {
			return 100;
		}

		int stylesMaxWidth = Math.round(context.getResources().getDimension(R.dimen.numpad_max_width));
		float width = screenWidth < stylesMaxWidth ? 100 : 100f * stylesMaxWidth / screenWidth;
		width = width < MIN_WIDTH_PERCENT ? MIN_WIDTH_PERCENT : width;

		return DEFAULT_WIDTH_LANDSCAPE = Math.round(width / 5) * 5;
	}

	public int getWidthPercent(boolean isPortrait) {
		return getStringifiedInt(DropDownWidth.NAME, getDefaultWidthPercent(isPortrait));
	}

	public int getMainViewLayout() {
		return LAYOUT_SMALL;
	}

	/** No-op retained so the preference screen's layout picker (now removed) still compiles. */
	public void setPreferredLargeLayout(int layout) {}

	public boolean isMainLayoutLarge() { return false; }
	public boolean isMainLayoutClassic() { return false; }
	public boolean isMainLayoutNumpad() { return false; }
	public boolean isMainLayoutTray() { return false; }
	public boolean isMainLayoutSmall() { return true; }
	public boolean isMainLayoutStealth() { return false; }

	/********** SettingsCustomKeyActions **********/
public static final String CUSTOM_ACTION_KEY_1 = "_1";
	public static final String CUSTOM_ACTION_KEY_2 = "_2";
	public static final String CUSTOM_ACTION_KEY_3 = "_3";
	public static final String CUSTOM_ACTION_KEY_4 = "_4";
	public static final String CUSTOM_ACTION_KEY_5 = "_5";
	public static final String CUSTOM_ACTION_KEY_6 = "_6";
	public static final String CUSTOM_ACTION_KEY_7 = "_7";
	public static final String CUSTOM_ACTION_KEY_8 = "_8";
	public static final String CUSTOM_ACTION_KEY_9 = "_9";

	protected static final HashMap<String, String> classicLayoutDefaultsSwipeLeft = new HashMap<>() {{
		put(CUSTOM_ACTION_KEY_1, CmdAddWord.ID);
		put(CUSTOM_ACTION_KEY_2, CmdEditWord.ID);
		put(CUSTOM_ACTION_KEY_5, CmdEditText.ID);
	}};

	protected static final HashMap<String, String> classicLayoutDefaultsSwipeRight = new HashMap<>() {{
		put(CUSTOM_ACTION_KEY_3, CmdVoiceInput.ID);
	}};

	public float getMoveCursorWithSpaceThreshold() {
		return 0;
	}

	public boolean getMoveCursorWithSpace() {
		return false;
	}

	@NonNull
	public String getSwipeRightCommand(String keySuffix) {
		if (keySuffix == null || keySuffix.isEmpty() || !classicLayoutDefaultsSwipeRight.containsKey(keySuffix) || !isMainLayoutClassic()) {
			return NullCommand.ID;
		}

		String defaultCmd = classicLayoutDefaultsSwipeRight.get(keySuffix);
		return defaultCmd != null ? defaultCmd : NullCommand.ID;
	}

	@NonNull
	public String getSwipeLeftCommand(String keySuffix) {
		if (keySuffix == null || keySuffix.isEmpty() || !classicLayoutDefaultsSwipeLeft.containsKey(keySuffix) || !isMainLayoutClassic()) {
			return NullCommand.ID;
		}

		String defaultCmd = classicLayoutDefaultsSwipeLeft.get(keySuffix);
		return defaultCmd != null ? defaultCmd : NullCommand.ID;
	}

	/********** SettingsVirtualNumpad **********/
public final static int NUMPAD_SHAPE_SQUARE = 0;
	public final static int NUMPAD_SHAPE_V = 1;
	public final static int NUMPAD_SHAPE_LONG_SPACE = 2;

	public static final String DEFAULT_LFN_KEY_ORDER = "1234";
	public static final String DEFAULT_RFN_KEY_ORDER = "5678";

	public boolean getArrowsLeftRight() {
		return prefs.getBoolean(SwitchLeftRightArrows.NAME, SwitchLeftRightArrows.DEFAULT);
	}

	public boolean getArrowsUpDown() {
		return prefs.getBoolean(SwitchShowArrowsUpDown.NAME, SwitchShowArrowsUpDown.DEFAULT);
	}

	public boolean getHardwareKeyVisualFeedback() {
		return prefs.getBoolean("pref_hardware_key_visual_feedback", false);
	}

	@NonNull public String getLfnKeyOrder() {
		return prefs.getString("pref_lfn_key_order", DEFAULT_LFN_KEY_ORDER);
	}

	@NonNull public String getRfnKeyOrder() {
		return prefs.getString("pref_rfn_key_order", DEFAULT_RFN_KEY_ORDER);
	}

	public FnKeyOrderValidator setFnKeyOrder(String left, String right) {
		FnKeyOrderValidator validator = new FnKeyOrderValidator(left, right);
		if (validator.validate()) {
			getPrefsEditor()
				.putString("pref_rfn_key_order", right)
				.putString("pref_lfn_key_order", left)
				.apply();
		}

		return validator;
	}

	public int getNumpadKeyDefaultHeight() {
		return context.getResources().getDimensionPixelSize(R.dimen.numpad_key_height);
	}

	public int getNumpadKeyHeight() {
		return getStringifiedInt(DropDownKeyHeight.NAME, getNumpadKeyDefaultHeight());
	}

	public float getNumpadFnKeyDefaultScale() {
		// The simpler getResource.getFloat() requires API 29, so we must get the value manually.
		try {
			TypedValue outValue = new TypedValue();
			context.getResources().getValue(R.dimen.numpad_key_fn_layout_weight, outValue, true);
			return outValue.getFloat();
		} catch (Exception e) {
			return 0.625f;
		}
	}

	public float getNumpadFnKeyScale() {
		return getStringifiedFloat(DropDownNumpadFnKeyScale.NAME, getNumpadFnKeyDefaultScale());
	}

	public int getNumpadKeyFontSizePercent() {
		return isMainLayoutLarge() ? getStringifiedInt(DropDownNumpadKeyFontSize.NAME, 100) : 100;
	}

	public int getNumpadShape() {
		return getStringifiedInt(DropDownNumpadShape.NAME, NUMPAD_SHAPE_SQUARE);
	}

	public boolean isNumpadShapeLongSpace() { return getNumpadShape() == NUMPAD_SHAPE_LONG_SPACE; }
	public boolean isNumpadShapeV() { return getNumpadShape() == NUMPAD_SHAPE_V; }


	public boolean getTutorialSeen() {
		if (isMainLayoutClassic()) {
			return prefs.getBoolean("pref_tutorial_classic_seen", false);
		} else if (isMainLayoutNumpad()) {
			return prefs.getBoolean("pref_tutorial_fn_keys_seen", false);
		} else {
			return false;
		}
	}


	public void setTutorialSeen() {
		if (isMainLayoutClassic()) {
			getPrefsEditor().putBoolean("pref_tutorial_classic_seen", true).apply();
		} else if (isMainLayoutNumpad()) {
			getPrefsEditor().putBoolean("pref_tutorial_fn_keys_seen", true).apply();
		}
	}

	/********** SettingsHotkeys **********/
private static final String HOTKEY_VERSION = "hotkeys_v7";

	public boolean areHotkeysInitialized() {
		return !prefs.getBoolean(HOTKEY_VERSION, false);
	}


	/**
	 * Applies the default hotkey scheme.
	 * When a standard "Backspace" hardware key is available, "Backspace" hotkey association is not necessary,
	 * so it will be left out blank, to allow the hardware key do its job.
	 * When the on-screen keyboard is on, "Back" is also not associated, because it will cause weird user
	 * experience. Instead the on-screen "Backspace" key can be used.
	 * Arrow keys for manipulating suggestions are also assigned only if available.
	 */
	public void setDefaultKeys() {
		// no default keys
		String[] unassigned = {CmdAddWord.ID, CmdEditText.ID, CmdSelectKeyboard.ID, CmdShowSettings.ID, CmdUndo.ID, CmdRedo.ID, CmdVoiceInput.ID};
		for (String key : unassigned) {
			getPrefsEditor().putString(key, String.valueOf(KeyEvent.KEYCODE_UNKNOWN));
		}

		// backspace
		if (
			KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_CLEAR)
			|| KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_DEL)
			|| isMainLayoutLarge()
		) {
			getPrefsEditor().putString(CmdBackspace.ID, String.valueOf(KeyEvent.KEYCODE_UNKNOWN));
		} else {
			getPrefsEditor().putString(CmdBackspace.ID, String.valueOf(KeyEvent.KEYCODE_BACK));
		}

		// filter clear
		getPrefsEditor().putString(
			CmdFilterClear.ID,
			String.valueOf(KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_DPAD_DOWN) ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_UNKNOWN)
		);

		// filter
		getPrefsEditor().putString(
			CmdFilterSuggestions.ID,
			String.valueOf(KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_DPAD_UP) ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_UNKNOWN)
		);

		// previous suggestion
		getPrefsEditor().putString(
			CmdSuggestionPrevious.ID,
			String.valueOf(KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_DPAD_LEFT) ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_UNKNOWN)
		);

		// next suggestion
		getPrefsEditor().putString(
			CmdSuggestionNext.ID,
			String.valueOf(KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_DPAD_RIGHT) ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_UNKNOWN)
		);

		getPrefsEditor().putString(CmdCommandPalette.ID, String.valueOf(KeyEvent.KEYCODE_STAR)); // emoji mode on press
		getPrefsEditor().putString(CmdNextInputMode.ID, String.valueOf(KeyEvent.KEYCODE_POUND));
		getPrefsEditor().putString(CmdNextLanguage.ID, String.valueOf(-KeyEvent.KEYCODE_POUND)); // negative means "hold"
		getPrefsEditor().putString(CmdShift.ID, String.valueOf(-KeyEvent.KEYCODE_STAR)); // negative means "hold"
		getPrefsEditor().putString(
			CmdSpaceKorean.ID,
			String.valueOf(KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_SPACE) ? KeyEvent.KEYCODE_SPACE : KeyEvent.KEYCODE_STAR)
		);

		getPrefsEditor().putBoolean(HOTKEY_VERSION, true).apply();
	}


	public int getFunctionKey(String functionName) {
		return getStringifiedInt(functionName, KeyEvent.KEYCODE_UNKNOWN);
	}


	public void setFunctionKey(String functionName, int keyCode) {
		if (isValidFunction(functionName)) {
			Logger.d(LOG_TAG, "Setting hotkey for function: '" + functionName + "' to " + keyCode);
			getPrefsEditor().putString(functionName, String.valueOf(keyCode)).apply();
		} else {
			Logger.w(LOG_TAG,"Not setting a hotkey for invalid function: '" + functionName + "'");
		}
	}


	public int getKeyAddWord() {
		return getFunctionKey(CmdAddWord.ID);
	}
	public int getKeyBackspace() {
		return getFunctionKey(CmdBackspace.ID);
	}
	public int getKeyCommandPalette() {
		return getFunctionKey(CmdCommandPalette.ID);
	}
	public int getKeyEditText() {
		return getFunctionKey(CmdEditText.ID);
	}
	public int getKeyEditWord() {
		return getFunctionKey(CmdEditWord.ID);
	}
	public int getKeyFilterClear() {
		return getFunctionKey(CmdFilterClear.ID);
	}
	public int getKeyFilterSuggestions() {
		return getFunctionKey(CmdFilterSuggestions.ID);
	}
	public int getKeyPreviousSuggestion() {
		return getFunctionKey(CmdSuggestionPrevious.ID);
	}
	public int getKeyNextSuggestion() {
		return getFunctionKey(CmdSuggestionNext.ID);
	}
	public int getKeyNextInputMode() {
		return getFunctionKey(CmdNextInputMode.ID);
	}
	public int getKeyNextLanguage() {
		return getFunctionKey(CmdNextLanguage.ID);
	}
	public int getKeySelectKeyboard() {
		return getFunctionKey(CmdSelectKeyboard.ID);
	}
	public int getKeyShift() {
		return getFunctionKey(CmdShift.ID);
	}
	public int getKeySpaceKorean() {
		return getFunctionKey(CmdSpaceKorean.ID);
	}
	public int getKeyShowSettings() {
		return getFunctionKey(CmdShowSettings.ID);
	}
	public int getKeyUndo() {
		return getFunctionKey(CmdUndo.ID);
	}
	public int getKeyRedo() {
		return getFunctionKey(CmdRedo.ID);
	}
	public int getKeyVoiceInput() {
		return getFunctionKey(CmdVoiceInput.ID);
	}


	public String getFunction(int keyCode) {
		if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
			return null;
		}

		for (Command cmd : CommandCollection.getHotkeyCommands()) {
			if (keyCode == getFunctionKey(cmd.getId())) {
				return cmd.getId();
			}
		}

		return null;
	}


	private boolean isValidFunction(String functionName) {
		for (Command cmd : CommandCollection.getHotkeyCommands()) {
			if (cmd.getId().equals(functionName)) {
				return true;
			}
		}
		return false;
	}

	/********** SettingsColors **********/
public static final int DEFAULT_KEY_BACKGROUND_COLOR = Color.WHITE;
	public static final int DEFAULT_KEY_BORDER_COLOR = Color.TRANSPARENT;
	public static final int DEFAULT_KEY_RIPPLE_COLOR = Color.LTGRAY;
	public static final int DEFAULT_KEY_TEXT_COLOR = Color.BLACK;

	@Nullable
	protected static AbstractColorScheme colorScheme;

	public String getColorSchemeId() {
		return prefs.getString(DropDownColorScheme.NAME, DropDownColorScheme.DEFAULT);
	}


	public void setColorScheme(@NonNull AbstractColorScheme scheme) {
		colorScheme = scheme;
		getPrefsEditor()
			.putString(DropDownColorScheme.NAME, String.valueOf(scheme.getId()))
			.apply();
	}


	public void setPreviewScheme(@NonNull AbstractColorScheme scheme) {
		colorScheme = scheme;
	}


	public boolean getDarkTheme() {
		return ColorUtils.calculateLuminance(getKeyboardBackground()) < 0.5;
	}


	// Keyboard Panel
	public int getKeyboardBackground() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getKeyboardBackground();
	}


	public int getKeyboardTextColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getKeyboardText();
	}


	// Suggestions
	public int getSuggestionSelectedBackground() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getSuggestionSelectedBackground();
	}


	public int getSuggestionSelectedColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getSuggestionSelectedColor();
	}


	public int getSuggestionSeparatorColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getSuggestionSeparatorColor();
	}


	// Default key
	@NonNull
	public ColorStateList getKeyBackgroundColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyBackground());
	}


	@NonNull
	public ColorStateList getKeyBorderColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyBorder());
	}


	@NonNull
	public ColorStateList getKeyRippleColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyRipple());
	}


	public int getKeyTextColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getKeyText();
	}


	public int getKeyCornerElementColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getKeyAlternativeText();
	}


	// Fn Key
	@NonNull
	public ColorStateList getKeyFnBackgroundColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyFnBackground());
	}


	@NonNull
	public ColorStateList getKeyFnBorderColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyFnBorder());
	}


	@NonNull
	public ColorStateList getKeyFnRippleColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyFnRipple());
	}


	public int getKeyFnTextColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getKeyFnText();
	}

	public int getKeyFnCornerElementColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getKeyFnAlternativeText();
	}


	// LF4 Key
	@NonNull
	public ColorStateList getKeyLf4BackgroundColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyLf4Background());
	}


	@NonNull
	public ColorStateList getKeyLf4BorderColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyLf4Border());
	}


	@NonNull
	public ColorStateList getKeyLf4RippleColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyLf4Ripple());
	}


	public int getKeyLf4TextColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getKeyLf4Text();
	}


	public int getKeyLf4CornerElementColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getKeyLf4AlternativeText();
	}


	// OK Key
	@NonNull
	public ColorStateList getKeyOkBackgroundColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyOkBackground());
	}


	@NonNull
	public ColorStateList getKeyOkBorderColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyOkBorder());
	}


	@NonNull
	public ColorStateList getKeyOkRippleColor() {
		colorScheme = invalidateScheme(colorScheme);
		return ColorStateList.valueOf(colorScheme.getKeyOkRipple());
	}


	public int getKeyOkTextColor() {
		colorScheme = invalidateScheme(colorScheme);
		return colorScheme.getKeyOkText();
	}


	// Helpers

	@NonNull
	protected AbstractColorScheme invalidateScheme(@Nullable AbstractColorScheme scheme) {
		if (scheme != null && scheme.getNightModeTag() == SystemSettings.isNightModeOn(context)) {
			return scheme;
		}

		return CollectionColorScheme.get(context, getColorSchemeId());
	}


	public void reloadColorScheme() {
		colorScheme = null;
	}

	/********** SettingsStatic **********/

	/************* internal settings *************/
	public static final int AUTO_ASSISTANCE_BEFORE_TEXT = 50; // chars
	public static final int AUTO_ASSISTANCE_AFTER_TEXT = 2; // chars
	public static final int BACKSPACE_ACCELERATION_MAX_CHARS = 20; // maximum chars to be deleted at once in very long words
	public static final int BACKSPACE_ACCELERATION_MAX_CHARS_NO_SPACE = 4; // maximum chars to be deleted at once for languages with no spaces
	public static final int BACKSPACE_ACCELERATION_REPEAT_DEBOUNCE = 5;
	public final static int CLIPBOARD_PREVIEW_LENGTH = 20;
	public final static int CUSTOM_WORDS_SEARCH_RESULTS_MAX = 50;
	public final static int DICTIONARY_AUTO_LOAD_COOLDOWN_TIME = 1200000; // 20 minutes in ms
	public final static int DICTIONARY_DOWNLOAD_CONNECTION_TIMEOUT = 10000; // ms
	public final static int DICTIONARY_DOWNLOAD_READ_TIMEOUT = 10000; // ms
	public final static int DICTIONARY_IMPORT_BATCH_SIZE = 5000; // words
	public final static int DICTIONARY_IMPORT_PROGRESS_UPDATE_TIME = 250; // ms
	public final static long INPUT_CONNECTION_MAX_WAIT = 50; // ms
	public final static int LANGUAGE_SEARCH_DEBOUNCE_TIME = 500; // ms
	public final static int RESIZE_THROTTLING_TIME = 60; // ms
	public final static int SHIFT_STATE_DEBOUNCE_TIME = 175; // ms
	public final static byte SLOW_QUERY_TIME = 50; // ms
	public final static int SLOW_QUERY_TIMEOUT = 3000; // ms
	public final static float SOFT_KEY_AMOUNT_OF_KEY_SIZE_FOR_SWIPE = 0.5f; // 1 = full key size
	public final static int SOFT_KEY_DOUBLE_CLICK_DELAY = 500; // ms
	public final static int SOFT_KEY_REPEAT_DELAY = 40; // ms
	public static final String SOFT_KEY_TEXT_LEFT_DEFAULT = "!";
	public static final String SOFT_KEY_TEXT_RIGHT_DEFAULT = "?";
	public final static float SOFT_KEY_SCALE_SCREEN_COMPENSATION_NORMAL_SIZE = 360; // dp
	public final static float SOFT_KEY_SCALE_SCREEN_COMPENSATION_MAX = 1.4f;
	public final static int SOFT_KEY_TITLE_MAX_CHARS = 5;
	public final static int SOFT_KEY_TITLE_MAX_CHARS_INDIC = 3;
	public final static float SOFT_KEY_V_SHAPE_RATIO_INNER = 1.1f;
	public final static float SOFT_KEY_V_SHAPE_RATIO_OUTER = (float) Math.pow(SOFT_KEY_V_SHAPE_RATIO_INNER, 2);
	public final static float SOFT_KEY_V_SHAPE_RATIO_CLASSIC = (SOFT_KEY_V_SHAPE_RATIO_OUTER + SOFT_KEY_V_SHAPE_RATIO_INNER) * 0.49f;
	public final static int SUGGESTIONS_MAX = 20;
	public final static int SUGGESTIONS_MIN = 8;
	public final static int SUGGESTIONS_SELECT_ANIMATION_DURATION = 66;
	public final static int SUGGESTIONS_TRANSLATE_ANIMATION_DURATION = 0;
	public final static int WORD_BACKGROUND_TASKS_DELAY = 15000; // ms
	public final static int WORD_FREQUENCY_MAX = 25500;
	public final static int WORD_FREQUENCY_NORMALIZATION_DIVIDER = 100; // normalized frequency = WORD_FREQUENCY_MAX / WORD_FREQUENCY_NORMALIZATION_DIVIDER
	public final static int WORD_PAIR_MAX = 1250;
	public final static int WORD_PAIR_MAX_WORD_LENGTH = 6;
	public final static int ZOMBIE_CHECK_INTERVAL = 5000; // ms
	public final static int ZOMBIE_CHECK_MAX = 2;
	public final static int ZOMBIE_HEARTBEAT_INTERVAL = 2000; // ms

	/************* hacks *************/
	public final static int PREFERENCES_CLICK_DEBOUNCE_TIME = 250; // ms
	public final static int VOICE_INPUT_START_FAILURE_TIMEOUT = 5000; // ms

}

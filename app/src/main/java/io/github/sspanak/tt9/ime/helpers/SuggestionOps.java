package io.github.sspanak.tt9.ime.helpers;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import io.github.sspanak.tt9.hacks.AppHacks;
import io.github.sspanak.tt9.hacks.InputType;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.ui.tray.SuggestionBar;
import io.github.sspanak.tt9.util.Text;
import io.github.sspanak.tt9.util.TextTools;
import io.github.sspanak.tt9.util.chars.Characters;
import io.github.sspanak.tt9.util.sys.Clipboard;

/**
 * Headless suggestion data model and text-field commit helper.
 *
 * Holds the list of suggestions the IME is working with (including stem/show-more/clipboard
 * handling that affects what gets committed), drives the composing-text lifecycle on the
 * text field, and exposes the accept/scroll API used by the typing and command handlers.
 *
 * No UI. A future suggestion-bar implementation should read its display state from here.
 */
public class SuggestionOps {
	public static final String CLIPBOARD_SUGGESTION_SUFFIX = "\u200B...\u200B";
	public static final String SHOW_GROUP_0_SUGGESTION = "(…\u200A)";
	public static final String SHOW_GROUP_1_SUGGESTION = "(…\u200B)";

	private static final String SHOW_MORE_SUGGESTION = "(...)";
	private static final String STEM_SUFFIX = "… +";
	private static final String STEM_VARIATION_PREFIX = "…";
	private static final String STEM_PUNCTUATION_VARIATION_PREFIX = "​";

	@NonNull private final Handler delayedAcceptHandler;
	@NonNull private final Consumer<String> onDelayedAccept;

	@Nullable private AppHacks appHacks;
	private boolean isInputLimited;
	@NonNull private TextField textField;
	@Nullable private final SettingsStore settings;

	@NonNull private String stem = "";
	private int selectedIndex = 0;
	@Nullable private List<String> suggestions = new ArrayList<>();
	@NonNull private final List<String> visibleSuggestions = new ArrayList<>();

	@Nullable private SuggestionBar bar;


	public SuggestionOps(@Nullable InputMethodService ims, @Nullable SettingsStore settings, @Nullable AppHacks appHacks, @Nullable InputType inputType, @Nullable TextField textField, @Nullable Consumer<String> onDelayedAccept) {
		delayedAcceptHandler = new Handler(Looper.getMainLooper());
		this.onDelayedAccept = onDelayedAccept != null ? onDelayedAccept : s -> {};

		this.appHacks = appHacks;
		this.isInputLimited = inputType == null || inputType.isLimited();
		this.settings = settings;
		this.textField = textField != null ? textField : new TextField(ims, settings, null);
	}


	public void setLanguage(@Nullable Language newLanguage) {
		// Reserved for future UI-facing bar to react to language direction changes.
	}


	public void setBar(@NonNull SuggestionBar newBar) {
		this.bar = newBar;
		newBar.onSuggestionsSet(visibleSuggestions, selectedIndex);
	}


	public void setDependencies(@NonNull AppHacks appHacks, @NonNull InputType inputType, @NonNull TextField textField) {
		this.appHacks = appHacks;
		this.isInputLimited = inputType.isLimited();
		this.textField = textField;
	}


	public boolean isEmpty() {
		return visibleSuggestions.isEmpty();
	}


	public boolean containsStem() {
		return !stem.isEmpty();
	}


	@NonNull
	public String get(int id) {
		String suggestion = getRaw(id);

		if (suggestion.endsWith(CLIPBOARD_SUGGESTION_SUFFIX) && suggestions != null) {
			suggestion = Clipboard.get(suggestions.size() - id - 1);
		}

		if (suggestion.equals(SHOW_MORE_SUGGESTION) || suggestion.equalsIgnoreCase(SHOW_GROUP_1_SUGGESTION) || suggestion.equalsIgnoreCase(SHOW_GROUP_0_SUGGESTION)) {
			return Characters.PLACEHOLDER;
		}

		if (suggestion.equals(Characters.NEW_LINE)) return "\n";
		if (suggestion.equals(Characters.TAB)) return "\t";

		suggestion = suggestion.replace(Characters.ZWNJ_GRAPHIC, Characters.ZWNJ);
		suggestion = suggestion.replace(Characters.ZWJ_GRAPHIC, Characters.ZWJ);
		if (suggestion.length() == 1) return suggestion;

		int endIndex = suggestion.indexOf(STEM_SUFFIX);
		endIndex = endIndex == -1 ? suggestion.length() : endIndex;

		int startIndex = 0;
		String[] prefixes = {STEM_VARIATION_PREFIX, STEM_PUNCTUATION_VARIATION_PREFIX, Characters.COMBINING_BASE};
		for (String prefix : prefixes) {
			int prefixIndex = suggestion.indexOf(prefix) + 1;
			if (prefixIndex < endIndex) {
				startIndex = Math.max(startIndex, prefixIndex);
			}
		}

		if (startIndex == 0 && endIndex == suggestion.length()) {
			return suggestion;
		}

		return stem + suggestion.substring(startIndex, endIndex);
	}


	@NonNull
	public String getRaw(int id) {
		final int index = containsStem() ? id - 1 : id;
		if (index < 0 || suggestions == null || index >= suggestions.size()) {
			return "";
		}

		return suggestions.get(index);
	}


	public int getCurrentIndex() {
		return selectedIndex;
	}


	public String getCurrent() {
		return get(getCurrentIndex());
	}


	public String getCurrentRaw() {
		return getRaw(getCurrentIndex());
	}


	public String getCurrent(Language language, int maxLength) {
		if (maxLength == 0 || isEmpty()) {
			return "";
		}

		Text text = new Text(language, getCurrent());
		if (maxLength > 0 && !text.isEmpty() && text.codePointLength() > maxLength) {
			return text.substringCodePoints(0, maxLength);
		}

		return text.toString();
	}


	public void clear() {
		set(null);
		if (appHacks == null) {
			textField.setComposingText("");
		} else {
			appHacks.setComposingText("");
		}
		textField.finishComposingText();
	}


	public void set(@Nullable ArrayList<String> suggestions) {
		set(suggestions, 0, false);
	}


	public void set(@Nullable ArrayList<String> suggestions, boolean containsGenerated) {
		set(suggestions, 0, containsGenerated);
	}


	public void set(@Nullable ArrayList<String> newSuggestions, int selectIndex, boolean containsGenerated) {
		setMany(newSuggestions, selectIndex, containsGenerated);
	}


	public void setClipboardItems(@NonNull LinkedList<CharSequence> clips) {
		ArrayList<String> clipStrings = new ArrayList<>(clips.size());
		for (int i = clips.size() - 1; i >= 0; i--) {
			String preview = Clipboard.getPreview(i, CLIPBOARD_SUGGESTION_SUFFIX);
			if (preview != null) {
				clipStrings.add(preview);
			}
		}

		setMany(clipStrings, 0, false);
	}


	public void setTextCase(@NonNull Language language, int textCase) {
		if (suggestions == null || suggestions.isEmpty()) {
			return;
		}

		final ArrayList<String> copy = new ArrayList<>(suggestions);
		copy.replaceAll(text -> new Text(language, text).toTextCase(textCase));
		setMany(copy, selectedIndex, false);
	}


	public void scrollTo(int index) {
		scrollToSuggestion(index);
	}


	public String acceptCurrent() {
		final String current = getCurrent();
		if (Characters.PLACEHOLDER.equals(current)) {
			return "";
		}

		if (!current.isEmpty()) {
			commitCurrent(true, true);
		}

		return current;
	}


	public String acceptEdited() {
		final String current = getCurrent();
		if (current.isEmpty() || Characters.PLACEHOLDER.equals(current)) {
			return "";
		}

		String composingText = textField.getComposingText();
		if (composingText.length() > current.length() && !composingText.endsWith(current)) {
			composingText = new StringBuilder(composingText).replace(composingText.length() - current.length(), composingText.length(), current).toString();

			if (appHacks == null) {
				textField.setComposingText(composingText);
			} else {
				appHacks.setComposingText(composingText);
			}
		}

		textField.finishComposingText();

		return current;
	}


	public String acceptIncomplete() {
		final String current = getCurrent();
		if (Characters.PLACEHOLDER.equals(current)) {
			return "";
		}

		commitCurrent(false, true);

		return current;
	}


	public String acceptIncompleteAndKeepList() {
		if (Characters.PLACEHOLDER.equals(this.getCurrent())) {
			return "";
		}

		commitCurrent(false, false);
		return this.getCurrent();
	}


	public void commitCurrent(boolean entireSuggestion, boolean clearList) {
		if (!isEmpty()) {
			if (entireSuggestion) {
				if (appHacks == null) {
					textField.setComposingText(getCurrent());
				} else {
					appHacks.setComposingText(getCurrent());
				}
			}
			textField.finishComposingText();
		}

		if (clearList) {
			set(null);
		}
	}


	public boolean scheduleDelayedAccept(int delay) {
		cancelDelayedAccept();

		if (isEmpty()) {
			return false;
		}

		if (delay == 0) {
			onDelayedAccept.accept(acceptCurrent());
			return true;
		} else if (delay > 0) {
			delayedAcceptHandler.postDelayed(() -> onDelayedAccept.accept(acceptCurrent()), delay);
		}

		return false;
	}


	public void cancelDelayedAccept() {
		delayedAcceptHandler.removeCallbacksAndMessages(null);
	}


	private void setMany(@Nullable List<String> newSuggestions, int initialSel, boolean containsGenerated) {
		if ((suggestions == null || suggestions.isEmpty()) && (newSuggestions == null || newSuggestions.isEmpty())) {
			return;
		}

		suggestions = newSuggestions;
		selectedIndex = newSuggestions == null || newSuggestions.isEmpty() ? 0 : Math.max(initialSel, 0);

		visibleSuggestions.clear();
		setStem(newSuggestions, containsGenerated);

		boolean onlySpecialChars = newSuggestions != null && !newSuggestions.isEmpty() && !(new Text(newSuggestions.get(0)).isAlphabetic());
		addMany(newSuggestions, onlySpecialChars ? Integer.MAX_VALUE : SettingsStore.SUGGESTIONS_MAX);

		selectedIndex = Math.max(Math.min(selectedIndex, visibleSuggestions.size() - 1), 0);

		if (bar != null) {
			bar.onSuggestionsSet(visibleSuggestions, selectedIndex);
		}
	}


	private void setStem(List<String> newSuggestions, boolean containsGenerated) {
		if (newSuggestions == null || newSuggestions.size() < 2) {
			stem = "";
			return;
		}

		stem = containsGenerated && newSuggestions.get(0).length() > 1 ? newSuggestions.get(0).substring(0, newSuggestions.get(0).length() - 1) : "";

		stem = (stem.length() == 1 && newSuggestions.get(0).length() == 2 && !Character.isAlphabetic(newSuggestions.get(0).charAt(1))) ? "" : stem;

		boolean onlyOneContainsStem = true;
		for (int i = 1; i < newSuggestions.size(); i++) {
			if (newSuggestions.get(i).contains(stem)) {
				onlyOneContainsStem = false;
				break;
			}
		}
		stem = onlyOneContainsStem ? "" : stem;

		if (!stem.isEmpty() && !newSuggestions.contains(stem)) {
			visibleSuggestions.add(stem + STEM_SUFFIX);
			selectedIndex++;
		}
	}


	private void addMany(List<String> newSuggestions, int limit) {
		if (newSuggestions == null) {
			return;
		}

		for (int i = 0, end = Math.min(limit, newSuggestions.size()); i < end; i++) {
			add(newSuggestions.get(i));
		}

		if (newSuggestions.size() > limit) {
			visibleSuggestions.add(SHOW_MORE_SUGGESTION);
		}
	}


	private void add(@NonNull String suggestion) {
		if (!stem.isEmpty() && suggestion.length() == stem.length() + 1 && suggestion.toLowerCase().startsWith(stem.toLowerCase())) {
			String trimmedSuggestion = suggestion.substring(stem.length());
			char firstChar = trimmedSuggestion.charAt(0);

			String prefix = Character.isAlphabetic(firstChar) && !Characters.isCombiningPunctuation(firstChar) ? STEM_VARIATION_PREFIX : STEM_PUNCTUATION_VARIATION_PREFIX;
			prefix = Characters.isFathatan(firstChar) ? " " : prefix;
			visibleSuggestions.add(prefix + formatUnreadableSuggestion(trimmedSuggestion));
			return;
		}

		visibleSuggestions.add(formatUnreadableSuggestion(suggestion));
	}


	private String formatUnreadableSuggestion(String suggestion) {
		if (TextTools.isCombining(suggestion)) {
			return Characters.COMBINING_BASE + suggestion;
		}

		return switch (suggestion) {
			case "\n" -> Characters.NEW_LINE;
			case "\t" -> Characters.TAB;
			case Characters.ZWJ -> Characters.ZWJ_GRAPHIC;
			case Characters.ZWNJ -> Characters.ZWNJ_GRAPHIC;
			default -> suggestion;
		};
	}


	private void scrollToSuggestion(int increment) {
		if (visibleSuggestions.size() <= 1) {
			return;
		}

		calculateScrollIndex(increment);
		boolean listChanged = appendHiddenSuggestionsIfNeeded(increment < 0);

		if (bar != null) {
			if (listChanged) {
				bar.onSuggestionsSet(visibleSuggestions, selectedIndex);
			} else {
				bar.onScrolled(selectedIndex);
			}
		}
	}


	private void calculateScrollIndex(int increment) {
		if (visibleSuggestions.isEmpty()) {
			selectedIndex = 0;
			return;
		}

		selectedIndex = selectedIndex + increment;
		if (selectedIndex == visibleSuggestions.size()) {
			selectedIndex = containsStem() ? 1 : 0;
		} else if (selectedIndex < 0) {
			selectedIndex = visibleSuggestions.size() - 1;
		} else if (selectedIndex == 0 && containsStem()) {
			selectedIndex = visibleSuggestions.size() - 1;
		}
	}


	private boolean appendHiddenSuggestionsIfNeeded(boolean scrollBack) {
		if (selectedIndex < 0 || selectedIndex >= visibleSuggestions.size() || !visibleSuggestions.get(selectedIndex).equals(SHOW_MORE_SUGGESTION)) {
			return false;
		}

		visibleSuggestions.clear();
		addMany(suggestions, Integer.MAX_VALUE);
		selectedIndex = scrollBack || selectedIndex >= visibleSuggestions.size() ? visibleSuggestions.size() - 1 : selectedIndex;
		selectedIndex = Math.max(selectedIndex, 0);

		return true;
	}
}

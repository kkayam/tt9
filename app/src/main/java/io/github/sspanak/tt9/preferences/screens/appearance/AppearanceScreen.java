package io.github.sspanak.tt9.preferences.screens.appearance;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.preferences.PreferencesActivity;
import io.github.sspanak.tt9.preferences.custom.EnhancedDropDownPreference;
import io.github.sspanak.tt9.preferences.screens.ScreenWithPreviewKeyboardHeaderFragment;

public class AppearanceScreen extends ScreenWithPreviewKeyboardHeaderFragment {
	final public static String NAME = "Appearance";

	public AppearanceScreen() { super(); }
	public AppearanceScreen(@Nullable PreferencesActivity activity) { super(activity); }

	@Override public String getName() { return NAME; }
	@Override protected int getTitle() { return R.string.pref_category_appearance; }
	@Override protected int getXml() { return R.xml.prefs_screen_appearance; }

	@Override
	protected void onCreate() {
		super.onCreate();
		populateDropDowns();
		enablePreviewOnChange();
		resetFontSize(true);
	}


	@Override
	public void onResume() {
		super.onResume();
		DropDownColorScheme colorScheme = findPreference(DropDownColorScheme.NAME);
		if (colorScheme != null && activity != null) {
			colorScheme.populate(activity.getSettings());
		}
	}


	private void populateDropDowns() {
		if (activity == null) {
			return;
		}

		EnhancedDropDownPreference[] dropdowns = {
			findPreference(DropDownSettingsFontSize.NAME),
			findPreference(DropDownSuggestionFontSize.NAME),
		};

		for (EnhancedDropDownPreference item : dropdowns) {
			if (item instanceof DropDownSettingsFontSize) {
				((DropDownSettingsFontSize) item).setScreen(this);
			}
			if (item != null) {
				item.populate(activity.getSettings()).preview();
			}
		}
	}


	private void enablePreviewOnChange() {
		DropDownColorScheme colorScheme = findPreference(DropDownColorScheme.NAME);
		if (colorScheme != null) {
			colorScheme.setOnChangeListener(this::onThemeChange);
		}

		EnhancedDropDownPreference suggestionFontSize = findPreference(DropDownSuggestionFontSize.NAME);
		if (suggestionFontSize != null) {
			suggestionFontSize.setOnChangeListener(this::previewDropDownChange);
		}

		SwitchPreferenceCompat statusIcon = findPreference("pref_status_icon");
		if (statusIcon != null) {
			statusIcon.setOnPreferenceChangeListener(this::previewSwitchChange);
		}
	}


	protected void onThemeChange(String s) {
		previewDropDownChange(null);
	}


	private void previewDropDownChange(String s) {
		previewKeyboard();
	}


	private boolean previewSwitchChange(Preference p, Object o) {
		previewDropDownChange(null);
		return true;
	}
}

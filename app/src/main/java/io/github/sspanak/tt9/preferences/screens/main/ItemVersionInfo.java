package io.github.sspanak.tt9.preferences.screens.main;

import androidx.preference.Preference;

import io.github.sspanak.tt9.BuildConfig;
import io.github.sspanak.tt9.preferences.PreferencesActivity;
import io.github.sspanak.tt9.preferences.items.ItemClickable;

class ItemVersionInfo extends ItemClickable {
	static final String NAME = "version_info";

	ItemVersionInfo(Preference item, PreferencesActivity activity) {
		super(item);
	}

	@Override
	protected boolean onClick(Preference p) {
		return true;
	}

	ItemVersionInfo populate() {
		if (item != null) {
			item.setSummary(BuildConfig.VERSION_FULL);
		}
		return this;
	}
}

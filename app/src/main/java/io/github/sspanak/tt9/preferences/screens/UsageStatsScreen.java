package io.github.sspanak.tt9.preferences.screens;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.db.DataStore;
import io.github.sspanak.tt9.db.words.SlowQueryStats;
import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.preferences.PreferencesActivity;
import io.github.sspanak.tt9.preferences.items.ItemText;
import io.github.sspanak.tt9.ui.UI;
import io.github.sspanak.tt9.util.Logger;

public class UsageStatsScreen extends BaseScreenFragment {
	public final static String NAME = "UsageStats";

	private final static String CONTAINER_SLOW_QUERY_STATS = "summary_container";
	private final static String BUTTON_RESET_SLOW_QUERIES = "slow_queries_clear_cache";

	private final static String CONTAINER_WORD_PAIRS = "word_pairs_container";
	private final static String BUTTON_RESET_WORD_PAIRS_CACHE = "word_pair_clear_cache";
	private final static String BUTTON_RESET_WORD_PAIRS_DB = "word_pair_clear_db";


	private ItemText queryListContainer;

	public UsageStatsScreen() { super(); }
	public UsageStatsScreen(@Nullable PreferencesActivity activity) { super(activity); }

	@Override public String getName() { return NAME; }
	@Override protected int getTitle() { return R.string.pref_category_usage_stats; }
	@Override protected int getXml() { return R.xml.prefs_screen_usage_stats; }

	@Override
	protected void onCreate() {
		print(CONTAINER_WORD_PAIRS, DataStore.getWordPairStats());
		print(CONTAINER_SLOW_QUERY_STATS, SlowQueryStats.getSummary());
		printSlowQueries();

		Preference slowQueriesButton = findPreference(BUTTON_RESET_SLOW_QUERIES);
		if (slowQueriesButton != null) {
			slowQueriesButton.setOnPreferenceClickListener(this::resetSlowQueries);
		}

		Preference wordPairsCacheButton = findPreference(BUTTON_RESET_WORD_PAIRS_CACHE);
		if (wordPairsCacheButton != null) {
			wordPairsCacheButton.setOnPreferenceClickListener(this::resetWordPairsCache);
		}

		Preference wordPairsDbButton = findPreference(BUTTON_RESET_WORD_PAIRS_DB);
		if (wordPairsDbButton != null) {
			wordPairsDbButton.setOnPreferenceClickListener(this::deleteWordPairs);
		}

		resetFontSize(false);
	}

	private void print(String containerName, String text) {
		Preference container = findPreference(containerName);
		if (container != null) {
			container.setSummary(text);
		}
	}

	private void printSlowQueries() {
		if (queryListContainer == null) {
			queryListContainer = new ItemText(activity, findPreference("query_list_container"));
			queryListContainer.enableClickHandler();
		}

		String slowQueries = SlowQueryStats.getList();
		queryListContainer.populate(slowQueries.isEmpty() ? "No slow queries." : slowQueries);
	}

	private boolean resetSlowQueries(Preference ignored) {
		SlowQueryStats.clear();
		print(CONTAINER_SLOW_QUERY_STATS, SlowQueryStats.getSummary());
		printSlowQueries();
		return true;
	}

	private boolean resetWordPairsCache(Preference ignored) {
		DataStore.clearWordPairCache();
		print(CONTAINER_WORD_PAIRS, DataStore.getWordPairStats());
		return true;
	}

	private boolean deleteWordPairs(Preference ignored) {
		if (activity == null || activity.getApplicationContext() == null) {
			Logger.w(getName(), "Cannot delete word pairs without context.");
			return false;
		}

		DataStore.deleteWordPairs(
			LanguageCollection.getAll(),
			() -> UI.toastLongFromAsync(activity.getApplicationContext(), "Word pairs deleted. You must reopen the screen manually.")
		);
		return true;
	}
}

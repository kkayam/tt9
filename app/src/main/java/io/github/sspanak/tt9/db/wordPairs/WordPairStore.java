package io.github.sspanak.tt9.db.wordPairs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.sspanak.tt9.db.BaseSyncStore;
import io.github.sspanak.tt9.db.sqlite.DbOps;
import io.github.sspanak.tt9.db.sqlite.WordDbOpener;
import io.github.sspanak.tt9.db.words.DictionaryLoader;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.Timer;

public class WordPairStore extends BaseSyncStore {
	private static final String LOG_TAG = WordPairStore.class.getSimpleName();

	// data
	private final ConcurrentHashMap<Integer, HashMap<WordPair, WordPair>> pairs = new ConcurrentHashMap<>();

	// timing
	private long slowestAddTime = 0;
	private long slowestLoadTime = 0;
	private long slowestSaveTime = 0;
	private long slowestSearchTime = 0;


	public WordPairStore(Context context) {
		super(context);
	}


	@NonNull
	@Override
	protected WordDbOpener openDb(Context context) {
		return WordDbOpener.getInstance(context);
	}


	public void add(Language language, String word1, String word2, String sequence2) {
		String ADD_TIMER_NAME = "word_pair_add";
		Timer.start(ADD_TIMER_NAME);

		WordPair pair = new WordPair(language, word1, word2, sequence2);
		if (pair.isInvalid()) {
			return;
		}

		HashMap<WordPair, WordPair> languagePairs = pairs.computeIfAbsent(language.getId(), k -> new HashMap<>());

		WordPair existing = languagePairs.get(pair);
		if (existing != null) {
			existing.incrementFrequency();
		} else {
			if (languagePairs.size() >= SettingsStore.WORD_PAIR_MAX) {
				evictLowestFrequency(languagePairs);
			}
			languagePairs.put(pair, pair);
		}

		slowestAddTime = Math.max(slowestAddTime, Timer.stop(ADD_TIMER_NAME));
	}


	private static void evictLowestFrequency(HashMap<WordPair, WordPair> languagePairs) {
		WordPair worst = null;
		for (WordPair p : languagePairs.values()) {
			if (worst == null || p.getFrequency() < worst.getFrequency()) {
				worst = p;
			}
		}
		if (worst != null) {
			languagePairs.remove(worst);
		}
	}


	public void clearCache() {
		pairs.clear();
		slowestAddTime = 0;
		slowestSearchTime = 0;
		slowestSaveTime = 0;
		slowestLoadTime = 0;
	}


	@Nullable
	public String getWord2(Language language, String word1, String sequence2) {
		String SEARCH_TIMER_NAME = "word_pair_search";
		Timer.start(SEARCH_TIMER_NAME);

		HashMap<WordPair, WordPair> languagePairs = pairs.get(language.getId());

		if (languagePairs == null || word1 == null || sequence2 == null) {
			slowestSearchTime = Math.max(slowestSearchTime, Timer.stop(SEARCH_TIMER_NAME));
			return null;
		}

		final String lcWord1 = word1.toLowerCase(language.getLocale());
		WordPair best = null;
		for (WordPair p : languagePairs.values()) {
			if (!p.getWord1().equals(lcWord1) || !sequence2.equals(p.getSequence2())) continue;
			if (best == null || p.getFrequency() > best.getFrequency()) {
				best = p;
			}
		}

		String word2 = best == null ? null : best.getWord2();
		slowestSearchTime = Math.max(slowestSearchTime, Timer.stop(SEARCH_TIMER_NAME));
		return word2;
	}


	/**
	 * Returns the most frequent word2 that followed word1, across any sequence2. Used for the
	 * post-space next-word hint where no digit sequence has been typed yet.
	 */
	@Nullable
	public String getNextWord(Language language, String word1) {
		HashMap<WordPair, WordPair> languagePairs = pairs.get(language.getId());
		if (languagePairs == null || word1 == null || word1.isEmpty()) {
			return null;
		}

		final String lcWord1 = word1.toLowerCase(language.getLocale());
		WordPair best = null;
		for (WordPair p : languagePairs.values()) {
			if (!p.getWord1().equals(lcWord1)) continue;
			if (best == null || p.getFrequency() > best.getFrequency()) {
				best = p;
			}
		}

		return best == null ? null : best.getWord2();
	}


	public void save() {
		if (!checkOrNotify()) {
			return;
		}

		String SAVE_TIMER_NAME = "word_pair_save";
		Timer.start(SAVE_TIMER_NAME);

		for (Map.Entry<Integer, HashMap<WordPair, WordPair>> entry : pairs.entrySet()) {
			int langId = entry.getKey();
			HashMap<WordPair, WordPair> languagePairs = entry.getValue();

			sqlite.beginTransaction();
			try {
				DbOps.deleteWordPairs(sqlite.getDb(), langId);
				DbOps.insertWordPairs(sqlite.getDb(), langId, languagePairs.values());
				sqlite.finishTransaction();
			} catch (Exception e) {
				sqlite.failTransaction();
				Logger.e(LOG_TAG, "Failed to save word pairs for language: " + langId + ". " + e);
			}
		}

		long currentTime = Timer.stop(SAVE_TIMER_NAME);
		slowestSaveTime = Math.max(slowestSaveTime, currentTime);
		Logger.d(LOG_TAG, "Saved all word pairs in: " + currentTime + " ms");
	}


	public void load(@NonNull DictionaryLoader dictionaryLoader, ArrayList<Language> languages) {
		if (!checkOrNotify()) {
			return;
		}

		if (dictionaryLoader.isRunning()) {
			Logger.e(LOG_TAG, "Cannot load word pairs while the DictionaryLoader is working.");
			return;
		}

		if (languages == null) {
			Logger.e(LOG_TAG, "Cannot load word pairs for NULL language list.");
			return;
		}

		String LOAD_TIMER_NAME = "word_pair_load";
		Timer.start(LOAD_TIMER_NAME);

		int totalPairs = 0;
		for (Language language : languages) {
			HashMap<WordPair, WordPair> wordPairs = pairs.get(language.getId());
			if (wordPairs == null) {
				wordPairs = new HashMap<>();
				pairs.put(language.getId(), wordPairs);
			} else if (!wordPairs.isEmpty()) {
				Logger.d(LOG_TAG, "Reusing " + wordPairs.size() + " word pairs for language: " + language.getId());
				continue;
			}

			int max = SettingsStore.WORD_PAIR_MAX - wordPairs.size();
			ArrayList<WordPair> dbPairs = new DbOps().getWordPairs(sqlite.getDb(), language, max);
			for (WordPair pair : dbPairs) {
				wordPairs.put(pair, pair);
			}

			Logger.d(LOG_TAG, "Loaded " + wordPairs.size() + " word pairs for language: " + language.getId());
		}

		long currentTime = Timer.stop(LOAD_TIMER_NAME);
		slowestLoadTime = Math.max(slowestLoadTime, currentTime);
		Logger.d(LOG_TAG, "Total " + totalPairs + " word pairs loaded in " + currentTime + " ms");
	}


	public void remove(@NonNull ArrayList<Language> languages) {
		if (!checkOrNotify()) {
			return;
		}

		Timer.start(LOG_TAG);

		for (Language language : languages) {
			DbOps.deleteWordPairs(sqlite.getDb(), language.getId());
		}

		Logger.d(LOG_TAG, "Deleted " + languages.size() + " word pair groups. Time: " + Timer.stop(LOG_TAG) + " ms");

		slowestLoadTime = 0;
		slowestSaveTime = 0;
	}


	@NonNull
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();

		for (Map.Entry<Integer, HashMap<WordPair, WordPair>> entry : pairs.entrySet()) {
			int langId = entry.getKey();
			HashMap<WordPair, WordPair> languagePairs = entry.getValue();

			sb.append("Language ").append(langId).append(": ");
			sb.append(languagePairs.size()).append("\n");
		}

		if (sb.length() == 0) {
			sb.append("No word pairs.\n");
		} else {
			sb.append("\nSlowest add-one: ").append(slowestAddTime).append(" ms\n");
			sb.append("Slowest search-one: ").append(slowestSearchTime).append(" ms\n");
			sb.append("Slowest save-all: ").append(slowestSaveTime).append(" ms\n");
			sb.append("Slowest load-all: ").append(slowestLoadTime).append(" ms\n");
		}

		return sb.toString();
	}
}

package io.github.sspanak.tt9.db.sqlite;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.sspanak.tt9.db.entities.CustomWord;
import io.github.sspanak.tt9.db.entities.NormalizationList;
import io.github.sspanak.tt9.db.entities.Word;
import io.github.sspanak.tt9.db.entities.WordList;
import io.github.sspanak.tt9.db.entities.WordPosition;
import io.github.sspanak.tt9.db.entities.WordPositionsStringBuilder;
import io.github.sspanak.tt9.db.wordPairs.WordPair;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.Logger;
import io.github.sspanak.tt9.util.Text;

/**
 * All SQLite operations (SELECT / INSERT / UPDATE / DELETE) against the words DB, formed by
 * merging the former ReadOps, InsertOps, UpdateOps, and DeleteOps classes.
 *
 * Instance-scoped prepared-statement caches live on the DbOps instance; purely stateless ops are
 * static.
 */
public class DbOps {
	private static final String LOG_TAG = "DbOps";

	// ───── Instance state: per-instance prepared-statement caches ─────
	private final ConcurrentHashMap<String, String> sqlCache = new ConcurrentHashMap<>();
	private final HashMap<Integer, SQLiteStatement> insertWordsQuery = new HashMap<>();
	private final HashMap<Integer, SQLiteStatement> insertPositionsQuery = new HashMap<>();


	/************************************************************
	 * READ — existence checks, language meta, words, custom words
	 ************************************************************/

	public boolean exists(@NonNull SQLiteDatabase db, @NonNull Language language, @NonNull String word, @NonNull String sequence) {
		return getWord(db, language, word, sequence) != null;
	}


	@NonNull
	public Set<String> exists(@NonNull SQLiteDatabase db, @Nullable Language language, @Nullable ArrayList<CustomWord> words) {
		Set<String> foundWords = new HashSet<>();

		if (language == null || words == null || words.isEmpty()) {
			return foundWords;
		}

		String factoryWordsSql = "SELECT COUNT(*) " +
			"FROM " + Tables.getWords(language.getId()) + " AS w" +
			" JOIN " + Tables.getWordPositions(language.getId()) + " AS wp ON w.position >= wp.start AND w.position <= wp.`end`" +
			" WHERE wp.sequence = ? AND LOWER(w.word) = ?";

		String customWordsSql = "SELECT COUNT(*) " +
			"FROM " + Tables.CUSTOM_WORDS +
			" WHERE langId = ? AND sequence = ? AND LOWER(word) = ?";

		SQLiteStatement factoryWordsQuery = CompiledQueryCache.get(db, factoryWordsSql);
		SQLiteStatement customWordsQuery = CompiledQueryCache.get(db, customWordsSql);
		customWordsQuery.bindLong(1, language.getId());

		for (CustomWord word : words) {
			factoryWordsQuery.bindString(1, word.sequence);
			factoryWordsQuery.bindString(2, word.word.toLowerCase(language.getLocale()));
			if (factoryWordsQuery.simpleQueryForLong() > 0) {
				foundWords.add(word.word);
			}

			customWordsQuery.bindString(2, word.sequence);
			customWordsQuery.bindString(3, word.word.toLowerCase(language.getLocale()));
			if (customWordsQuery.simpleQueryForLong() > 0) {
				foundWords.add(word.word);
			}
		}

		return foundWords;
	}


	public boolean exists(@NonNull SQLiteDatabase db, int langId) {
		return CompiledQueryCache.simpleQueryForLong(
			db,
			"SELECT COUNT(*) FROM " + Tables.getWords(langId),
			0
		) > 0;
	}


	public String getLanguageFileHash(@NonNull SQLiteDatabase db, int langId) {
		SQLiteStatement query = CompiledQueryCache.get(db, "SELECT fileHash FROM " + Tables.LANGUAGES_META + " WHERE langId = ?");
		query.bindLong(1, langId);
		try {
			return query.simpleQueryForString();
		} catch (SQLiteDoneException e) {
			return "";
		}
	}


	public long countCustomWords(@NonNull SQLiteDatabase db) {
		return CompiledQueryCache.simpleQueryForLong(db, "SELECT COUNT(*) FROM " + Tables.CUSTOM_WORDS, 0);
	}


	public ArrayList<CustomWord> getCustomWords(@NonNull SQLiteDatabase db, @NonNull String wordFilter, int maxWords) {
		ArrayList<CustomWord> words = new ArrayList<>();

		String[] select = new String[]{"word", "sequence", "langId"};
		String where = "word LIKE ?";
		String[] whereArgs = new String[]{wordFilter + "%"};
		String limit = maxWords > 0 ? String.valueOf(maxWords) : null;
		String orderBy = maxWords > 0 ? null : "word";

		try (Cursor cursor = db.query(Tables.CUSTOM_WORDS, select, where, whereArgs, null, null, orderBy, limit)) {
			while (cursor.moveToNext()) {
				words.add(new CustomWord(
					cursor.getString(0),
					cursor.getString(1),
					(int) cursor.getLong(2)
				));
			}
		}

		return words;
	}


	@Nullable
	public String getWord(@NonNull SQLiteDatabase db, @NonNull Language language, @NonNull String word, @NonNull String sequence) {
		if (sequence.isEmpty() || word.isEmpty()) {
			return null;
		}

		final String sql = sqlCache.computeIfAbsent(
			"exists_fast_" + language.getId(),
			k ->
				"SELECT w.word" +
				" FROM " + Tables.getWordPositions(language.getId()) + " AS wp" +
				" JOIN " + Tables.getWords(language.getId()) + " AS w ON w.position >= wp.start AND w.position <= wp.`end`" +
				" WHERE sequence = ? AND w.word IN(?,?,?)"
		);

		final SQLiteStatement query = CompiledQueryCache.get(db, sql);
		query.bindString(1, sequence);
		query.bindString(2, word);
		query.bindString(3, word.toLowerCase(language.getLocale()));
		query.bindString(4, word.toUpperCase(language.getLocale()));

		try {
			return query.simpleQueryForString();
		} catch (SQLiteDoneException e) {
			return null;
		}
	}


	@NonNull
	public String getWords(@NonNull SQLiteDatabase db, Language language, boolean customWords) {
		StringBuilder words = new StringBuilder();

		String table = customWords || language == null ? Tables.CUSTOM_WORDS : Tables.getWords(language.getId());
		String[] columns = customWords || language == null ? new String[]{"word", "langId"} : new String[]{"word", "frequency"};

		try (Cursor cursor = db.query(table, columns, null, null, null, null, null)) {
			while (cursor.moveToNext()) {
				words
					.append(cursor.getString(0))
					.append("\t")
					.append(cursor.getInt(1))
					.append("\n");
			}
		}

		return words.toString();
	}


	@NonNull
	public WordList getWords(@NonNull SQLiteDatabase db, @Nullable CancellationSignal cancel, @NonNull Language language, @NonNull String positions, String filter, boolean orderByLength, boolean fullOutput) {
		if (positions.isEmpty()) {
			Logger.d(LOG_TAG, "No word positions. Not searching words.");
			return new WordList();
		}

		String wordsQuery = getWordsQuery(language, positions, filter, orderByLength, fullOutput);
		if (wordsQuery.isEmpty() || (cancel != null && cancel.isCanceled())) {
			return new WordList();
		}

		WordList words = new WordList();
		try (Cursor cursor = db.rawQuery(wordsQuery, null, cancel)) {
			while (cursor.moveToNext()) {
				words.add(
					cursor.getString(0),
					fullOutput ? cursor.getInt(1) : 0,
					fullOutput ? cursor.getInt(2) : 0
				);
			}
		} catch (OperationCanceledException e) {
			Logger.d(LOG_TAG, "Words query cancelled!");
			return words;
		}

		return words;
	}


	public String getSimilarWordPositions(@NonNull SQLiteDatabase db, @NonNull CancellationSignal cancel, @NonNull Language language, @NonNull String sequence, boolean onlyExactSequenceMatches, String wordFilter, int minPositions, int maxPositions) {
		int generations;

		if (onlyExactSequenceMatches) {
			generations = 0;
		} else {
			generations = switch (sequence.length()) {
				case 2 -> wordFilter.isEmpty() ? 1 : 10;
				case 3, 4 -> wordFilter.isEmpty() ? 2 : 10;
				default -> 10;
			};
		}

		return getWordPositions(db, cancel, language, sequence, generations, minPositions, maxPositions, wordFilter);
	}


	@NonNull
	public String getWordPositions(@NonNull SQLiteDatabase db, @Nullable CancellationSignal cancel, @NonNull Language language, @NonNull String sequence, int generations, int minPositions, int maxPositions, String wordFilter) {
		if ((sequence.length() == 1 && !language.isTranscribed()) || (cancel != null && cancel.isCanceled())) {
			return sequence;
		}

		WordPositionsStringBuilder positions = new WordPositionsStringBuilder().setMaxFuzzy(maxPositions);

		try (Cursor cursor = db.rawQuery(getPositionsQuery(language, sequence, generations), null, cancel)) {
			positions.appendFromDbRanges(cursor);
		} catch (OperationCanceledException ignored) {
			Logger.d(LOG_TAG, "Word positions query cancelled!");
			return sequence;
		}

		if (positions.getSize() < minPositions && generations < Integer.MAX_VALUE) {
			Logger.d(LOG_TAG, "Not enough positions: " + positions.getSize() + " < " + minPositions + ". Searching for more.");
			try (Cursor cursor = db.rawQuery(getFactoryWordPositionsQuery(language, sequence, Integer.MAX_VALUE), null, cancel)) {
				positions.appendFromDbRanges(cursor);
			} catch (OperationCanceledException ignored) {
				Logger.d(LOG_TAG, "Word positions query cancelled!");
				return sequence;
			}
		}

		return positions.toString();
	}


	@NonNull
	private String getCustomWordPositions(@NonNull SQLiteDatabase db, CancellationSignal cancel, Language language, String sequence, int generations) {
		try (Cursor cursor = db.rawQuery(getCustomWordPositionsQuery(language, sequence, generations), null, cancel)) {
			return new WordPositionsStringBuilder().appendFromDbRanges(cursor).toString();
		} catch (OperationCanceledException e) {
			Logger.d(LOG_TAG, "Custom word positions query cancelled.");
			return "";
		}
	}


	private String getPositionsQuery(@NonNull Language language, @NonNull String sequence, int generations) {
		return
			"SELECT `start`, `end`, `exact` FROM ( " +
				getFactoryWordPositionsQuery(language, sequence, generations) +
				") UNION " +
				getCustomWordPositionsQuery(language, sequence, generations);
	}


	@NonNull
	private String getFactoryWordPositionsQuery(@NonNull Language language, @NonNull String sequence, int generations) {
		StringBuilder sql = new StringBuilder("SELECT `start`, `end`, LENGTH(`sequence`) = ").append(sequence.length()).append(" AS `exact`")
			.append(" FROM ").append(Tables.getWordPositions(language.getId()))
			.append(" WHERE ");

		if (generations >= 0 && generations < 10) {
			sql.append(" sequence IN('").append(sequence);

			int lastChild = (int) Math.pow(10, generations) - 1;

			for (int seqEnd = 1; seqEnd <= lastChild; seqEnd++) {
				if (seqEnd % 10 != 0) {
					sql.append("','").append(sequence).append(seqEnd);
				}
			}

			sql.append("')");
		} else {
			String rangeEnd = generations == 10 ? "9" : "999999";
			sql.append(" sequence = '")
				.append(sequence)
				.append("' OR sequence BETWEEN '").append(sequence).append("0' AND '").append(sequence).append(rangeEnd).append("'");
			sql.append(" ORDER BY `start` ");
			sql.append(" LIMIT ").append(SettingsStore.SUGGESTIONS_MAX);
		}

		String positionsSql = sql.toString();
		Logger.v(LOG_TAG, "Index SQL: " + positionsSql);
		return positionsSql;
	}


	@NonNull
	private String getCustomWordPositionsQuery(@NonNull Language language, @NonNull String sequence, int generations) {
		String sql = "SELECT -id as `start`, -id as `end`, LENGTH(`sequence`) = " + sequence.length() + " as `exact` " +
			" FROM " + Tables.CUSTOM_WORDS +
			" WHERE langId = " + language.getId() +
			" AND (sequence = " + sequence;

		if (generations > 0) {
			sql += " OR sequence BETWEEN " + sequence + "0 AND " + sequence + "999999)";
		} else {
			sql += ")";
		}

		Logger.v(LOG_TAG, "Custom words SQL: " + sql);
		return sql;
	}


	@NonNull
	private String getWordsQuery(@NonNull Language language, @NonNull String positions, @NonNull String filter, boolean orderByLength, boolean fullOutput) {
		StringBuilder sql = new StringBuilder();
		sql.append("SELECT word");
		if (fullOutput) {
			sql.append(",frequency,position");
		}

		sql.append(" FROM ").append(Tables.getWords(language.getId()))
			.append(" WHERE position IN(").append(positions).append(")");

		if (!filter.isEmpty()) {
			sql.append(" AND word LIKE '").append(filter.replaceAll("'", "''")).append("%'");
		}

		sql.append(" ORDER BY ");
		if (orderByLength) {
			sql.append("LENGTH(word), ");
		}
		sql.append("frequency DESC");

		String wordsSql = sql.toString();
		Logger.v(LOG_TAG, "Words SQL: " + wordsSql);
		return wordsSql;
	}


	public NormalizationList getNextInNormalizationQueue(@NonNull SQLiteDatabase db) {
		String res = CompiledQueryCache.simpleQueryForString(
			db,
			"SELECT langId || ',' || positionsToNormalize FROM " + Tables.LANGUAGES_META + " WHERE positionsToNormalize IS NOT NULL LIMIT 1",
			null
		);

		return new NormalizationList(res);
	}


	@NonNull
	public ArrayList<WordPair> getWordPairs(@NonNull SQLiteDatabase db, @NonNull Language language, int limit) {
		ArrayList<WordPair> pairs = new ArrayList<>();

		if (limit <= 0) {
			return pairs;
		}

		String[] select = new String[]{"word1", "word2", "sequence2"};

		try (Cursor cursor = db.query(Tables.getWordPairs(language.getId()), select, null, null, null, null, null, String.valueOf(limit))) {
			while (cursor.moveToNext()) {
				pairs.add(new WordPair(language, cursor.getString(0), cursor.getString(1), cursor.getString(2)));
			}
		}

		return pairs;
	}


	/************************************************************
	 * INSERT
	 ************************************************************/

	public void insertWord(@NonNull SQLiteDatabase db, @NonNull Language language, @NonNull Word word) {
		SQLiteStatement insert = insertWordsQuery.get(language.getId());
		if (insert == null) {
			insert = CompiledQueryCache.get(db, "INSERT INTO " + Tables.getWords(language.getId()) + " (frequency, position, word) VALUES (?, ?, ?)");
			insertWordsQuery.put(language.getId(), insert);
		}

		insert.bindLong(1, word.frequency);
		insert.bindLong(2, word.position);
		insert.bindString(3, word.word);
		insert.execute();
	}


	public void insertWordPosition(@NonNull SQLiteDatabase db, @NonNull Language language, @NonNull WordPosition position) {
		SQLiteStatement insert = insertPositionsQuery.get(language.getId());
		if (insert == null) {
			insert = CompiledQueryCache.get(db, "INSERT INTO " + Tables.getWordPositions(language.getId()) + " (sequence, `start`, `end`) VALUES (?, ?, ?)");
			insertPositionsQuery.put(language.getId(), insert);
		}

		insert.bindString(1, position.sequence);
		insert.bindLong(2, position.start);
		insert.bindLong(3, position.end);
		insert.execute();
	}


	public static void replaceLanguageMeta(@NonNull SQLiteDatabase db, int langId, String fileHash) {
		SQLiteStatement query = CompiledQueryCache.get(db, "REPLACE INTO " + Tables.LANGUAGES_META + " (langId, fileHash) VALUES (?, ?)");
		query.bindLong(1, langId);
		query.bindString(2, fileHash);
		query.execute();
	}


	public static boolean insertCustomWord(@NonNull SQLiteDatabase db, @NonNull Language language, @NonNull String sequence, @NonNull String word) {
		ContentValues values = new ContentValues();
		values.put("langId", language.getId());
		values.put("sequence", sequence);
		values.put("word", word);

		long insertId = db.insert(Tables.CUSTOM_WORDS, null, values);
		if (insertId == -1) {
			return false;
		}

		values = new ContentValues();
		values.put("position", (int) -insertId);
		values.put("word", word);
		insertId = db.insert(Tables.getWords(language.getId()), null, values);

		return insertId != -1;
	}


	public static void restoreCustomWords(@NonNull SQLiteDatabase db, @NonNull Language language) {
		CompiledQueryCache.execute(
			db,
			"INSERT INTO " + Tables.getWords(language.getId()) + " (position, word) " +
				"SELECT -id, word FROM " + Tables.CUSTOM_WORDS + " WHERE langId = " + language.getId()
		);
	}


	public static void insertWordPairs(@NonNull SQLiteDatabase db, int langId, Collection<WordPair> pairs) throws SQLException {
		if (langId <= 0 || pairs == null || pairs.isEmpty()) {
			return;
		}

		StringBuilder sql = new StringBuilder(
			"INSERT INTO " + Tables.getWordPairs(langId) + " (word1, word2, sequence2) VALUES"
		);

		for (WordPair pair : pairs) {
			sql.append(pair.toSqlRow()).append(",");
		}

		sql.setLength(sql.length() - 1);

		db.execSQL(sql.toString());
	}


	/************************************************************
	 * UPDATE
	 ************************************************************/

	public static boolean changeFrequency(@NonNull SQLiteDatabase db, @NonNull Language language, Text wordFilter, int position, int frequency) {
		boolean isFilterOn = wordFilter != null && !wordFilter.isEmpty();
		String sql = "UPDATE " + Tables.getWords(language.getId()) + " SET frequency = ? WHERE position = ?";

		if (wordFilter != null && !wordFilter.isEmpty()) {
			sql += " AND word IN(?, ?, ?)";
		}

		SQLiteStatement query = CompiledQueryCache.get(db, sql);
		query.bindLong(1, frequency);
		query.bindLong(2, position);
		if (isFilterOn) {
			query.bindString(3, wordFilter.capitalize());
			query.bindString(4, wordFilter.toLowerCase());
			query.bindString(5, wordFilter.toUpperCase());
		}

		if (!isFilterOn) {
			Logger.v(LOG_TAG, "Change frequency SQL: " + sql + "; (" + frequency + ", " + position + ")");
		} else {
			Logger.v(LOG_TAG, "Change frequency SQL: " + sql + "; (" + frequency + ", " + position + ", '" + wordFilter + "')");
		}

		return query.executeUpdateDelete() > 0;
	}


	public static void normalize(@NonNull SQLiteDatabase db, NormalizationList normalizationList) {
		if (normalizationList.langId <= 0 || normalizationList.positions == null || normalizationList.positions.isEmpty()) {
			return;
		}

		db.execSQL(
			"UPDATE " + Tables.getWords(normalizationList.langId) +
			" SET frequency = frequency / " + SettingsStore.WORD_FREQUENCY_NORMALIZATION_DIVIDER +
			" WHERE position IN (" + normalizationList.positions + ")"
		);

		SQLiteStatement query = CompiledQueryCache.get(db, "UPDATE " + Tables.LANGUAGES_META + " SET positionsToNormalize = NULL WHERE langId = ?");
		query.bindLong(1, normalizationList.langId);
		query.execute();
	}


	public static void scheduleNormalization(@NonNull SQLiteDatabase db, @NonNull Language language, @NonNull String positions) {
		SQLiteStatement query = CompiledQueryCache.get(db, "UPDATE " + Tables.LANGUAGES_META + " SET positionsToNormalize = ? WHERE langId = ?");
		query.bindString(1, positions);
		query.bindLong(2, language.getId());
		query.execute();
	}


	/************************************************************
	 * DELETE
	 ************************************************************/

	public static void delete(@NonNull SQLiteDatabase db, int languageId) {
		db.delete(Tables.getWords(languageId), null, null);
		db.delete(Tables.getWordPositions(languageId), null, null);
	}


	public static void deleteCustomWord(@NonNull SQLiteDatabase db, int languageId, String word) {
		db.delete(Tables.getWords(languageId), "word = ?", new String[]{word});
		db.delete(Tables.CUSTOM_WORDS, "word = ?", new String[]{word});
	}


	public static int purgeCustomWords(@NonNull SQLiteDatabase db, int languageId) {
		String words = Tables.getWords(languageId);
		String positions = Tables.getWordPositions(languageId);

		String repeatingWordsSql = "SELECT GROUP_CONCAT(cw.ROWID) " +
			" FROM " + Tables.CUSTOM_WORDS + " AS cw " +
			" JOIN " + positions + " AS p ON p.sequence = cw.sequence " +
			" JOIN " + words + " AS w " +
				" ON w.position >= p.start AND w.position <= p.`end` " +
				" AND LOWER(w.word) = LOWER(cw.word) " +
			" WHERE cw.langId = ?";

		SQLiteStatement repeatingWordsQuery = CompiledQueryCache.get(db, repeatingWordsSql);
		repeatingWordsQuery.bindLong(1, languageId);
		String repeatingWords = repeatingWordsQuery.simpleQueryForString();

		return db.delete(Tables.CUSTOM_WORDS, "ROWID IN (" + repeatingWords + ")", null);
	}


	public static void deleteWordPairs(@NonNull SQLiteDatabase db, int languageId) {
		db.delete(Tables.getWordPairs(languageId), null, null);
	}
}

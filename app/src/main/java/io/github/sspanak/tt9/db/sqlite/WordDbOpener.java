package io.github.sspanak.tt9.db.sqlite;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import io.github.sspanak.tt9.languages.LanguageCollection;
import io.github.sspanak.tt9.util.Logger;

public class WordDbOpener extends SQLiteOpener {
	private static final String LOG_TAG = WordDbOpener.class.getSimpleName();

	/** First version that stores a `frequency` column on word_pairs_<langId>. */
	private static final int WORD_PAIR_FREQUENCY_VERSION = 1538;

	private static WordDbOpener self;

	private WordDbOpener(Context context) {
		super(context.getApplicationContext(), "tt9.db");
	}

	@NonNull
	public static WordDbOpener getInstance(Context context) {
		if (self == null) {
			self = new WordDbOpener(context);
		}

		return self;
	}

	@NonNull
	@Override
	protected String[] getCreateQueries() {
		return Tables.getWordsCreateQueries(LanguageCollection.getAll());
	}

	@NonNull
	@Override
	protected Migration[] getMigrations() {
		return Migration.WORDS;
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		// Word-pair frequency was added in v1538. Drop every per-language word_pairs_* table
		// so the subsequent onCreate recreates them with the new schema. Users lose their
		// word-pair history (it rebuilds naturally during typing), which is cheaper than
		// writing per-language ALTER TABLE migrations.
		if (oldVersion < WORD_PAIR_FREQUENCY_VERSION) {
			try (Cursor cursor = db.rawQuery(
				"SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'word_pairs_%'",
				null
			)) {
				while (cursor.moveToNext()) {
					String table = cursor.getString(0);
					db.execSQL("DROP TABLE IF EXISTS " + table);
					Logger.d(LOG_TAG, "Dropped legacy word-pairs table: " + table);
				}
			} catch (Exception e) {
				Logger.e(LOG_TAG, "Failed to drop legacy word-pairs tables: " + e.getMessage());
			}
		}

		super.onUpgrade(db, oldVersion, newVersion);
	}
}

package io.github.sspanak.tt9.db.wordPairs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;
import io.github.sspanak.tt9.util.Text;
import io.github.sspanak.tt9.util.chars.Characters;

public class WordPair {
	private final Language language;
	@NonNull private final String word1;
	@NonNull private final String word2;
	private final String sequence2;
	private int frequency;
	private Integer hash = null;


	public WordPair(Language language, String word1, String word2, String sequence2) {
		this(language, word1, word2, sequence2, 1);
	}


	public WordPair(Language language, String word1, String word2, String sequence2, int frequency) {
		this.language = language;
		this.word1 = word1 != null ? word1.toLowerCase(language.getLocale()) : "";
		this.word2 = word2 != null ? word2.toLowerCase(language.getLocale()) : "";
		this.sequence2 = sequence2;
		this.frequency = Math.max(frequency, 1);
	}


	boolean isInvalid() {
		Text w1 = new Text(language, word1);
		Text w2 = new Text(language, word2);

		return
			language == null
			|| word1.isEmpty() || word2.isEmpty()
			|| word1.equals(Characters.START_OF_TEXT) || word1.equals(Characters.END_OF_TEXT)
			|| word2.equals(Characters.START_OF_TEXT) || word2.equals(Characters.END_OF_TEXT)
			|| word1.equals(word2)
			|| sequence2 == null || !(new Text(sequence2).isNumeric())
			|| (w1.codePointLength() > SettingsStore.WORD_PAIR_MAX_WORD_LENGTH && w2.codePointLength() > SettingsStore.WORD_PAIR_MAX_WORD_LENGTH)
			|| !w1.isWord() || !w2.isWord();
	}


	@NonNull
	public String getWord1() {
		return word1;
	}


	@NonNull
	public String getWord2() {
		return word2;
	}


	public String getSequence2() {
		return sequence2;
	}


	public int getFrequency() {
		return frequency;
	}


	public void incrementFrequency() {
		if (frequency < Integer.MAX_VALUE) {
			frequency++;
		}
	}


	/**
	 * Hash over the full triple (word1, word2, sequence2) — distinct word2 options coexist for
	 * the same (word1, sequence2) so their frequencies can compete.
	 */
	@Override
	public int hashCode() {
		if (hash == null) {
			hash = (word1 + "," + word2 + "," + (sequence2 == null ? "" : sequence2)).hashCode();
		}

		return hash;
	}


	@Override
	public boolean equals(@Nullable Object obj) {
		return obj instanceof WordPair && obj.hashCode() == hashCode();
	}


	public String toSqlRow() {
		return "('" + word1 + "','" + word2 + "','" + sequence2 + "'," + frequency + ")";
	}


	@NonNull
	@Override
	public String toString() {
		return "(" + word1 + "," + word2 + "," + sequence2 + ",f=" + frequency + ")";
	}
}

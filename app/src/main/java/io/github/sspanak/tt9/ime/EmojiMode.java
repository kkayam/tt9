package io.github.sspanak.tt9.ime;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.function.Supplier;

import io.github.sspanak.tt9.ime.helpers.SuggestionOps;
import io.github.sspanak.tt9.ime.helpers.TextField;
import io.github.sspanak.tt9.util.chars.Emoji;

/**
 * Holds all state and behavior for the overlay emoji picker that sits on top of the regular
 * input mode. The picker is not a proper {@link io.github.sspanak.tt9.ime.modes.InputMode}
 * because it is digit-free — it repurposes the suggestion bar to scroll through emoji categories
 * and commits whichever emoji is selected when OK is pressed.
 */
public final class EmojiMode {
	@NonNull private final Supplier<Context> contextSupplier;
	@NonNull private final Supplier<SuggestionOps> suggestionOpsSupplier;
	@NonNull private final Supplier<TextField> textFieldSupplier;

	private boolean active = false;
	private int categoryIndex = 0;


	public EmojiMode(
		@NonNull Supplier<Context> contextSupplier,
		@NonNull Supplier<SuggestionOps> suggestionOpsSupplier,
		@NonNull Supplier<TextField> textFieldSupplier
	) {
		this.contextSupplier = contextSupplier;
		this.suggestionOpsSupplier = suggestionOpsSupplier;
		this.textFieldSupplier = textFieldSupplier;
	}


	@NonNull private Context context() { return contextSupplier.get(); }
	@NonNull private SuggestionOps suggestionOps() { return suggestionOpsSupplier.get(); }
	@NonNull private TextField textField() { return textFieldSupplier.get(); }


	public boolean isActive() {
		return active;
	}


	public void enter() {
		active = true;
		categoryIndex = 0;
		showCurrentCategory();
	}


	public void exit() {
		active = false;
		categoryIndex = 0;
		suggestionOps().set(null);
	}


	public void nextCategory() {
		categoryIndex = (categoryIndex + 1) % Emoji.getMaxEmojiLevel();
		showCurrentCategory();
	}


	/**
	 * Commits the currently selected emoji into the text field and reloads the category so
	 * "Recently Used" updates appear immediately. The "Recently Used" tab itself is kept stable
	 * during the session to avoid reordering under the cursor.
	 */
	public void onSelect() {
		final SuggestionOps ops = suggestionOps();
		final int currentIndex = ops.getCurrentIndex();
		final String emoji = ops.getCurrent();
		if (!emoji.isEmpty()) {
			textField().setText(emoji);
			Emoji.recordEmojiUsage(context(), emoji);
		}
		if (categoryIndex == 0) {
			return;
		}
		ops.set(Emoji.getEmoji(context(), categoryIndex), currentIndex, false);
	}


	private void showCurrentCategory() {
		ArrayList<String> emojis = Emoji.getEmoji(context(), categoryIndex);
		if (emojis.isEmpty() && categoryIndex == 0) {
			categoryIndex = 1;
			emojis = Emoji.getEmoji(context(), categoryIndex);
		}
		suggestionOps().set(emojis, 0, false);
	}
}

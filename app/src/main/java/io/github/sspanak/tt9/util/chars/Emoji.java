package io.github.sspanak.tt9.util.chars;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;

public class Emoji extends Punctuation {
	private static final String RECENT_EMOJIS_KEY = "recent_emojis";
	private static final int MAX_RECENT_EMOJIS = 30;
	final private static ArrayList<String> TextEmoticons = new ArrayList<>(Arrays.asList(
		":)", ":D", ":P", ";)", "\\m/", ":-O", ":|", ":("
	));

	final private static ArrayList<ArrayList<String>> Emoji = new ArrayList<>(Arrays.asList(
		// 0: Smileys & Happy
		new ArrayList<>(Arrays.asList(
			"😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "😊",
			"😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙", "🥲", "😋",
			"😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🫡"
		)),
		// 1: Sad & Negative
		new ArrayList<>(Arrays.asList(
			"😐", "😑", "😶", "🫤", "😏", "😒", "🙄", "😬", "😮‍💨", "🤥",
			"😔", "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🤮", "🥵",
			"🥶", "🥴", "😵", "🤯", "😕", "🫠", "😟", "🙁", "😮", "😯"
		)),
		// 2: Angry & Intense
		new ArrayList<>(Arrays.asList(
			"😲", "😳", "🥺", "🥹", "😦", "😧", "😨", "😰", "😥", "😢",
			"😭", "😱", "😖", "😣", "😞", "😓", "😩", "😫", "🥱", "😤",
			"😠", "😡", "🤬", "👿", "😈", "💀", "☠️", "💩", "🤡", "👹"
		)),
		// 3: Hands & Gestures
		new ArrayList<>(Arrays.asList(
			"👍", "👎", "👊", "✊", "🤛", "🤜", "👏", "🙌", "🫶", "👐",
			"🤲", "🤝", "🙏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙", "👋",
			"🖐️", "✋", "👌", "🤌", "🫳", "🫴", "💪", "🦾", "🖖", "🫵"
		)),
		// 4: Hearts & Love
		new ArrayList<>(Arrays.asList(
			"❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
			"❤️‍🔥", "❤️‍🩹", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟",
			"😍", "🥰", "😘", "💋", "💏", "💑", "🫂", "👩‍❤️‍👨", "💐", "🌹"
		)),
		// 5: Animals
		new ArrayList<>(Arrays.asList(
			"🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐻‍❄️", "🐨",
			"🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐒",
			"🐔", "🐧", "🐦", "🦆", "🦅", "🦉", "🐺", "🐗", "🐴", "🦄"
		)),
		// 6: Nature & Weather
		new ArrayList<>(Arrays.asList(
			"🌸", "💮", "🌹", "🥀", "🌺", "🌻", "🌼", "🌷", "🌱", "🪴",
			"🌲", "🌳", "🌴", "🌵", "🌾", "🍀", "🍁", "🍂", "🍃", "🌍",
			"🌈", "☀️", "🌤️", "⛅", "🌧️", "⛈️", "🌩️", "❄️", "🌊", "🔥"
		)),
		// 7: Food & Drink
		new ArrayList<>(Arrays.asList(
			"🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍒",
			"🍑", "🥭", "🍍", "🥥", "🥝", "🍔", "🍕", "🌮", "🌯", "🍟",
			"🍗", "🥩", "🍣", "🍜", "🍝", "🍰", "🎂", "🍩", "☕", "🍺"
		)),
		// 8: Activities & Sports
		new ArrayList<>(Arrays.asList(
			"⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱",
			"🏓", "🏸", "🏒", "🥊", "🎿", "⛷️", "🏂", "🏋️", "🤸", "🚴",
			"🏊", "🧘", "🎮", "🕹️", "🎲", "🎯", "🏆", "🥇", "🥈", "🥉"
		)),
		// 9: Travel & Places
		new ArrayList<>(Arrays.asList(
			"🚗", "🚕", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "✈️", "🚀",
			"🛸", "🚁", "⛵", "🚢", "🏠", "🏢", "🏰", "🏯", "🗼", "🗽",
			"⛪", "🕌", "🏖️", "🏔️", "🌋", "🗻", "🏕️", "🎡", "🎢", "🎪"
		)),
		// 10: Objects & Tools
		new ArrayList<>(Arrays.asList(
			"📱", "💻", "⌨️", "🖥️", "🖨️", "📷", "🎥", "🎬", "📺", "📻",
			"🔔", "🔕", "📢", "💡", "🔦", "🕯️", "📦", "🔑", "🗝️", "🔒",
			"🔓", "🛠️", "🔧", "🔨", "⚙️", "💎", "💰", "💳", "✉️", "📮"
		)),
		// 11: Symbols & Flags
		new ArrayList<>(Arrays.asList(
			"✅", "❌", "❓", "❗", "‼️", "⁉️", "💯", "🔴", "🟠", "🟡",
			"🟢", "🔵", "🟣", "⚫", "⚪", "🟤", "🔶", "🔷", "♻️", "⚠️",
			"🚫", "🏳️", "🏴", "🏁", "🚩", "⭐", "🌟", "✨", "🎵", "🎶"
		)),
		// 12: Celebration & Fun
		new ArrayList<>(Arrays.asList(
			"🎉", "🎊", "🎈", "🎁", "🎀", "🎄", "🎃", "🎅", "🤶", "🧑‍🎄",
			"🎆", "🎇", "🧨", "✨", "🎗️", "🎟️", "🎫", "🎖️", "🏅", "🎭",
			"🎨", "🎪", "🎤", "🎧", "🎷", "🎸", "🎹", "🥁", "🎺", "🎻"
		)),
		// 13: People & Expressions
		new ArrayList<>(Arrays.asList(
			"😎", "🤓", "🧐", "🤠", "🥳", "🥸", "😺", "😸", "😹", "😻",
			"😼", "😽", "🙀", "😿", "😾", "🫶", "🤷", "🤦", "🙅", "🙆",
			"🙋", "🙇", "💁", "🧏", "🙍", "🙎", "👶", "🧒", "👦", "👧"
		))
	));

	public static boolean isGraphic(char ch) {
		return !(ch < 256 || Character.isLetterOrDigit(ch) || Character.isAlphabetic(ch));
	}

	public static boolean isGraphic(int codePoint) {
		return !(codePoint < 256 || Character.isLetterOrDigit(codePoint) || Character.isAlphabetic(codePoint));
	}

	public static ArrayList<String> getEmoji(Context context, int level) {
		// Level 0 is "Recently Used"
		if (level == 0) {
			return getRecentEmojis(context);
		}

		int staticIndex = level - 1;
		if (staticIndex < 0 || staticIndex >= Emoji.size()) {
			return new ArrayList<>();
		}

		Paint paint = new Paint();
		ArrayList<String> availableEmoji = new ArrayList<>();
		for (String emoji : Emoji.get(staticIndex)) {
			if (paint.hasGlyph(emoji)) {
				availableEmoji.add(emoji);
			}
		}

		return availableEmoji.isEmpty() ? new ArrayList<>(TextEmoticons) : availableEmoji;
	}

	/** @deprecated Use {@link #getEmoji(Context, int)} instead. */
	@Deprecated
	public static ArrayList<String> getEmoji(int level) {
		// Fallback without context — no recent emojis available
		if (level == 0) {
			return new ArrayList<>();
		}

		int staticIndex = level - 1;
		if (staticIndex < 0 || staticIndex >= Emoji.size()) {
			return new ArrayList<>();
		}

		Paint paint = new Paint();
		ArrayList<String> availableEmoji = new ArrayList<>();
		for (String emoji : Emoji.get(staticIndex)) {
			if (paint.hasGlyph(emoji)) {
				availableEmoji.add(emoji);
			}
		}

		return availableEmoji.isEmpty() ? new ArrayList<>(TextEmoticons) : availableEmoji;
	}

	public static int getMaxEmojiLevel() {
		return Emoji.size() + 1; // +1 for "Recently Used"
	}

	public static void recordEmojiUsage(@NonNull Context context, @NonNull String emoji) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String stored = prefs.getString(RECENT_EMOJIS_KEY, "");

		LinkedList<String> recent = new LinkedList<>();
		if (!stored.isEmpty()) {
			recent.addAll(Arrays.asList(stored.split(",")));
		}

		// Move to front if already present, otherwise add to front
		recent.remove(emoji);
		recent.addFirst(emoji);

		// Cap the list
		while (recent.size() > MAX_RECENT_EMOJIS) {
			recent.removeLast();
		}

		prefs.edit().putString(RECENT_EMOJIS_KEY, String.join(",", recent)).apply();
	}

	@NonNull
	private static ArrayList<String> getRecentEmojis(@NonNull Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		String stored = prefs.getString(RECENT_EMOJIS_KEY, "");
		if (stored.isEmpty()) {
			return new ArrayList<>();
		}

		Paint paint = new Paint();
		ArrayList<String> result = new ArrayList<>();
		for (String emoji : stored.split(",")) {
			if (!emoji.isEmpty() && paint.hasGlyph(emoji)) {
				result.add(emoji);
			}
		}
		return result;
	}

	public static boolean isBuiltInEmoji(String emoji) {
		for (ArrayList<String> group : Emoji) {
			if (group.contains(emoji)) {
				return true;
			}
		}

		return false;
	}
}

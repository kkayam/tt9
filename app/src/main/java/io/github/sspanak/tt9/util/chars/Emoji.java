package io.github.sspanak.tt9.util.chars;

import android.graphics.Paint;

import java.util.ArrayList;
import java.util.Arrays;

public class Emoji extends Punctuation {
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

	public static ArrayList<String> getEmoji(int level) {
		if (level < 0 || level >= Emoji.size()) {
			return new ArrayList<>();
		}

		Paint paint = new Paint();
		ArrayList<String> availableEmoji = new ArrayList<>();
		for (String emoji : Emoji.get(level)) {
			if (paint.hasGlyph(emoji)) {
				availableEmoji.add(emoji);
			}
		}

		return availableEmoji.isEmpty() ? new ArrayList<>(TextEmoticons) : availableEmoji;
	}

	public static int getMaxEmojiLevel() {
		return Emoji.size();
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

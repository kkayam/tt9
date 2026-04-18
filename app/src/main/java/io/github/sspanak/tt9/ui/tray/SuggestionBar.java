package io.github.sspanak.tt9.ui.tray;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;

/**
 * Horizontal suggestion bar. Renders each suggestion once and keeps the currently-selected
 * item centered in the viewport. Left/right navigation slides the next item into the center;
 * wrapping past either end snaps selection without visually duplicating items.
 */
public class SuggestionBar {
	private static final long SCROLL_ANIM_MS = 160;
	private static final float ITEM_TEXT_SIZE_SP = 17f;
	private static final int ITEM_PADDING_H_DP = 14;
	private static final int ITEM_PADDING_V_DP = 4;
	private static final int ITEM_CORNER_DP = 6;

	@Nullable private final FrameLayout viewport;
	@Nullable private final LinearLayout list;
	@NonNull private final List<TextView> itemViews = new ArrayList<>();

	private final int textColor;
	private final int selectedTextColor;
	private final int selectedBgColor;

	private int selectedIndex = -1;


	public SuggestionBar(@NonNull View root, @NonNull SettingsStore settings) {
		viewport = root.findViewById(R.id.suggestion_bar);
		list = root.findViewById(R.id.suggestion_bar_list);

		textColor = settings.getKeyboardTextColor();
		selectedTextColor = settings.getSuggestionSelectedColor();
		selectedBgColor = settings.getSuggestionSelectedBackground();

		if (list != null) {
			list.setTranslationX(0);
		}
	}


	public void onSuggestionsSet(@NonNull List<String> suggestions, int newSelectedIndex) {
		if (viewport == null || list == null) {
			return;
		}

		list.animate().cancel();
		list.removeAllViews();
		itemViews.clear();

		if (suggestions.isEmpty()) {
			viewport.setVisibility(View.INVISIBLE);
			selectedIndex = -1;
			return;
		}

		viewport.setVisibility(View.VISIBLE);
		selectedIndex = Math.max(0, Math.min(newSelectedIndex, suggestions.size() - 1));

		Context ctx = viewport.getContext();
		for (String s : suggestions) {
			TextView tv = createItemView(ctx, s);
			list.addView(tv);
			itemViews.add(tv);
		}

		applyHighlight();
		centerSelected(false);
	}


	public void onScrolled(int newSelectedIndex) {
		if (viewport == null || list == null || itemViews.isEmpty()) {
			return;
		}
		if (newSelectedIndex < 0 || newSelectedIndex >= itemViews.size() || newSelectedIndex == selectedIndex) {
			return;
		}

		selectedIndex = newSelectedIndex;
		applyHighlight();
		centerSelected(true);
	}


	private TextView createItemView(@NonNull Context ctx, @NonNull String text) {
		TextView tv = new TextView(ctx);
		tv.setText(text);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, ITEM_TEXT_SIZE_SP);
		tv.setTextColor(textColor);
		tv.setGravity(Gravity.CENTER);
		tv.setSingleLine(true);

		float density = ctx.getResources().getDisplayMetrics().density;
		int padH = Math.round(ITEM_PADDING_H_DP * density);
		int padV = Math.round(ITEM_PADDING_V_DP * density);
		tv.setPadding(padH, padV, padH, padV);

		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT,
			LinearLayout.LayoutParams.MATCH_PARENT);
		tv.setLayoutParams(lp);
		return tv;
	}


	private void applyHighlight() {
		for (int i = 0; i < itemViews.size(); i++) {
			TextView tv = itemViews.get(i);
			if (i == selectedIndex) {
				tv.setTextColor(selectedTextColor);
				tv.setTypeface(null, Typeface.BOLD);
				tv.setBackground(buildPill(selectedBgColor, tv.getContext()));
			} else {
				tv.setTextColor(textColor);
				tv.setTypeface(null, Typeface.NORMAL);
				tv.setBackgroundColor(Color.TRANSPARENT);
			}
		}
	}


	private android.graphics.drawable.GradientDrawable buildPill(int color, @NonNull Context ctx) {
		android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
		d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		d.setColor(color);
		d.setCornerRadius(ITEM_CORNER_DP * ctx.getResources().getDisplayMetrics().density);
		return d;
	}


	/**
	 * Translate the list so the selected item's center aligns with the viewport's center.
	 * Runs after layout so child widths are measured.
	 */
	private void centerSelected(boolean animate) {
		if (viewport == null || list == null || selectedIndex < 0 || selectedIndex >= itemViews.size()) {
			return;
		}

		final TextView sel = itemViews.get(selectedIndex);
		final Runnable apply = () -> {
			float targetX = viewport.getWidth() / 2f - (sel.getX() + sel.getWidth() / 2f);
			if (animate) {
				list.animate().translationX(targetX).setDuration(SCROLL_ANIM_MS).start();
			} else {
				list.setTranslationX(targetX);
			}
		};

		if (sel.getWidth() > 0 && viewport.getWidth() > 0) {
			apply.run();
		} else {
			list.post(apply);
		}
	}
}

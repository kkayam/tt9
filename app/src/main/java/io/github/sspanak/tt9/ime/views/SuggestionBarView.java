package io.github.sspanak.tt9.ime.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.helpers.SuggestionOps;

/**
 * Suggestion bar that keeps the selected word dead-centered and arranges the remaining
 * visible suggestions around it.
 *
 * Layout rules:
 *  - Selected word is centered horizontally.
 *  - Left/right scroll shifts the next suggestion into the center (no animation).
 *  - Suggestions wrap around but never appear twice (each visible entry is drawn exactly once).
 *  - With exactly two suggestions, the non-selected one is always drawn on the right.
 */
public class SuggestionBarView extends View {
	private static final float GAP_DP = 20f;
	private static final float SELECTED_PAD_H_DP = 12f;
	private static final float SELECTED_PAD_V_DP = 4f;
	private static final float SELECTED_RADIUS_DP = 6f;
	private static final float TEXT_SIZE_DP = 15f;
	private static final float SELECTED_TEXT_SIZE_DP = 21f;

	@Nullable private SuggestionOps suggestionOps;

	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint selectedTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint selectedBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	private final float gapPx;
	private final float selectedPadHPx;
	private final float selectedPadVPx;
	private final float selectedRadiusPx;


	public SuggestionBarView(Context context) {
		super(context);

		final float density = context.getResources().getDisplayMetrics().density;
		gapPx = GAP_DP * density;
		selectedPadHPx = SELECTED_PAD_H_DP * density;
		selectedPadVPx = SELECTED_PAD_V_DP * density;
		selectedRadiusPx = SELECTED_RADIUS_DP * density;

		final float textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DP, context.getResources().getDisplayMetrics());
		final float selectedTextSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SELECTED_TEXT_SIZE_DP, context.getResources().getDisplayMetrics());

		textPaint.setTextSize(textSizePx);
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setColor(getColor(context, R.color.keyboard_text, Color.DKGRAY));

		selectedTextPaint.setTextSize(selectedTextSizePx);
		selectedTextPaint.setTextAlign(Paint.Align.CENTER);
		selectedTextPaint.setColor(getColor(context, R.color.suggestion_selected_text, Color.BLACK));

		selectedBgPaint.setColor(getColor(context, R.color.suggestion_selected_background, 0xFF8CB7F9));
		selectedBgPaint.setStyle(Paint.Style.FILL);

		setBackgroundColor(getColor(context, R.color.keyboard_background, 0xFFE8EAED));
	}


	public void attach(@NonNull SuggestionOps ops) {
		this.suggestionOps = ops;
		ops.setChangeListener(this::refresh);
	}


	public void detach() {
		if (suggestionOps != null) {
			suggestionOps.setChangeListener(null);
			suggestionOps = null;
		}
	}


	private void refresh() {
		post(this::invalidate);
	}


	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		final int width = resolveSize(
			getSuggestedMinimumWidth(),
			widthMeasureSpec
		);
		final int desiredHeight = getResources().getDimensionPixelSize(R.dimen.suggestion_bar_height);
		final int height = resolveSize(desiredHeight, heightMeasureSpec);
		setMeasuredDimension(width, height);
	}


	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (suggestionOps == null) return;

		final List<String> items = suggestionOps.getVisibleSuggestions();
		final int n = items.size();
		if (n == 0) return;

		final int selectedIdx = clamp(suggestionOps.getCurrentIndex(), 0, n - 1);
		final String selected = items.get(selectedIdx);

		final float centerX = getWidth() / 2f;
		final float baselineY = (getHeight() - (textPaint.descent() + textPaint.ascent())) / 2f;

		// Selected word background.
		final float selectedWidth = selectedTextPaint.measureText(selected);
		final float bgLeft = centerX - selectedWidth / 2f - selectedPadHPx;
		final float bgRight = centerX + selectedWidth / 2f + selectedPadHPx;
		final float bgTop = baselineY + selectedTextPaint.ascent() - selectedPadVPx;
		final float bgBottom = baselineY + selectedTextPaint.descent() + selectedPadVPx;
		canvas.drawRoundRect(new RectF(bgLeft, bgTop, bgRight, bgBottom), selectedRadiusPx, selectedRadiusPx, selectedBgPaint);

		canvas.drawText(selected, centerX, baselineY, selectedTextPaint);

		// Partition the remaining items onto the right or left based on ring distance to selected.
		// Rule: forwardDist <= backwardDist → right. With n == 2 both equal 1, so the single
		// other item lands on the right as required.
		final List<String> rightItems = new ArrayList<>();
		final List<String> leftItems = new ArrayList<>();
		for (int offset = 1; offset < n; offset++) {
			final int forwardDist = offset;
			final int backwardDist = n - offset;
			if (forwardDist <= backwardDist) {
				rightItems.add(items.get((selectedIdx + offset) % n));
			}
		}
		for (int offset = 1; offset < n; offset++) {
			final int forwardDist = n - offset;
			final int backwardDist = offset;
			if (forwardDist > backwardDist) {
				leftItems.add(items.get((selectedIdx - offset + n) % n));
			}
		}

		float rightCursor = centerX + selectedWidth / 2f + selectedPadHPx + gapPx;
		for (String word : rightItems) {
			final float w = textPaint.measureText(word);
			final float cx = rightCursor + w / 2f;
			if (cx - w / 2f >= getWidth()) break;
			canvas.drawText(word, cx, baselineY, textPaint);
			rightCursor += w + gapPx;
		}

		float leftCursor = centerX - selectedWidth / 2f - selectedPadHPx - gapPx;
		for (String word : leftItems) {
			final float w = textPaint.measureText(word);
			final float cx = leftCursor - w / 2f;
			if (cx + w / 2f <= 0) break;
			canvas.drawText(word, cx, baselineY, textPaint);
			leftCursor -= w + gapPx;
		}
	}


	private static int clamp(int value, int lo, int hi) {
		return Math.max(lo, Math.min(hi, value));
	}


	private static int getColor(Context context, int resId, int fallback) {
		try {
			return context.getResources().getColor(resId, context.getTheme());
		} catch (Exception e) {
			return fallback;
		}
	}
}

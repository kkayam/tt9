package io.github.sspanak.tt9.ime.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.helpers.SuggestionOps;
import io.github.sspanak.tt9.preferences.settings.SettingsStore;

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
	private static final float MODE_TEXT_SIZE_DP = 16f;
	private static final float MIC_ICON_SIZE_DP = 24f;
	private static final float MIC_ICON_MARGIN_DP = 12f;
	private static final long FLASH_DURATION_MS = 2000L;

	@Nullable private SuggestionOps suggestionOps;

	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint selectedTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint selectedBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint modePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	@NonNull private String modeText = "";
	@NonNull private String langText = "";
	private long flashUntilMs = 0L;
	@NonNull private final Handler flashHandler = new Handler(Looper.getMainLooper());

	private final float gapPx;
	private final float selectedPadHPx;
	private final float selectedPadVPx;
	private final float selectedRadiusPx;
	private final float micIconSizePx;
	private final float micIconMarginPx;

	@Nullable private Drawable micIcon;
	@Nullable private Runnable onMicClick;
	@Nullable private java.util.function.BooleanSupplier isMicVisible;
	private final RectF micHitRect = new RectF();
	private boolean micPressed = false;


	public SuggestionBarView(Context context) {
		super(context);

		final float density = context.getResources().getDisplayMetrics().density;
		gapPx = GAP_DP * density;
		selectedPadHPx = SELECTED_PAD_H_DP * density;
		selectedPadVPx = SELECTED_PAD_V_DP * density;
		selectedRadiusPx = SELECTED_RADIUS_DP * density;
		micIconSizePx = MIC_ICON_SIZE_DP * density;
		micIconMarginPx = MIC_ICON_MARGIN_DP * density;

		micIcon = ContextCompat.getDrawable(context, R.drawable.ic_fn_voice);
		if (micIcon != null) {
			micIcon = micIcon.mutate();
			micIcon.setColorFilter(new PorterDuffColorFilter(getColor(context, R.color.keyboard_text, Color.DKGRAY), PorterDuff.Mode.SRC_IN));
		}

		final float fontScale = new SettingsStore(context).getSuggestionFontScale();
		final float textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DP * fontScale, context.getResources().getDisplayMetrics());
		final float selectedTextSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SELECTED_TEXT_SIZE_DP * fontScale, context.getResources().getDisplayMetrics());

		textPaint.setTextSize(textSizePx);
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setColor(getColor(context, R.color.keyboard_text, Color.DKGRAY));

		selectedTextPaint.setTextSize(selectedTextSizePx);
		selectedTextPaint.setTextAlign(Paint.Align.CENTER);
		selectedTextPaint.setColor(getColor(context, R.color.suggestion_selected_text, Color.BLACK));

		selectedBgPaint.setColor(getColor(context, R.color.suggestion_selected_background, 0xFF8CB7F9));
		selectedBgPaint.setStyle(Paint.Style.FILL);

		final float modeTextSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, MODE_TEXT_SIZE_DP, context.getResources().getDisplayMetrics());
		modePaint.setTextSize(modeTextSizePx);
		modePaint.setTextAlign(Paint.Align.CENTER);
		modePaint.setColor(getColor(context, R.color.keyboard_text, Color.DKGRAY));

		setBackgroundColor(getColor(context, R.color.keyboard_background, 0xFFE8EAED));
	}


	public void attach(@NonNull SuggestionOps ops) {
		this.suggestionOps = ops;
		ops.setChangeListener(this::refresh);
	}


	public void setMicButton(@Nullable java.util.function.BooleanSupplier isVisible, @Nullable Runnable onClick) {
		this.isMicVisible = isVisible;
		this.onMicClick = onClick;
		refresh();
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


	/**
	 * Updates the mode + language label. If the label actually changed and suggestions are
	 * currently on-screen, the label flashes centered for {@link #FLASH_DURATION_MS}.
	 */
	public void setModeInfo(@Nullable String mode, @Nullable String language) {
		final String newMode = mode == null ? "" : mode;
		final String newLang = language == null ? "" : language;
		final boolean changed = !newMode.equals(modeText) || !newLang.equals(langText);

		modeText = newMode;
		langText = newLang;

		if (changed && suggestionOps != null && !suggestionOps.isEmpty()) {
			flashUntilMs = System.currentTimeMillis() + FLASH_DURATION_MS;
			flashHandler.removeCallbacksAndMessages(null);
			flashHandler.postDelayed(this::invalidate, FLASH_DURATION_MS);
		}

		refresh();
	}


	@NonNull
	private String buildModeLabel() {
		return modeText;
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
		final boolean flashing = System.currentTimeMillis() < flashUntilMs;

		if (n == 0 || flashing) {
			drawModeLabel(canvas);
			drawMicButton(canvas);
			return;
		}

		micHitRect.setEmpty();

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


	private boolean isMicButtonShown() {
		return micIcon != null && onMicClick != null && (isMicVisible == null || isMicVisible.getAsBoolean());
	}


	private void drawMicButton(Canvas canvas) {
		if (!isMicButtonShown()) {
			micHitRect.setEmpty();
			return;
		}

		final float cy = getHeight() / 2f;
		final float right = getWidth() - micIconMarginPx;
		final float left = right - micIconSizePx;
		final float top = cy - micIconSizePx / 2f;
		final float bottom = cy + micIconSizePx / 2f;

		micIcon.setBounds((int) left, (int) top, (int) right, (int) bottom);
		micIcon.setAlpha(micPressed ? 140 : 255);
		micIcon.draw(canvas);

		// Enlarge touch area around the drawn icon.
		micHitRect.set(left - micIconMarginPx, 0, getWidth(), getHeight());
	}


	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (!isMicButtonShown() || micHitRect.isEmpty()) {
			return super.onTouchEvent(event);
		}

		final float x = event.getX();
		final float y = event.getY();
		final boolean inside = micHitRect.contains(x, y);

		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				if (!inside) return super.onTouchEvent(event);
				micPressed = true;
				invalidate();
				return true;
			case MotionEvent.ACTION_MOVE:
				if (micPressed && !inside) {
					micPressed = false;
					invalidate();
				}
				return micPressed;
			case MotionEvent.ACTION_UP:
				if (micPressed) {
					micPressed = false;
					invalidate();
					if (inside && onMicClick != null) {
						performClick();
						onMicClick.run();
					}
					return true;
				}
				break;
			case MotionEvent.ACTION_CANCEL:
				if (micPressed) {
					micPressed = false;
					invalidate();
					return true;
				}
				break;
		}
		return super.onTouchEvent(event);
	}


	@Override
	public boolean performClick() {
		return super.performClick();
	}


	private void drawModeLabel(Canvas canvas) {
		final String label = buildModeLabel();
		if (label.isEmpty()) return;
		final float centerX = getWidth() / 2f;
		final float baselineY = (getHeight() - (modePaint.descent() + modePaint.ascent())) / 2f;
		canvas.drawText(label, centerX, baselineY, modePaint);
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

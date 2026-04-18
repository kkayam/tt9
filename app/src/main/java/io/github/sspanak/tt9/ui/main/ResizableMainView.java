package io.github.sspanak.tt9.ui.main;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.ime.TraditionalT9;

/**
 * Thin view wrapper kept around so swipe-capable soft keys still have something to invoke.
 * Layout switching, drag-resize, and alignment were dropped when the keyboard was reduced
 * to a single layout; the hooks remain as no-ops.
 */
public class ResizableMainView extends StaticMainView implements View.OnAttachStateChangeListener {

	public ResizableMainView(TraditionalT9 tt9) {
		super(tt9);
	}


	@Nullable
	@Override
	public View getView() {
		final View view = super.getView();
		if (view != null) {
			view.removeOnAttachStateChangeListener(this);
			view.addOnAttachStateChangeListener(this);
		}

		return view;
	}


	@Override
	public boolean create() {
		return super.create() && main != null;
	}


	@Override
	public void destroy() {
		if (main != null && main.getView() != null) {
			main.getView().removeOnAttachStateChangeListener(this);
		}
		super.destroy();
	}


	@Override public void onViewDetachedFromWindow(@NonNull View v) {}
	@Override public void onViewAttachedToWindow(@NonNull View v) {
		if (main != null) {
			main.setPadding();
		}
	}


	public void onOrientationChanged() {
		showKeyboard();
		render();
	}

	public void onAlign(float deltaX) {}
	public void onResizeStart(float startY) {}
	public void onResize(float currentY) {}
	public void onResizeThrottled(float currentY) {}
	public void onSnap() {}
}

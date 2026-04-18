package io.github.sspanak.tt9.ui.main;

import android.content.res.Resources;

import androidx.annotation.NonNull;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.ime.TraditionalT9;
import io.github.sspanak.tt9.util.sys.DeviceInfo;

class MainLayoutSmall extends MainLayoutExtraPanel {
	protected int height;


	MainLayoutSmall(TraditionalT9 tt9) {
		super(tt9, R.layout.main_small);
	}


	@Override
	int getHeight(boolean forceRecalculate) {
		if (height <= 0 || forceRecalculate) {
			Resources resources = tt9.getResources();
			height = resources.getDimensionPixelSize(R.dimen.suggestion_bar_height);
		}

		return height;
	}


	@Override void showCommandPalette() {}
	@Override void showKeyboard() {}
	@Override void showTextEditingPalette() {}
	@Override boolean isCommandPaletteShown() { return false; }
	@Override boolean isTextEditingPaletteShown() { return false; }


	@Override
	void render() {
		final boolean isPortrait = !DeviceInfo.isLandscapeOrientation(tt9);

		getView();
		setPadding();
		setWidth(tt9.getSettings().getWidthPercent(isPortrait), tt9.getSettings().getAlignment());
		setBackgroundBlending();
	}
}

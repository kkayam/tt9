package io.github.sspanak.tt9.ime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Outcome of a key-handler's decision about whether it wants to consume a key, plus the side
 * effect to run when the handler is actually invoked (as opposed to merely validated).
 *
 * The Android IME lifecycle needs to answer "will this key be consumed?" on {@code onKeyDown} so
 * the event doesn't bubble up into the host UI — but the side effect (committing a word, toggling
 * a mode, scrolling the bar, …) should only run once, on {@code onKeyUp}. Previously every
 * handler duplicated the shape {@code if (validateOnly) return true; …run…; return true;}.
 * Returning a {@code KeyIntent} collapses that: the caller consults {@link #accepted()} on key
 * down and {@link #run()} on key up.
 */
public final class KeyIntent {
	/** Singleton for "I don't want this key". */
	public static final KeyIntent REJECT = new KeyIntent(null);

	/** Consume the key without any side effect (useful for long-press tracking, etc.). */
	public static final KeyIntent ACCEPT_NOOP = new KeyIntent(() -> {});

	@Nullable private final Runnable sideEffect;


	private KeyIntent(@Nullable Runnable sideEffect) {
		this.sideEffect = sideEffect;
	}


	public static KeyIntent accept(@NonNull Runnable sideEffect) {
		return new KeyIntent(sideEffect);
	}


	/** {@code true} if the handler wants to consume the key. */
	public boolean accepted() {
		return sideEffect != null;
	}


	/** Executes the side effect iff accepted; returns {@link #accepted()}. */
	public boolean run() {
		if (sideEffect != null) sideEffect.run();
		return sideEffect != null;
	}
}

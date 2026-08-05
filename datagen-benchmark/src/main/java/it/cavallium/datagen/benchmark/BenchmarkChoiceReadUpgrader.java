package it.cavallium.datagen.benchmark;

import it.cavallium.datagen.benchmark.fixture.v0.upgraders.ViewRootUpgrader.ReadInputChoice;
import it.cavallium.datagen.benchmark.fixture.v0.upgraders.ViewRootUpgrader.ReadUpgraderChoice;

/** Exercises exact-kind union dispatch and typed subtype views without materializing the union. */
public final class BenchmarkChoiceReadUpgrader implements ReadUpgraderChoice {

	@Override
	public long upgrade(ReadInputChoice input) {
		var choice = input.valueView();
		return switch (choice.kind()) {
			case ViewA -> choice.asViewAView().value();
			case ViewB -> choice.asViewBView().value();
			case ViewC -> choice.asViewCView().value();
			case ViewD -> choice.asViewDView().value();
		};
	}
}

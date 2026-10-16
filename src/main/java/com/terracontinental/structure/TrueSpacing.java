package com.terracontinental.structure;

/**
 * The grid a random-spread structure set really generates on (implemented on
 * RandomSpreadStructurePlacement by {@code mixin.RandomSpreadStructurePlacementMixin}). Its
 * {@code spacing()} answers the scaled value, which generation never reads (the placement math
 * reads the field), so anything walking the real grid needs this instead: /continental villages
 * checked only every sixth cell until it did (2026-10-16).
 */
public interface TrueSpacing {

    int terraContinental$trueSpacing();
}

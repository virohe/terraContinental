package com.terracontinental.plan;

/**
 * A coarse, top-down picture of the world plan, for the world-loading screen.
 *
 * <p>The real world plan (continent outline, ranges, rivers, village sites; computed from the seed
 * at world creation) implements this once it exists. Until then {@link PlanPreviews} serves a stub
 * that samples the terrain's continentalness. Implementations are filled on a worker thread and
 * read on the render thread, so {@link #colorAt} must be safe to call while cells are still being
 * written.
 *
 * <p>Cell (0, 0) covers blocks [originX, originX + blocksPerCell) x [originZ, originZ + blocksPerCell);
 * x grows east, z grows south, as on an in-game map.
 */
public interface PlanPreview {

    int cellsX();

    int cellsZ();

    int blocksPerCell();

    /** Block x of cell (0, 0)'s west edge. */
    int originX();

    /** Block z of cell (0, 0)'s north edge. */
    int originZ();

    /** ARGB colour of the cell, or 0 while it isn't known yet (drawn transparent). */
    int colorAt(int cx, int cz);

    /** Share of cells known, 0..1. */
    float progress();
}

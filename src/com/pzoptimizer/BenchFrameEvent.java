package com.pzoptimizer;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * One measured benchmark frame, from one IsoCamera frame boundary to the next on the main thread. Recorded into the
 * JFR profile so bench/jfr_hotspots.py can attribute samples to slow frames only (the 1% lows) instead of averaging
 * them away. Costs nothing when no recording is running.
 */
@Name("pzo.BenchFrame")
@Label("PZO Bench Frame")
@Category("PZO")
@Description("Main-thread frame measured by BenchmarkHarness")
final class BenchFrameEvent extends jdk.jfr.Event {

    @Label("Zoom")
    float zoom;
}

package com.pzoptimizer;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * One benchmark frame as a JFR event, so bench/jfr_hotspots.py can look at just the slow frames instead of averaging
 * them away. Free when nothing is recording.
 */
@Name("pzo.BenchFrame")
@Label("PZO Bench Frame")
@Category("PZO")
@Description("Main-thread frame measured by BenchmarkHarness")
final class BenchFrameEvent extends jdk.jfr.Event {

    @Label("Zoom")
    float zoom;
}

package com.pzoptimizer;

/** The game thread and the game's tick counter, for answers that are only valid within one tick. */
final class FrameStamp {

    private FrameStamp() {}

    static boolean onGameThread() {
        Thread t = zombie.GameWindow.gameThread;
        return t != null && Thread.currentThread() == t;
    }

    /** IngameState's tick counter, or -1 outside a game. */
    static long tick() {
        zombie.gameStates.IngameState s = zombie.gameStates.IngameState.instance;
        return s == null ? -1L : s.numberTicks;
    }
}

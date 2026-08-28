package me.zed_0xff.zombie_buddy;

public class SyntaxSugar {
    public static <T> T or(T val, T defVal) {
        return val != null ? val : defVal;
    }
}

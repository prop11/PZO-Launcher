package me.zed_0xff.zombie_buddy;

public class LuaJSON {
    public static String encode(Object obj) {
        return obj == null ? "null" : obj.toString();
    }
}

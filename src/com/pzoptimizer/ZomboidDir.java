package com.pzoptimizer;

import java.io.File;

/**
 * The game's Zomboid folder (mods, saves, Lua, console.txt), worked out the way ZomboidFileSystem.getCacheDir does it.
 * A -cachedir=<path> launch option moves the whole folder; reading user.home\Zomboid then missed the mod lists, so
 * PZO Enhancements never loaded and PZO's own settings went to a folder the game never reads.
 *
 * Has to work before any game class loads. On Windows the exe starts the JVM itself (no sun.java.command), so
 * PZOEntrypoint hands me its args before the agent loads. With -javaagent (Linux, macOS, servers) premain runs before
 * main, so I read the launcher's command line instead.
 */
public final class ZomboidDir {

    private static final String OPTION = "-cachedir=";
    private static volatile String[] mainArgs;
    private static volatile File resolved;
    private static volatile String source = "user.home";

    private ZomboidDir() {}

    /** Called first thing in PZOEntrypoint.main, before anything asks for the folder. */
    static void setArgs(String[] args) {
        if (args == null) return;
        mainArgs = args.clone();
    }

    public static File get() {
        File f = resolved;
        if (f != null) return f;
        synchronized (ZomboidDir.class) {
            if (resolved == null) resolved = resolve();
            return resolved;
        }
    }

    /** "launch option", "command line", "zomboid.cachedir" or "user.home", for the log. */
    public static String source() {
        get();
        return source;
    }

    public static File sub(String child) {
        return new File(get(), child);
    }

    private static File resolve() {
        String dir = null;
        String[] args = mainArgs;
        if (args != null) {
            dir = fromArgs(args);
            if (dir != null) source = "launch option";
        }
        if (dir == null) {
            dir = fromCommandLine(System.getProperty("sun.java.command"));
            if (dir != null) source = "command line";
        }
        if (dir == null) {
            String prop = System.getProperty("zomboid.cachedir");
            if (prop != null && !prop.trim().isEmpty()) {
                dir = prop.trim();
                source = "zomboid.cachedir";
            }
        }
        if (dir != null) return new File(dir);
        source = "user.home";
        // Same as the game: deployment.user.cachedir only counts off Windows
        String root = System.getProperty("deployment.user.cachedir");
        String os = System.getProperty("os.name", "");
        if (root == null || os.startsWith("Win")) root = System.getProperty("user.home");
        return new File(root, "Zomboid");
    }

    // MainScreenState and GameServer: args[n].startsWith("-cachedir=") -> the rest, trimmed. Last one wins there too.
    private static String fromArgs(String[] args) {
        String dir = null;
        for (String a : args) {
            if (a == null || !a.startsWith(OPTION)) continue;
            String v = unquote(a.substring(OPTION.length()).trim());
            if (!v.isEmpty()) dir = v;
        }
        return dir;
    }

    // sun.java.command is the main class and its args joined by spaces, so a path with spaces runs on to the next " -"
    static String fromCommandLine(String cmd) {
        if (cmd == null) return null;
        String dir = null;
        int at = -1;
        while ((at = cmd.indexOf(OPTION, at + 1)) >= 0) {
            if (at > 0 && cmd.charAt(at - 1) != ' ') continue;
            int start = at + OPTION.length();
            int end = cmd.indexOf(" -", start);
            String v = unquote((end < 0 ? cmd.substring(start) : cmd.substring(start, end)).trim());
            if (!v.isEmpty()) dir = v;
        }
        return dir;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) return v.substring(1, v.length() - 1).trim();
        return v;
    }
}

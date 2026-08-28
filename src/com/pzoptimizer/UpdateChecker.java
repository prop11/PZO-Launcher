package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {
    public static final String CURRENT_VERSION = "0.3.0";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/prop11/PZO-Launcher/releases/latest";
    private static final String RELEASE_URL = "https://github.com/prop11/PZO-Launcher/releases/latest";

    public static void checkForUpdatesAsync() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2000);
                check();
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.setName("PZO-GitHubUpdateChecker");
        t.start();
    }

    private static void check() {
        try {
            URL url = new URL(GITHUB_API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "PZO-UpdateChecker");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);

            int code = conn.getResponseCode();
            if (code != 200) {
                writeStatus(false, CURRENT_VERSION);
                return;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            String json = response.toString();
            String latestTag = extractTag(json);
            if (latestTag == null || latestTag.isEmpty()) {
                writeStatus(false, CURRENT_VERSION);
                return;
            }

            String cleanLatest = latestTag.replaceAll("(?i)^v", "").trim();
            String cleanCurrent = CURRENT_VERSION.replaceAll("(?i)^v", "").trim();

            boolean hasUpdate = isNewerVersion(cleanLatest, cleanCurrent);
            writeStatus(hasUpdate, cleanLatest);
        } catch (Exception e) {
            writeStatus(false, CURRENT_VERSION);
        }
    }

    private static String extractTag(String json) {
        int idx = json.indexOf("\"tag_name\":");
        if (idx == -1) return null;
        int start = json.indexOf("\"", idx + 11);
        if (start == -1) return null;
        int end = json.indexOf("\"", start + 1);
        if (end == -1) return null;
        return json.substring(start + 1, end);
    }

    private static boolean isNewerVersion(String latest, String current) {
        try {
            String[] lParts = latest.split("\\.");
            String[] cParts = current.split("\\.");
            int length = Math.max(lParts.length, cParts.length);
            for (int i = 0; i < length; i++) {
                int lNum = i < lParts.length ? Integer.parseInt(lParts[i].replaceAll("\\D", "")) : 0;
                int cNum = i < cParts.length ? Integer.parseInt(cParts[i].replaceAll("\\D", "")) : 0;
                if (lNum > cNum) return true;
                if (lNum < cNum) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void writeStatus(boolean hasUpdate, String latestVersion) {
        try {
            String userHome = System.getProperty("user.home");
            File luaDir = new File(userHome, "Zomboid" + File.separator + "Lua");
            if (!luaDir.exists()) luaDir.mkdirs();
            File outFile = new File(luaDir, "pzo_update.json");

            String json = String.format(
                "{\"has_update\": %b, \"latest_version\": \"%s\", \"current_version\": \"%s\", \"url\": \"%s\"}",
                hasUpdate, latestVersion, CURRENT_VERSION, RELEASE_URL
            );

            FileWriter fw = new FileWriter(outFile, false);
            fw.write(json);
            fw.close();
        } catch (Exception ignored) {}
    }
}

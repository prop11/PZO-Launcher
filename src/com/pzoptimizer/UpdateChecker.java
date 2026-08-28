package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateChecker {
    public static final String CURRENT_VERSION = "0.4.4";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/prop11/PZO-Launcher/releases/latest";
    private static final String RELEASE_URL = "https://github.com/prop11/PZO-Launcher/releases/latest";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");

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

    public static void check() {
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
            String releaseName = extractJsonField(json, "name");
            String tagName = extractJsonField(json, "tag_name");

            String latestVersion = extractVersionNumber(releaseName);
            if (latestVersion == null) {
                latestVersion = extractVersionNumber(tagName);
            }
            if (latestVersion == null) {
                latestVersion = (releaseName != null && !releaseName.isEmpty()) ? releaseName : tagName;
            }
            if (latestVersion == null || latestVersion.isEmpty()) {
                latestVersion = CURRENT_VERSION;
            }

            boolean hasUpdate = isNewerVersion(latestVersion, CURRENT_VERSION);
            writeStatus(hasUpdate, latestVersion);
        } catch (Exception e) {
            writeStatus(false, CURRENT_VERSION);
        }
    }

    private static String extractJsonField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":";
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int start = json.indexOf("\"", idx + key.length());
        if (start == -1) return null;
        int end = json.indexOf("\"", start + 1);
        if (end == -1) return null;
        return json.substring(start + 1, end);
    }

    private static String extractVersionNumber(String text) {
        if (text == null) return null;
        Matcher m = VERSION_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
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

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
    public static final String CURRENT_VERSION = "0.8.0";
    private static final String GITHUB_LATEST_API_URL = "https://api.github.com/repos/prop11/PZO-Launcher/releases/latest";
    private static final String GITHUB_ALL_RELEASES_API_URL = "https://api.github.com/repos/prop11/PZO-Launcher/releases";
    private static final String DEFAULT_JAR_DOWNLOAD_URL = "https://github.com/prop11/PZO-Launcher/releases/latest/download/PZOptimEngine.jar";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    public static class UpdateResult {
        public boolean hasUpdate = false;
        public String latestVersion = CURRENT_VERSION;
        public String downloadUrl = DEFAULT_JAR_DOWNLOAD_URL;
        public boolean isBeta = false;
    }

    public static UpdateResult checkForUpdatesSync(int timeoutMs) {
        UpdateResult res = new UpdateResult();
        boolean betaOptIn = PZOConfig.isBetaOptIn();
        res.isBeta = betaOptIn;

        try {
            String apiUrl = betaOptIn ? GITHUB_ALL_RELEASES_API_URL : GITHUB_LATEST_API_URL;
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "PZO-UpdateChecker");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            int code = conn.getResponseCode();
            if (code != 200) {
                writeStatus(false, CURRENT_VERSION);
                return res;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            String json = response.toString().trim();
            String releaseName = null;
            String tagName = null;
            String downloadUrl = DEFAULT_JAR_DOWNLOAD_URL;

            if (betaOptIn && json.startsWith("[")) {
                // Parse releases array: look for releases tagged with unstable / beta or the newest pre-release
                int firstObjEnd = json.indexOf("},");
                String firstReleaseJson = firstObjEnd != -1 ? json.substring(0, firstObjEnd + 1) : json;

                // Scan through releases to find unstable/beta release or top release
                releaseName = extractJsonField(firstReleaseJson, "name");
                tagName = extractJsonField(firstReleaseJson, "tag_name");
                
                String jarAssetUrl = extractDownloadUrlForAsset(firstReleaseJson, "PZOptimEngine.jar");
                if (jarAssetUrl != null) {
                    downloadUrl = jarAssetUrl;
                }
            } else {
                releaseName = extractJsonField(json, "name");
                tagName = extractJsonField(json, "tag_name");
                String jarAssetUrl = extractDownloadUrlForAsset(json, "PZOptimEngine.jar");
                if (jarAssetUrl != null) {
                    downloadUrl = jarAssetUrl;
                }
            }

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

            res.hasUpdate = hasUpdate;
            res.latestVersion = latestVersion;
            res.downloadUrl = downloadUrl;
            return res;
        } catch (Exception e) {
            writeStatus(false, CURRENT_VERSION);
            return res;
        }
    }

    private static String extractDownloadUrlForAsset(String json, String assetName) {
        int assetIdx = json.indexOf("\"name\":\"" + assetName + "\"");
        if (assetIdx == -1) {
            assetIdx = json.indexOf("\"name\": \"" + assetName + "\"");
        }
        if (assetIdx != -1) {
            String key = "\"browser_download_url\":";
            int urlKeyIdx = json.indexOf(key, assetIdx);
            if (urlKeyIdx != -1) {
                int start = json.indexOf("\"", urlKeyIdx + key.length());
                if (start != -1) {
                    int end = json.indexOf("\"", start + 1);
                    if (end != -1) {
                        return json.substring(start + 1, end);
                    }
                }
            }
        }
        return null;
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
            int maxLen = Math.max(lParts.length, cParts.length);

            for (int i = 0; i < maxLen; i++) {
                int lNum = i < lParts.length ? Integer.parseInt(lParts[i].replaceAll("\\D+", "")) : 0;
                int cNum = i < cParts.length ? Integer.parseInt(cParts[i].replaceAll("\\D+", "")) : 0;
                if (lNum > cNum) return true;
                if (lNum < cNum) return false;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeStatus(boolean hasUpdate, String latestVer) {
        try {
            String userHome = System.getProperty("user.home");
            if (userHome == null) return;
            File luaDir = new File(userHome, "Zomboid" + File.separator + "Lua");
            if (!luaDir.exists()) luaDir.mkdirs();

            File updateFile = new File(luaDir, "pzo_update.json");
            String json = String.format("{\"has_update\": %b, \"current_version\": \"%s\", \"latest_version\": \"%s\", \"beta_channel\": %b}",
                hasUpdate, CURRENT_VERSION, latestVer, PZOConfig.isBetaOptIn());

            try (FileWriter fw = new FileWriter(updateFile, false)) {
                fw.write(json);
            }
        } catch (Throwable ignored) {}
    }
}

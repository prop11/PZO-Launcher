package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PZO Multi-Channel Update Checker.
 * Supports strict channel isolation between Stable (releases/latest) and Beta/Unstable (releases list).
 * 100% pure Java with zero external dependencies.
 */
public class UpdateChecker {
    public static final String CURRENT_VERSION = "0.9.5-unstable";
    private static final String GITHUB_LATEST_API_URL = "https://api.github.com/repos/prop11/PZO-Launcher/releases/latest";
    private static final String GITHUB_ALL_RELEASES_API_URL = "https://api.github.com/repos/prop11/PZO-Launcher/releases";
    private static final String DEFAULT_JAR_DOWNLOAD_URL = "https://github.com/prop11/PZO-Launcher/releases/latest/download/PZOptimEngine.jar";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    public static String getNativeFileName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "pzo_native64.dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "libpzo_native64.dylib";
        } else {
            return "libpzo_native64.so";
        }
    }

    public static String getDefaultNativeDownloadUrl() {
        String nativeFile = getNativeFileName();
        boolean beta = PZOConfig.isBetaOptIn() || isUnstableIdentifier(CURRENT_VERSION);
        if (beta) {
            String cleanVer = CURRENT_VERSION.trim();
            String vTag = cleanVer.startsWith("v") || cleanVer.startsWith("V") ? cleanVer : "V" + cleanVer;
            return "https://github.com/prop11/PZO-Launcher/releases/download/" + vTag + "/" + nativeFile;
        }
        return "https://github.com/prop11/PZO-Launcher/releases/latest/download/" + nativeFile;
    }

    /**
     * Resolves the direct download URL for the native library matching the requested version
     * or active release channel (including beta/unstable prereleases if opted-in or unstable build).
     */
    public static String resolveNativeDownloadUrl(String targetVersion, int timeoutMs) {
        String nativeFileName = getNativeFileName();
        String cleanVer = targetVersion != null && !targetVersion.isEmpty() ? targetVersion.trim() : CURRENT_VERSION;
        String vTag = cleanVer.startsWith("v") || cleanVer.startsWith("V") ? cleanVer : "V" + cleanVer;
        String directFallback = "https://github.com/prop11/PZO-Launcher/releases/download/" + vTag + "/" + nativeFileName;

        boolean betaOptIn = PZOConfig.isBetaOptIn() || isUnstableIdentifier(cleanVer);

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
            if (code == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
                in.close();

                String json = response.toString().trim();
                List<String> releases = new ArrayList<>();
                if (json.startsWith("[")) {
                    releases = splitJsonArrayObjects(json);
                } else if (json.startsWith("{")) {
                    releases.add(json);
                }

                // Pass 1: Look for exact tag or version match
                for (String relJson : releases) {
                    if (extractJsonBooleanField(relJson, "draft")) continue;
                    String tag = extractJsonField(relJson, "tag_name");
                    String name = extractJsonField(relJson, "name");
                    boolean tagMatches = tag != null && (tag.equalsIgnoreCase(cleanVer) || tag.equalsIgnoreCase(vTag) || tag.equalsIgnoreCase("v" + cleanVer));
                    boolean nameMatches = name != null && name.contains(cleanVer);

                    if (tagMatches || nameMatches) {
                        String assetUrl = extractDownloadUrlForAsset(relJson, nativeFileName);
                        if ((assetUrl == null || assetUrl.isEmpty()) && nativeFileName.endsWith(".dll")) {
                            assetUrl = extractDownloadUrlForAsset(relJson, "pzo_native64.dll");
                        }
                        if (assetUrl != null && !assetUrl.isEmpty()) {
                            return assetUrl;
                        }
                    }
                }

                // Pass 2: Look for highest compatible release on the channel containing native asset
                for (String relJson : releases) {
                    if (extractJsonBooleanField(relJson, "draft")) continue;
                    boolean isPrerelease = extractJsonBooleanField(relJson, "prerelease");
                    String tag = extractJsonField(relJson, "tag_name");
                    String name = extractJsonField(relJson, "name");
                    boolean hasUnstableTag = isUnstableIdentifier(tag) || isUnstableIdentifier(name);

                    if (!betaOptIn && (isPrerelease || hasUnstableTag)) {
                        continue;
                    }

                    String assetUrl = extractDownloadUrlForAsset(relJson, nativeFileName);
                    if ((assetUrl == null || assetUrl.isEmpty()) && nativeFileName.endsWith(".dll")) {
                        assetUrl = extractDownloadUrlForAsset(relJson, "pzo_native64.dll");
                    }
                    if (assetUrl != null && !assetUrl.isEmpty()) {
                        return assetUrl;
                    }
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("Notice: Could not query GitHub releases API for native library URL: " + t.getMessage());
        }

        return directFallback;
    }

    public static class UpdateResult {
        public boolean hasUpdate = false;
        public String latestVersion = CURRENT_VERSION;
        public String tagName = "";
        public String downloadUrl = DEFAULT_JAR_DOWNLOAD_URL;
        public String nativeFileName = getNativeFileName();
        public String nativeDownloadUrl = getDefaultNativeDownloadUrl();
        public String dllDownloadUrl = nativeDownloadUrl;
        public boolean isBeta = false;
        public String channel = "Stable";
    }

    public static UpdateResult checkForUpdatesSync(int timeoutMs) {
        UpdateResult res = new UpdateResult();
        boolean betaOptIn = PZOConfig.isBetaOptIn();
        res.isBeta = betaOptIn;
        res.channel = betaOptIn ? "Beta / Unstable" : "Stable";

        try {
            // Beta opt-in queries the full releases list; Stable queries /releases/latest
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
                writeStatus(false, CURRENT_VERSION, res.channel);
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
            String selectedReleaseJson = null;

            if (json.startsWith("[")) {
                // Parse array of releases and find the highest available version matching channel
                List<String> releases = splitJsonArrayObjects(json);
                String bestRelJson = null;
                String bestVersion = null;

                for (String relJson : releases) {
                    boolean isDraft = extractJsonBooleanField(relJson, "draft");
                    if (isDraft) continue;

                    boolean isPrerelease = extractJsonBooleanField(relJson, "prerelease");
                    String tag = extractJsonField(relJson, "tag_name");
                    String name = extractJsonField(relJson, "name");
                    boolean hasUnstableTag = isUnstableIdentifier(tag) || isUnstableIdentifier(name);

                    if (!betaOptIn) {
                        // NORMAL (STABLE) USER: Strictly reject all prereleases and unstable/beta tagged builds
                        if (isPrerelease || hasUnstableTag) {
                            continue;
                        }
                    }

                    String candVer = extractVersionNumber(name);
                    if (candVer == null) {
                        candVer = extractVersionNumber(tag);
                    }
                    if (candVer == null) continue;

                    if (bestVersion == null || isNewerVersion(candVer, bestVersion)) {
                        bestVersion = candVer;
                        bestRelJson = relJson;
                    }
                }
                selectedReleaseJson = bestRelJson;
            } else if (json.startsWith("{")) {
                // Single release object (e.g. from /releases/latest)
                boolean isPrerelease = extractJsonBooleanField(json, "prerelease");
                String tag = extractJsonField(json, "tag_name");
                String name = extractJsonField(json, "name");
                boolean hasUnstableTag = isUnstableIdentifier(tag) || isUnstableIdentifier(name);

                // Safety guard: If normal user somehow received an unstable release, reject it
                if (!betaOptIn && (isPrerelease || hasUnstableTag)) {
                    writeStatus(false, CURRENT_VERSION, res.channel);
                    return res;
                }

                selectedReleaseJson = json;
            }

            if (selectedReleaseJson == null) {
                writeStatus(false, CURRENT_VERSION, res.channel);
                return res;
            }

            String releaseName = extractJsonField(selectedReleaseJson, "name");
            String tagName = extractJsonField(selectedReleaseJson, "tag_name");
            String jarAssetUrl = extractDownloadUrlForAsset(selectedReleaseJson, "PZOptimEngine.jar");
            String nativeFileName = getNativeFileName();
            String nativeAssetUrl = extractDownloadUrlForAsset(selectedReleaseJson, nativeFileName);
            if ((nativeAssetUrl == null || nativeAssetUrl.isEmpty()) && nativeFileName.endsWith(".dll")) {
                nativeAssetUrl = extractDownloadUrlForAsset(selectedReleaseJson, "pzo_native64.dll");
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

            String displayVersion = latestVersion;
            if ((tagName != null && isUnstableIdentifier(tagName)) || (releaseName != null && isUnstableIdentifier(releaseName))) {
                if (!displayVersion.toLowerCase().contains("unstable")) {
                    displayVersion = displayVersion + "-unstable";
                }
            }

            boolean hasUpdate = isNewerVersion(latestVersion, CURRENT_VERSION);
            String downloadUrl = (jarAssetUrl != null && !jarAssetUrl.isEmpty()) ? jarAssetUrl : DEFAULT_JAR_DOWNLOAD_URL;
            String defaultNativeUrl = getDefaultNativeDownloadUrl();
            String nativeUrl = (nativeAssetUrl != null && !nativeAssetUrl.isEmpty()) ? nativeAssetUrl :
                (tagName != null && !tagName.isEmpty() ?
                    "https://github.com/prop11/PZO-Launcher/releases/download/" + tagName + "/" + nativeFileName :
                    defaultNativeUrl);

            writeStatus(hasUpdate, displayVersion, res.channel, downloadUrl, nativeUrl);

            res.hasUpdate = hasUpdate;
            res.latestVersion = displayVersion;
            res.tagName = tagName != null ? tagName : "";
            res.downloadUrl = downloadUrl;
            res.nativeFileName = nativeFileName;
            res.nativeDownloadUrl = nativeUrl;
            res.dllDownloadUrl = nativeUrl;
            return res;
        } catch (Exception e) {
            writeStatus(false, CURRENT_VERSION, res.channel, DEFAULT_JAR_DOWNLOAD_URL, getDefaultNativeDownloadUrl());
            return res;
        }
    }

    /**
     * Identifies tags or release titles denoting unstable/beta/preview builds.
     */
    public static boolean isUnstableIdentifier(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        return lower.contains("unstable") || lower.contains("beta") || lower.contains("alpha")
            || lower.contains("rc") || lower.contains("nightly") || lower.contains("dev") || lower.contains("preview");
    }

    private static List<String> splitJsonArrayObjects(String jsonArray) {
        List<String> list = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inQuote = false;

        for (int i = 0; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);
            if (c == '\"' && (i == 0 || jsonArray.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && start != -1) {
                        list.add(jsonArray.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return list;
    }

    private static boolean extractJsonBooleanField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":";
        int idx = json.indexOf(key);
        if (idx == -1) {
            key = "\"" + fieldName + "\": ";
            idx = json.indexOf(key);
        }
        if (idx == -1) return false;
        int start = idx + key.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        return json.startsWith("true", start);
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

    public static boolean isNewerVersion(String latest, String current) {
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

    private static void writeStatus(boolean hasUpdate, String latestVer, String channel) {
        writeStatus(hasUpdate, latestVer, channel, DEFAULT_JAR_DOWNLOAD_URL, getDefaultNativeDownloadUrl());
    }

    private static void writeStatus(boolean hasUpdate, String latestVer, String channel, String downloadUrl) {
        writeStatus(hasUpdate, latestVer, channel, downloadUrl, getDefaultNativeDownloadUrl());
    }

    private static void writeStatus(boolean hasUpdate, String latestVer, String channel, String downloadUrl, String dllUrl) {
        try {
            String userHome = System.getProperty("user.home");
            if (userHome == null) return;
            File luaDir = new File(userHome, "Zomboid" + File.separator + "Lua");
            if (!luaDir.exists()) luaDir.mkdirs();

            File updateFile = new File(luaDir, "pzo_update.json");
            String json = String.format("{\"has_update\": %b, \"current_version\": \"%s\", \"latest_version\": \"%s\", \"url\": \"%s\", \"dll_url\": \"%s\", \"native_file\": \"%s\", \"channel\": \"%s\", \"beta_opt_in\": %b}",
                hasUpdate, CURRENT_VERSION, latestVer,
                downloadUrl != null ? downloadUrl : DEFAULT_JAR_DOWNLOAD_URL,
                dllUrl != null ? dllUrl : getDefaultNativeDownloadUrl(),
                getNativeFileName(),
                channel, PZOConfig.isBetaOptIn());

            try (FileWriter fw = new FileWriter(updateFile, false)) {
                fw.write(json);
            }
        } catch (Throwable ignored) {}
    }
}

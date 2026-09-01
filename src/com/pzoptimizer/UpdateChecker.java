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
    public static final String CURRENT_VERSION = "0.8.2";
    private static final String GITHUB_LATEST_API_URL = "https://api.github.com/repos/prop11/PZO-Launcher/releases/latest";
    private static final String GITHUB_ALL_RELEASES_API_URL = "https://api.github.com/repos/prop11/PZO-Launcher/releases";
    private static final String DEFAULT_JAR_DOWNLOAD_URL = "https://github.com/prop11/PZO-Launcher/releases/latest/download/PZOptimEngine.jar";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    public static class UpdateResult {
        public boolean hasUpdate = false;
        public String latestVersion = CURRENT_VERSION;
        public String tagName = "";
        public String downloadUrl = DEFAULT_JAR_DOWNLOAD_URL;
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
                // Parse array of releases
                List<String> releases = splitJsonArrayObjects(json);
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
                        selectedReleaseJson = relJson;
                        break;
                    } else {
                        // BETA USER: Accept prereleases, unstable tags, or top release
                        selectedReleaseJson = relJson;
                        break;
                    }
                }
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
            String downloadUrl = (jarAssetUrl != null && !jarAssetUrl.isEmpty()) ? jarAssetUrl : DEFAULT_JAR_DOWNLOAD_URL;

            writeStatus(hasUpdate, latestVersion, res.channel);

            res.hasUpdate = hasUpdate;
            res.latestVersion = latestVersion;
            res.tagName = tagName != null ? tagName : "";
            res.downloadUrl = downloadUrl;
            return res;
        } catch (Exception e) {
            writeStatus(false, CURRENT_VERSION, res.channel);
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

    private static void writeStatus(boolean hasUpdate, String latestVer, String channel) {
        try {
            String userHome = System.getProperty("user.home");
            if (userHome == null) return;
            File luaDir = new File(userHome, "Zomboid" + File.separator + "Lua");
            if (!luaDir.exists()) luaDir.mkdirs();

            File updateFile = new File(luaDir, "pzo_update.json");
            String json = String.format("{\"has_update\": %b, \"current_version\": \"%s\", \"latest_version\": \"%s\", \"channel\": \"%s\", \"beta_opt_in\": %b}",
                hasUpdate, CURRENT_VERSION, latestVer, channel, PZOConfig.isBetaOptIn());

            try (FileWriter fw = new FileWriter(updateFile, false)) {
                fw.write(json);
            }
        } catch (Throwable ignored) {}
    }
}

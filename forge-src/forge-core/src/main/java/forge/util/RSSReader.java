package forge.util;

import com.apptasticsoftware.rssreader.Item;
import com.apptasticsoftware.rssreader.RssReader;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RSSReader {
    // R44: never let a stalled github.com connection hang the caller (EDT startup check /
    // background updater) — connect/read timeouts in seconds.
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    private static InputStream openStreamWithTimeout(URL url) throws IOException {
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        return conn.getInputStream();
    }

    public static String getCommitLog(String commitsAtom, Date buildDateOriginal, Date maxDate) {
        String message = "";
        SimpleDateFormat simpleDate = TextUtil.getSimpleDate();
        try {
            RssReader reader = new RssReader();
            URL url = new URL(commitsAtom);
            InputStream inputStream = openStreamWithTimeout(url);
            List<Item> items = reader.read(inputStream).collect(Collectors.toList());
            StringBuilder logs = new StringBuilder();
            int c = 0;
            for (Item i : items) {
                if (i.getTitle().isEmpty())
                    continue;
                String title = TextUtil.stripNonValidXMLCharacters(i.getTitle().get());
                if (title.contains("Merge"))
                    continue;
                ZonedDateTime zonedDateTime = i.getPubDateZonedDateTime().isPresent() ? i.getPubDateZonedDateTime().get() : null;
                if (zonedDateTime == null)
                    continue;
                Date feedDate = Date.from(zonedDateTime.toInstant());
                if (buildDateOriginal != null && feedDate.before(buildDateOriginal))
                    continue;
                if (maxDate != null && feedDate.after(maxDate))
                    continue;
                logs.append(simpleDate.format(feedDate)).append(" | ").append(StringEscapeUtils.unescapeXml(title).replace("\n", "").replace("        ", "")).append("\n\n");
                if (c >= 15)
                    break;
                c++;
            }
            if (logs.length() > 0)
                message += ("\n\nLatest Changes:\n\n" + logs);
            inputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return message;
    }
    public static String getLatestReleaseTag(String releaseAtom) {
        String tag = "";
        try {
            RssReader reader = new RssReader();
            URL url = new URL(releaseAtom);
            InputStream inputStream = openStreamWithTimeout(url);
            List<Item> items = reader.read(inputStream).collect(Collectors.toList());
            for (Item i : items) {
                if (i.getLink().isPresent()) {
                    try {
                        String val = i.getLink().get();
                        // GBF fork (P-14): extract the tag after "/releases/tag/" so this also
                        // works for repos whose name contains "Forge" (e.g. Card-Forge-GBF).
                        final String marker = "/releases/tag/";
                        int idx = val.lastIndexOf(marker);
                        if (idx >= 0) {
                            tag = val.substring(idx + marker.length());
                            break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            inputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tag;
    }

    /**
     * GBF fork (P-14 auto-update): resolve the download URL of the first ".zip" release asset of
     * the given tag via the GitHub API (robust against asset renames). Falls back to the
     * conventional asset URL (Card-Forge-GBF-Portable-&lt;tag&gt;.zip) when the API call fails.
     *
     * @param repoUrl e.g. "https://github.com/LiZiyu153/Card-Forge-GBF/"
     * @param tag     the exact release tag, e.g. "v0.0.2.3"
     */
    public static String getReleaseZipAssetUrl(String repoUrl, String tag) {
        final String base = repoUrl.endsWith("/") ? repoUrl : repoUrl + "/";
        final String fallback = base + "releases/download/" + tag + "/Card-Forge-GBF-Portable-" + tag + ".zip";
        try {
            Matcher repoMatcher = Pattern.compile("github\\.com/([^/]+)/([^/]+?)/?$").matcher(base);
            if (!repoMatcher.find()) {
                return fallback;
            }
            String owner = repoMatcher.group(1);
            String repo = repoMatcher.group(2);
            String api = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/tags/" + tag;
            URLConnection conn = new URL(api).openConnection();
            conn.setRequestProperty("User-Agent", "Forge/" + BuildInfo.getVersionString()); // GitHub API requires a UA
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            try (InputStream in = conn.getInputStream()) {
                String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                Matcher assetMatcher = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                while (assetMatcher.find()) {
                    String u = assetMatcher.group(1);
                    if (u.endsWith(".zip")) {
                        return u;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fallback;
    }
}

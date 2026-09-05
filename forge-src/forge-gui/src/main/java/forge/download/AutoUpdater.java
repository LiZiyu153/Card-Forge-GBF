package forge.download;

import forge.gui.util.SOptionPane;
import forge.util.*;
import org.apache.commons.lang3.StringUtils;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static forge.localinstance.properties.ForgeConstants.GBF_LOCAL_VERSION_FILE;
import static forge.localinstance.properties.ForgeConstants.GBF_RELEASES_ATOM;
import static forge.localinstance.properties.ForgeConstants.GBF_RELEASES_URL;

/**
 * Auto-update support.
 *
 * GBF fork (P-14): the update checker always targets THIS project's own repository
 * (LiZiyu153/Card-Forge-GBF). The official Card-Forge channels (daily-snapshots release
 * assets, releases.cardforge.org Maven server) are deliberately NOT used — fetching an
 * official build would replace the modified engine and break the GBF card scripts.
 * This project ships portable zips on GitHub Releases, so "updating" compares the latest
 * release tag with the local version marker (&lt;install dir&gt;/version.txt) and opens the
 * Releases page in the browser when a newer version exists.
 */
public class AutoUpdater {
    private static final Localizer localizer = Localizer.getInstance();

    // Kept for the preferences UI (auto-update combo); the GBF fork checks its own
    // repository regardless of the selected channel.
    public static String[] updateChannels = new String[]{ "none", "snapshot", "release"};

    private final boolean isLoading;
    private String version;
    private final String buildVersion;
    private String packageUrl;
    private String buildDate = "";

    public AutoUpdater(boolean loading) {
        // What do I need? Preferences? Splashscreen? UI? Skins?
        isLoading = loading;
        buildVersion = BuildInfo.getVersionString();
    }

    public boolean updateAvailable() {
        // TODO Check if an update is available, and add a UI element to notify the user.
        return verifyUpdateable();
    }

    public boolean attemptToUpdate(CompletableFuture<String> cf) {
        if (!verifyUpdateable()) {
            return false;
        }
        try {
            downloadUpdate(cf);
        } catch(IOException | URISyntaxException | ExecutionException | InterruptedException e) {
            return false;
        }
        return true;
    }

    private boolean verifyUpdateable() {
        if (isLoading) {
            // TODO This doesn't work yet, because FSkin isn't loaded at the time.
            return false;
        }

        // Check the internet connection
        if (!testNetConnection()) {
            return false;
        }

        // Fetch the latest release tag of this project's repo and compare it with the local marker
        return compareBuildWithLatestChannelVersion();
    }

    private boolean testNetConnection() {
        try (Socket socket = new Socket()) {
            InetSocketAddress address = new InetSocketAddress("github.com", 443);
            socket.connect(address, 1000);
            return true;
        } catch (IOException e) {
            return false; // Either timeout or unreachable or failed DNS lookup.
        }
    }

    private boolean compareBuildWithLatestChannelVersion() {
        try {
            retrieveVersion();
            if (StringUtils.isEmpty(version)) {
                return false;
            }
            final String localVersion = getLocalVersion();
            if (StringUtils.isEmpty(localVersion)) {
                return false; // no version marker shipped with this install -> stay quiet
            }
            buildDate = localVersion; // shown as the "current" version in the update dialog
            return !localVersion.equals(version);
        }
        catch (Exception e) {
            SOptionPane.showOptionDialog(e.getMessage(), localizer.getMessage("lblError"), null, List.of("Ok"));
            return false;
        }
    }

    private void retrieveVersion() {
        // GBF fork (P-14): latest version = newest release tag of this project's own repository.
        String tag = RSSReader.getLatestReleaseTag(GBF_RELEASES_ATOM);
        if (tag.startsWith("v") || tag.startsWith("V")) {
            tag = tag.substring(1);
        }
        version = tag.trim();
        // The project ships portable zips (no installer jar), so "updating" opens the Releases page.
        packageUrl = GBF_RELEASES_URL;
    }

    private String getLocalVersion() {
        // GBF fork (P-14): installed content version comes from <install dir>/version.txt,
        // a one-line file bumped together with each release (see docs/forge-src-patches.md P-14).
        try {
            File versionFile = new File(GBF_LOCAL_VERSION_FILE);
            if (!versionFile.exists()) {
                return "";
            }
            return FileUtil.readFileToString(versionFile).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean downloadUpdate(CompletableFuture<String> cf) throws URISyntaxException, IOException, ExecutionException, InterruptedException {
        // TODO Change the "auto" to be more auto.
        if (isLoading) {
            // We need to preload enough of a Skins to show a dialog and a button if we're in loading
            // splashScreen.prepareForDialogs();
            return downloadFromBrowser();
        }
        // GBF fork (P-14): cf = "latest changes" commit log of this project's repo (may be "").
        String logs = cf.get();
        String v = version;
        String b = buildDate.isEmpty() ? buildVersion : buildDate;
        String message = localizer.getMessage("lblNewVersionForgeAvailableUpdateConfirm", v, b) + logs;
        final List<String> options = List.of(localizer.getMessage("lblUpdateNow"), localizer.getMessage("lblUpdateLater"));
        if (SOptionPane.showOptionDialog(message, localizer.getMessage("lblNewVersionAvailable"), null, options, 0) == 0) {
            // Portable zip distribution: open the Releases page in the browser instead of
            // auto-downloading an installer jar (this project does not ship one).
            return downloadFromBrowser();
        }

        return false;
    }

    private boolean downloadFromBrowser() throws URISyntaxException, IOException {
        final Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
        if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
            // Linking directly there will auto download, but won't auto-update
            desktop.browse(new URI(packageUrl));
            return true;
        } else {
            System.out.println("Download latest version: " + packageUrl);
            return false;
        }
    }
}

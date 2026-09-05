package forge.download;

import forge.gui.FThreads;
import forge.gui.util.SOptionPane;
import forge.util.*;
import org.apache.commons.lang3.StringUtils;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
 *
 * R44 hotfix: the whole check is non-reentrant and never runs network on the EDT — a
 * re-entrant or queued duplicate invocation (e.g. repeated clicks on the title-bar marquee)
 * is ignored instead of cascading into another check + modal dialog.
 */
public class AutoUpdater {
    private static final Localizer localizer = Localizer.getInstance();

    // Kept for the preferences UI (auto-update combo); the GBF fork checks its own
    // repository regardless of the selected channel.
    public static String[] updateChannels = new String[]{ "none", "snapshot", "release"};

    // Re-entrancy guard: one check at a time, cleared only after the dialog (if any) closes.
    private static boolean checkInProgress;

    private final boolean isLoading;
    private final String buildVersion;
    private String version;
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
        if (checkInProgress) {
            System.out.println("AutoUpdater: update check already in progress, ignoring duplicate request");
            return false;
        }
        checkInProgress = true;
        if (FThreads.isGuiThread()) {
            // Both desktop callers (title-bar marquee, Downloads submenu) fire this from the EDT.
            // Never run network or modal dialogs synchronously inside the originating UI event;
            // run the check on a background thread and post only the final dialog to the EDT.
            FThreads.invokeInBackgroundThread(() -> runCheck(cf));
        } else {
            runCheck(cf);
        }
        return true;
    }

    private void runCheck(CompletableFuture<String> cf) {
        final String logs = fetchCommitLog(cf);
        boolean updateFound;
        try {
            updateFound = verifyUpdateable();
        } catch (Exception e) {
            e.printStackTrace();
            updateFound = false;
        }
        if (!updateFound) {
            System.out.println("AutoUpdater: no update prompt (latest=" + (StringUtils.isEmpty(version) ? "<unknown>" : version)
                    + ", local=" + (StringUtils.isEmpty(buildDate) ? "<none>" : buildDate) + ")");
            checkInProgress = false;
            return;
        }
        FThreads.invokeInEdtLater(() -> {
            try {
                downloadUpdate(logs); // modal confirm dialog + open Releases page — EDT only
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                checkInProgress = false; // cleared only after the dialog closed
            }
        });
    }

    /** "Latest changes" commit log of this project's repo (may be ""). */
    private static String fetchCommitLog(CompletableFuture<String> cf) {
        try {
            return cf.get();
        } catch (Exception e) { // ExecutionException / InterruptedException / CancellationException
            e.printStackTrace();
            return "";
        }
    }

    private boolean verifyUpdateable() {
        if (isLoading) {
            // TODO This doesn't work yet, because FSkin isn't loaded at the time.
            return false;
        }
        // Check the internet connection
        if (!testNetConnection()) {
            System.out.println("AutoUpdater: github.com unreachable, skipping update check");
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
                System.out.println("AutoUpdater: could not read the latest release tag from the repo");
                return false;
            }
            final String localVersion = getLocalVersion();
            if (StringUtils.isEmpty(localVersion)) {
                System.out.println("AutoUpdater: no local version.txt marker shipped, staying quiet");
                return false;
            }
            buildDate = localVersion; // shown as the "current" version in the update dialog
            return !localVersion.equals(version);
        }
        catch (Exception e) {
            // never pop an error dialog from inside the check itself — log instead
            e.printStackTrace();
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

    private void downloadUpdate(String logs) {
        String v = version;
        String b = buildDate.isEmpty() ? buildVersion : buildDate;
        String message = localizer.getMessage("lblNewVersionForgeAvailableUpdateConfirm", v, b) + logs;
        final List<String> options = List.of(localizer.getMessage("lblUpdateNow"), localizer.getMessage("lblUpdateLater"));
        System.out.println("AutoUpdater: update available — latest=" + v + ", local=" + b);
        if (SOptionPane.showOptionDialog(message, localizer.getMessage("lblNewVersionAvailable"), null, options, 0) == 0) {
            // Portable zip distribution: open the Releases page in the browser instead of
            // auto-downloading an installer jar (this project does not ship one).
            try {
                downloadFromBrowser();
            } catch (URISyntaxException | IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void downloadFromBrowser() throws URISyntaxException, IOException {
        final Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
        if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
            // Linking directly there will auto download, but won't auto-update
            desktop.browse(new URI(GBF_RELEASES_URL));
            return;
        }
        System.out.println("AutoUpdater: no desktop browser available — latest version at " + GBF_RELEASES_URL);
    }
}

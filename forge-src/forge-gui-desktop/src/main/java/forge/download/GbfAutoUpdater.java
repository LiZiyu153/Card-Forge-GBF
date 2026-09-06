package forge.download;

import forge.gui.FThreads;
import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgeConstants;
import forge.toolbox.FButton;
import forge.util.FileUtil;
import forge.util.RSSReader;
import forge.view.FDialog;

import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GBF fork (P-14 auto-update): desktop-side "update now" flow for the portable-zip
 * distribution.
 *
 * The game has no installer, so a full in-place overwrite would fight Windows file locks on
 * the running jar. Instead we download the release zip, extract it into a fresh sibling
 * directory "<install dir>.gbf-update", then on restart a tiny cmd script swaps the
 * directories (old -> ".gbf-old", new -> original name) and starts the new build. Directory
 * swap is atomic enough that either the old or the new build is always complete; the old
 * directory is deleted in the background after the new build has started.
 *
 * NOTE: UI texts are hard-coded Chinese on purpose (this class is desktop/GUI only and the
 * whole audience of this fork is Chinese-speaking; keeps the language files untouched).
 */
public class GbfAutoUpdater {
    private static final String SUFFIX_NEW = ".gbf-update";
    private static final String SUFFIX_OLD = ".gbf-old";
    private static final String JAR_NAME = "forge-gui-desktop-2.0.13-jar-with-dependencies.jar";
    private static final long MIN_FREE_BYTES = 1_500_000_000L; // zip (~460MB) + extracted (~600MB) + slack

    private GbfAutoUpdater() {
    }

    /** Called once at startup (FControl.initialize). */
    public static void register() {
        AutoUpdater.setUpdateInstaller(GbfAutoUpdater::startAutoInstall);
        // remove leftovers of previous interrupted auto-updates in the background
        FThreads.invokeInBackgroundThread(GbfAutoUpdater::cleanupLeftovers);
    }

    /** Entry point invoked from AutoUpdater on the EDT; never blocks it. */
    private static void startAutoInstall(String newVersion) {
        System.out.println("GbfAutoUpdater: starting auto-install of version " + newVersion);
        FThreads.invokeInBackgroundThread(() -> installInBackground(newVersion));
    }

    private static File installDir() {
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    /** Delete "<name>.gbf-old" / "<name>.gbf-update" siblings left by an interrupted update. */
    private static void cleanupLeftovers() {
        try {
            File installDir = installDir();
            File parent = installDir.getParentFile();
            if (parent == null) {
                return;
            }
            String base = installDir.getName();
            for (String suffix : new String[] { SUFFIX_OLD, SUFFIX_NEW }) {
                File stale = new File(parent, base + suffix);
                if (stale.exists()) {
                    System.out.println("GbfAutoUpdater: removing stale leftover " + stale.getAbsolutePath());
                    FileUtil.deleteDirectory(stale);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void installInBackground(String version) {
        final File installDir = installDir();
        final File parent = installDir.getParentFile();
        final String gameName = installDir.getName();
        final File newDir = new File(parent, gameName + SUFFIX_NEW);
        final String tag = version.startsWith("v") ? version : "v" + version;
        final File zipFile = new File(System.getProperty("java.io.tmpdir"), "gbf-update-" + tag + ".zip");

        final UpdateDialog dlg = new UpdateDialog();
        FThreads.invokeInEdtLater(() -> dlg.setVisible(true));
        try {
            // 1) disk space check
            long free = parent == null ? Long.MAX_VALUE : parent.getUsableSpace();
            if (free < MIN_FREE_BYTES) {
                dlg.closeDlg();
                int r = SOptionPane.showOptionDialog(
                        "磁盘空间不足：自动更新需要约 1.5GB 临时空间（当前可用 "
                                + (free / 1_000_000L) + "MB）。\n可清理后重试，或手动从 Releases 下载。",
                        "自动更新", SOptionPane.WARNING_ICON,
                        java.util.List.of("手动下载", "关闭"), 0);
                if (r == 0) {
                    openReleasesPage();
                }
                return;
            }

            // 2) fresh work dirs
            if (newDir.exists()) {
                FileUtil.deleteDirectory(newDir);
            }
            if (zipFile.exists() && !zipFile.delete()) {
                zipFile.deleteOnExit();
            }

            // 3) resolve the zip asset URL (GitHub API, falls back to the conventional name)
            dlg.setStatus("正在获取更新包下载地址…");
            String assetUrl = RSSReader.getReleaseZipAssetUrl(ForgeConstants.GBF_UPDATE_REPO, tag);

            // 4) download with progress
            dlg.setStatus("正在下载更新包（约 440MB，请保持网络畅通）…");
            if (!download(assetUrl, zipFile, dlg)) {
                if (dlg.cancelled.get()) {
                    failCancelled(dlg);
                    return;
                }
                dlg.closeDlg();
                retryOrManual("下载失败（网络问题或服务器不可达）。", version);
                return;
            }

            // 5) extract with progress
            dlg.setStatus("正在解压新版本…");
            if (!extract(zipFile, newDir, dlg)) {
                if (dlg.cancelled.get()) {
                    failCancelled(dlg);
                    return;
                }
                dlg.closeDlg();
                retryOrManual("解压失败（文件可能不完整）。", version);
                return;
            }
            // sanity: the new build must contain the game jar and res data
            if (!new File(newDir, JAR_NAME).isFile() || !new File(newDir, "res").isDirectory()) {
                dlg.closeDlg();
                retryOrManual("下载内容不完整（缺少游戏文件）。", version);
                return;
            }
            zipFile.delete();

            // 6) write the directory-swap script
            final File bat = writeSwitcherBat(installDir, newDir, gameName);
            if (bat == null) {
                dlg.closeDlg();
                retryOrManual("无法创建更新脚本。", version);
                return;
            }

            dlg.closeDlg();

            // 7) confirm restart & install
            int r = SOptionPane.showOptionDialog(
                    "新版本已下载并解压完成。\n\n点击「重启并安装」将关闭游戏，自动切换到新版本目录"
                            + "（旧版本目录会在新版本启动后自动清理），然后自动启动新版本。\n\n"
                            + "若选择「取消」，已下载内容将被删除，游戏保持当前版本不变。",
                    "自动更新 - 准备就绪", SOptionPane.QUESTION_ICON,
                    java.util.List.of("重启并安装", "取消"), 0);
            if (r != 0) {
                FileUtil.deleteDirectory(newDir);
                SOptionPane.showMessageDialog("已取消，游戏未做任何改动。", "自动更新", SOptionPane.INFORMATION_ICON);
                return;
            }

            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", bat.getAbsolutePath(),
                    installDir.getAbsolutePath(), newDir.getAbsolutePath(), gameName);
            pb.start();
            System.out.println("GbfAutoUpdater: switcher started, exiting game to complete the update");
            Thread.sleep(500);
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            dlg.closeDlg();
            SOptionPane.showErrorDialog("自动更新出错：" + e.getMessage() + "\n可稍后重试或手动从 Releases 下载。");
        }
    }

    private static void failCancelled(UpdateDialog dlg) {
        dlg.closeDlg();
        SOptionPane.showMessageDialog("已取消更新。", "自动更新", SOptionPane.INFORMATION_ICON);
    }

    private static void retryOrManual(String reason, String version) {
        int r = SOptionPane.showOptionDialog(
                reason + "\n可重试下载，或手动从 Releases 页面下载最新便携版。",
                "自动更新失败", SOptionPane.ERROR_ICON,
                java.util.List.of("重试", "手动下载", "关闭"), 0);
        if (r == 0) {
            FThreads.invokeInBackgroundThread(() -> installInBackground(version));
        } else if (r == 1) {
            openReleasesPage();
        }
    }

    private static void openReleasesPage() {
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(ForgeConstants.GBF_RELEASES_URL));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------------------ download

    private static boolean download(String urlStr, File dest, UpdateDialog dlg) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true); // GitHub release assets redirect to a CDN
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "Forge/" + forge.util.BuildInfo.getVersionString());
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                System.out.println("GbfAutoUpdater: HTTP " + code + " for " + urlStr);
                return false;
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (dlg.cancelled.get()) {
                        return false;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        dlg.setProgressPct((int) (100 * done / total));
                    }
                }
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ extract

    private static boolean extract(File zipFile, File destDir, UpdateDialog dlg) {
        try (ZipFile zf = new ZipFile(zipFile)) { // release zips are written with UTF-8 names (make_release_zip.py)
            int total = zf.size();
            int count = 0;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                if (dlg.cancelled.get()) {
                    return false;
                }
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                // zip-slip guard + skip the "./" root entry
                if (name.isEmpty() || name.contains("..") || name.startsWith("/") || name.indexOf(':') >= 0) {
                    continue;
                }
                File out = new File(destDir, name);
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    try {
                        out.getParentFile().mkdirs();
                        try (InputStream in = zf.getInputStream(entry); OutputStream os = new FileOutputStream(out)) {
                            in.transferTo(os);
                        }
                    } catch (Exception e) {
                        e.printStackTrace(); // per-entry failure: keep going, sanity check runs afterwards
                    }
                }
                count++;
                if (count % 25 == 0) {
                    dlg.setProgressPct((int) (100L * count / total));
                }
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ------------------------------------------------------------------ switcher script

    /**
     * Writes the ASCII-only directory-swap script into the system temp dir. Steps: wait for the
     * game to exit -> rename old dir to ".gbf-old" (with retries against file locks) -> rename
     * ".gbf-update" to the original name -> carry over forge.profile.properties -> start the new
     * forge.exe. The old directory is cleaned up by the new build at its next startup
     * (GbfAutoUpdater.cleanupLeftovers).
     *
     * NOTE: after a "ren" succeeds the original %~1 path no longer exists, so the script must
     * reference the renamed old dir as "%~1.gbf-old" and the final install dir as "%~1".
     * forge.exe is launched by its full path ("%~dp1%~3") because the script's CWD is %TEMP%.
     */
    private static File writeSwitcherBat(File installDir, File newDir, String gameName) {
        String content =
                "@echo off\r\n"
                        + "rem Card-Forge-GBF portable auto-update switcher (ASCII only)\r\n"
                        + "if not \"%MINIMIZED%\"==\"\" goto already\r\n"
                        + "set MINIMIZED=1\r\n"
                        + "start \"\" /min cmd /c \"\"%~f0\" %*\"\r\n"
                        + "exit /b\r\n"
                        + ":already\r\n"
                        + "set \"OLD=%~1\"\r\n"
                        + "set \"NEW=%~2\"\r\n"
                        + "set \"GAME=%~3\"\r\n"
                        + "set \"OLDDIR=%~1.gbf-old\"\r\n"
                        + "cd /d \"%TEMP%\"\r\n"
                        + "rem give the exiting game time to release its files\r\n"
                        + "timeout /t 8 /nobreak >nul\r\n"
                        + "set /a tries=0\r\n"
                        + ":retry\r\n"
                        + "ren \"%OLD%\" \"%GAME%.gbf-old\"\r\n"
                        + "if not errorlevel 1 goto renamed\r\n"
                        + "set /a tries+=1\r\n"
                        + "timeout /t 2 /nobreak >nul\r\n"
                        + "if %tries% lss 30 goto retry\r\n"
                        + "echo error: old directory stayed locked for 60s > \"%TEMP%\\gbf-update-error.txt\"\r\n"
                        + "exit /b 1\r\n"
                        + ":renamed\r\n"
                        + "ren \"%NEW%\" \"%GAME%\"\r\n"
                        + "if not errorlevel 1 goto swapped\r\n"
                        + "echo error: could not move new directory into place, rolling back > \"%TEMP%\\gbf-update-error.txt\"\r\n"
                        + "ren \"%OLDDIR%\" \"%GAME%\"\r\n"
                        + "exit /b 1\r\n"
                        + ":swapped\r\n"
                        + "if exist \"%OLDDIR%\\forge.profile.properties\" copy /y \"%OLDDIR%\\forge.profile.properties\" \"%~1\\forge.profile.properties\" >nul\r\n"
                        + "start \"\" \"%~dp1%~3\\forge.exe\"\r\n"
                        + "exit /b 0\r\n";
        try {
            File bat = new File(System.getProperty("java.io.tmpdir"), "gbf-updater-" + gameName + ".bat");
            FileUtil.writeFile(bat, content);
            System.out.println("GbfAutoUpdater: switcher script at " + bat.getAbsolutePath());
            return bat;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ------------------------------------------------------------------ progress dialog

    private static final class UpdateDialog extends FDialog {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final JProgressBar bar = new JProgressBar(0, 100);
        private final JLabel status = new JLabel(" ", SwingConstants.CENTER);

        UpdateDialog() {
            super(true, false, "dialog");
            setTitle("Card-Forge-GBF 自动更新");
            bar.setStringPainted(true);
            bar.setValue(0);
            add(bar, "w 420!, h 24, wrap, gap 10 10 10 10");
            add(status, "w 420!, wrap");
            FButton btnCancel = new FButton("取消");
            btnCancel.addActionListener(e -> cancelled.set(true));
            add(btnCancel, "align center, gapbottom 10");
            pack();
        }

        void setStatus(String s) {
            SwingUtilities.invokeLater(() -> status.setText(s));
        }

        void setProgressPct(int pct) {
            SwingUtilities.invokeLater(() -> bar.setValue(Math.max(0, Math.min(100, pct))));
        }

        void closeDlg() {
            SwingUtilities.invokeLater(() -> {
                if (isVisible()) {
                    setVisible(false);
                    dispose();
                }
            });
        }
    }
}

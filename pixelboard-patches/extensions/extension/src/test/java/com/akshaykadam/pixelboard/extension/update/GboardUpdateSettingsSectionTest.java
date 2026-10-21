package com.akshaykadam.pixelboard.extension.update;

import android.Manifest;
import android.app.Application;
import android.content.SharedPreferences;
import com.akshaykadam.pixelboard.extension.settings.GboardPatchesSettingsContract;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class GboardUpdateSettingsSectionTest {
    private Application context;
    private SharedPreferences preferences;
    private final List<String> actions = new ArrayList<String>();
    private final GboardUpdateSettingsSection.Actions recorder =
            new GboardUpdateSettingsSection.Actions() {
                @Override
                public void checkNow() {
                    actions.add("check");
                }

                @Override
                public void install() {
                    actions.add("install");
                }

                @Override
                public void openReleasePage(String url) {
                    actions.add("open " + url);
                }

                @Override
                public void requestNotificationPermission() {
                    actions.add("notifications");
                }

                @Override
                public void setAutoCheckEnabled(boolean enabled) {
                    actions.add("auto " + enabled);
                }
            };

    @Before
    public void setUp() throws Exception {
        GboardUpdateTestState.reset();
        context = RuntimeEnvironment.getApplication();
        preferences = GboardUpdateSettings.preferences(context);
        preferences.edit().clear().commit();
        Shadows.shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName()).lastUpdateTime = 0L;
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }

    @Test
    public void upToDateShowsTheSwitchAndCheckRowOnly() {
        GboardPatchesSettingsContract.Section section = build(null);

        Assert.assertEquals("Updates", section.getTitle());
        Assert.assertEquals(2, section.getItems().size());
        GboardPatchesSettingsContract.ToggleRow toggle =
                (GboardPatchesSettingsContract.ToggleRow) section.getItems().get(0);
        Assert.assertEquals("Check for updates automatically", toggle.getTitle().toString());
        Assert.assertTrue(toggle.isChecked());
        GboardPatchesSettingsContract.CommandRow check =
                (GboardPatchesSettingsContract.CommandRow) section.getItems().get(1);
        Assert.assertEquals("Check for updates now", check.getTitle().toString());
        Assert.assertEquals("Installed version: " + GboardUpdateChecker.installedVersion()
                + ". Not checked yet.", check.getSummary());

        toggle.getToggleAction().accept(false);
        check.getAction().run();
        Assert.assertEquals("[auto false, check]", actions.toString());
    }

    @Test
    public void availableUpdateOffersInstallAndReleaseNotes() {
        GboardUpdateRelease release = cache(new GboardUpdateRelease("v18.4.1-Stable-V99",
                "PixelBoard Stable Release (v18.4.1-V99 / Patch v99.0.0)",
                "https://github.com/Akshayykadam/PixelBoard/releases/tag/v18.4.1-Stable-V99",
                "99.0.0", 0L, "PixelBoard-18.4.1-V99.apk",
                "https://github.com/Akshayykadam/PixelBoard/releases/download/v/PixelBoard.apk",
                132947055L, null));

        GboardPatchesSettingsContract.Section section = build(release);

        Assert.assertEquals(4, section.getItems().size());
        GboardPatchesSettingsContract.CommandRow install =
                (GboardPatchesSettingsContract.CommandRow) section.getItems().get(0);
        Assert.assertEquals("Download and install", install.getTitle().toString());
        Assert.assertTrue(install.getSummary(), install.getSummary().startsWith(
                "PixelBoard 99.0.0 from GitHub ("));
        Assert.assertTrue(install.getSummary(), install.getSummary().contains("MB"));
        Assert.assertTrue(install.isEnabled());
        GboardPatchesSettingsContract.CommandRow notes =
                (GboardPatchesSettingsContract.CommandRow) section.getItems().get(1);
        Assert.assertEquals("Release notes", notes.getTitle().toString());
        Assert.assertEquals(release.name, notes.getSummary());
        Assert.assertEquals("PixelBoard 99.0.0 is available.",
                section.getItems().get(3).getSummary());

        install.getAction().run();
        notes.getAction().run();
        Assert.assertEquals("[install, open " + release.htmlUrl + "]", actions.toString());
    }

    @Test
    public void releaseWithoutApkLinksToGitHubInstead() {
        GboardUpdateRelease release = cache(new GboardUpdateRelease("v19", "PixelBoard 19",
                "https://github.com/Akshayykadam/PixelBoard/releases/tag/v19", "99.0.0", 0L,
                null, null, -1L, null));

        GboardPatchesSettingsContract.Section section = build(release);

        GboardPatchesSettingsContract.CommandRow open =
                (GboardPatchesSettingsContract.CommandRow) section.getItems().get(0);
        Assert.assertEquals("Open the release on GitHub", open.getTitle().toString());
        open.getAction().run();
        Assert.assertEquals("[open " + release.htmlUrl + "]", actions.toString());
    }

    @Test
    public void selfPatchedBuildsGetTheReleasePageInsteadOfAnInstall() throws Exception {
        GboardUpdateTestState.setOfficialBuild(false);
        GboardUpdateRelease release = cache(new GboardUpdateRelease("v99", "PixelBoard 99",
                "https://github.com/Akshayykadam/PixelBoard/releases/tag/v99", "99.0.0", 0L,
                "PixelBoard.apk", "https://github.com/x/y/releases/download/v/PixelBoard.apk",
                10L, null));

        GboardPatchesSettingsContract.CommandRow first =
                (GboardPatchesSettingsContract.CommandRow) build(release).getItems().get(0);

        Assert.assertEquals("Open the release on GitHub", first.getTitle().toString());
        Assert.assertTrue(first.getSummary(), first.getSummary().contains("Morphe Manager"));
    }

    @Test
    public void failedInstallsOnlyOfferARetryWhenItCanHelp() throws Exception {
        GboardUpdateRelease release = cache(new GboardUpdateRelease("v99", "PixelBoard 99",
                "https://github.com/Akshayykadam/PixelBoard/releases/tag/v99", "99.0.0", 0L,
                "PixelBoard.apk", "https://github.com/x/y/releases/download/v/PixelBoard.apk",
                10L, null));
        GboardUpdateTestState.setState(GboardUpdateInstaller.State.FAILED);

        GboardUpdateTestState.setFailure(GboardUpdateInstaller.ERROR_DOWNLOAD);
        Assert.assertEquals("The download failed. Tap to try again.",
                build(release).getItems().get(0).getSummary());

        GboardUpdateTestState.setFailure(GboardUpdateInstaller.ERROR_INSTALL_CONFLICT);
        Assert.assertFalse(build(release).getItems().get(0).getSummary().contains("Tap to try"));
    }

    @Test
    public void missingNotificationPermissionAddsAnAllowRow() {
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);

        GboardPatchesSettingsContract.Section section = build(null);

        GboardPatchesSettingsContract.Row last =
                section.getItems().get(section.getItems().size() - 1);
        Assert.assertEquals("Allow update notifications", last.getTitle().toString());

        GboardUpdateSettings.writeAutoCheckEnabled(preferences, false);
        Assert.assertEquals(2, build(null).getItems().size());
    }

    @Test
    public void lastFailureIsShownUntilACheckSucceeds() {
        preferences.edit()
                .putLong(GboardUpdateSettings.PREF_KEY_LAST_SUCCESS_MS, 1000L)
                .putLong(GboardUpdateSettings.PREF_KEY_LAST_ATTEMPT_MS, 2000L)
                .putString(GboardUpdateSettings.PREF_KEY_LAST_ERROR,
                        GboardUpdateChecker.ERROR_RATE_LIMITED)
                .commit();

        Assert.assertEquals("GitHub is limiting requests right now. Try again later.",
                build(null).getItems().get(1).getSummary());

        preferences.edit().putLong(GboardUpdateSettings.PREF_KEY_LAST_SUCCESS_MS, 3000L).commit();
        Assert.assertEquals("You have the latest version (" + GboardUpdateChecker.installedVersion()
                + "). Last checked: " + GboardUpdateSettingsSection.formatTime(
                        context, 3000L, System.currentTimeMillis()) + ".",
                build(null).getItems().get(1).getSummary());
    }

    @Test
    public void lastCheckTimeAddsTheYearOnlyWhenItDiffers() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(2026, java.util.Calendar.OCTOBER, 21, 13, 11, 0);
        long checkedAt = calendar.getTimeInMillis();
        calendar.set(2026, java.util.Calendar.DECEMBER, 1, 9, 0, 0);
        long laterThisYear = calendar.getTimeInMillis();
        calendar.set(2027, java.util.Calendar.JANUARY, 2, 9, 0, 0);
        long nextYear = calendar.getTimeInMillis();

        Assert.assertFalse(GboardUpdateSettingsSection.formatTime(context, checkedAt, laterThisYear)
                .contains("2026"));
        Assert.assertTrue(GboardUpdateSettingsSection.formatTime(context, checkedAt, nextYear)
                .contains("2026"));
    }

    @Test
    public void everyFailureCodeHasItsOwnSentence() {
        String[] codes = {
                GboardUpdateChecker.ERROR_NETWORK,
                GboardUpdateChecker.ERROR_RATE_LIMITED,
                GboardUpdateChecker.ERROR_NOT_FOUND,
                GboardUpdateChecker.ERROR_UNREADABLE,
                GboardUpdateInstaller.ERROR_UP_TO_DATE,
                GboardUpdateInstaller.ERROR_NO_APK,
                GboardUpdateInstaller.ERROR_DOWNLOAD,
                GboardUpdateInstaller.ERROR_CORRUPT,
                GboardUpdateInstaller.ERROR_INSTALL,
                GboardUpdateInstaller.ERROR_INSTALL_CONFLICT,
                GboardUpdateInstaller.ERROR_INSTALL_STORAGE,
        };
        Set<String> sentences = new HashSet<String>();
        for (String code : codes) {
            String sentence = GboardUpdateSettingsSection.errorText(context, code);
            Assert.assertTrue(code, sentence.endsWith("."));
            sentences.add(sentence);
        }
        Assert.assertEquals(codes.length, sentences.size());
        Assert.assertEquals(
                GboardUpdateSettingsSection.errorText(context, GboardUpdateChecker.ERROR_UNREADABLE),
                GboardUpdateSettingsSection.errorText(context,
                        GboardUpdateChecker.ERROR_HTTP_PREFIX + "500"));
        Assert.assertEquals(
                GboardUpdateSettingsSection.errorText(context, GboardUpdateChecker.ERROR_NETWORK),
                GboardUpdateSettingsSection.errorText(context, "something-new"));
    }

    private GboardPatchesSettingsContract.Section build(GboardUpdateRelease availableUpdate) {
        return GboardUpdateSettingsSection.build(
                context, preferences, availableUpdate, recorder, System.currentTimeMillis());
    }

    private GboardUpdateRelease cache(GboardUpdateRelease release) {
        GboardUpdateSettings.writeSuccess(preferences, System.currentTimeMillis(), release, null);
        GboardUpdateRelease available = GboardUpdateChecker.availableUpdate(context);
        Assert.assertNotNull(available);
        return available;
    }
}

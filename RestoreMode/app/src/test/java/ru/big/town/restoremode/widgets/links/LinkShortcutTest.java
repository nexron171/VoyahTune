package ru.big.town.restoremode.widgets.links;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.restoremode.dashboard.tiles.TileOrderStore;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = Application.class)
public class LinkShortcutTest {
    @Test
    public void launchesWebLinksInternallyAndDeepLinksThroughAndroid() throws Exception {
        android.app.Activity activity =
                org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        LinkShortcutLauncher.open(
                activity, new LinkShortcutStore.Entry("web", "Site", "https://example.com", false));
        android.content.Intent web =
                org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(LinkWebViewActivity.class.getName(), web.getComponent().getClassName());
        assertEquals("https://example.com", web.getDataString());
        android.content.Intent deep = LinkShortcutLauncher.deepLinkIntent("geo:0,0?q=Moscow");
        assertEquals(android.content.Intent.ACTION_VIEW, deep.getAction());
        assertEquals("geo:0,0?q=Moscow", deep.getDataString());
        assertTrue(deep.hasCategory(android.content.Intent.CATEGORY_BROWSABLE));
        android.content.Intent parsed =
                LinkShortcutLauncher.deepLinkIntent(
                        "intent://map/#Intent;scheme=example;package=example.maps;launchFlags=0x3;end");
        assertEquals("example", parsed.getData().getScheme());
        assertEquals("example.maps", parsed.getPackage());
        assertEquals(0, parsed.getFlags());
        assertNull(parsed.getComponent());
    }

    @Test
    public void webViewKeepsWebNavigationAndCloseButtonFinishesThePage() {
        android.content.Intent intent =
                new android.content.Intent(
                                RuntimeEnvironment.getApplication(), LinkWebViewActivity.class)
                        .setData(android.net.Uri.parse("https://example.com"));
        org.robolectric.android.controller.ActivityController<LinkWebViewActivity> controller =
                org.robolectric.Robolectric.buildActivity(LinkWebViewActivity.class, intent)
                        .setup();
        LinkWebViewActivity activity = controller.get();
        android.view.ViewGroup content = activity.findViewById(android.R.id.content);
        android.view.ViewGroup root = (android.view.ViewGroup) content.getChildAt(0);
        android.webkit.WebView webView = (android.webkit.WebView) root.getChildAt(0);
        assertEquals(
                "https://example.com",
                org.robolectric.Shadows.shadowOf(webView).getLastLoadedUrl());
        assertFalse(
                webView.getWebViewClient()
                        .shouldOverrideUrlLoading(webView, "https://example.com/next"));
        assertTrue(
                webView.getWebViewClient()
                        .shouldOverrideUrlLoading(webView, "file:///tmp/private"));
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(
                root,
                new androidx.core.view.WindowInsetsCompat.Builder()
                        .setInsets(
                                androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                                androidx.core.graphics.Insets.of(0, 50, 0, 0))
                        .build());
        assertEquals(
                Math.round(145 * activity.getResources().getDisplayMetrics().density),
                root.getPaddingLeft());
        assertEquals(50, root.getPaddingTop());
        root.getChildAt(1).performClick();
        assertTrue(activity.isFinishing());
        controller.pause().stop().destroy();
    }

    @Test
    public void browserChoicePersistsAndLaunchesOnlyTheSelectedPackage() {
        android.app.Activity activity =
                org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        SharedPreferences preferences = activity.getSharedPreferences("browser-choice", 0);
        preferences.edit().clear().commit();
        LinkShortcutStore.put(
                preferences,
                new LinkShortcutStore.Entry(
                        "browser", "Site", "https://example.com", false, "org.example.browser"));
        LinkShortcutStore.Entry entry = LinkShortcutStore.find(preferences, "browser");
        assertEquals("org.example.browser", entry.browserPackage);
        LinkShortcutLauncher.open(activity, entry);
        android.content.Intent intent =
                org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals("org.example.browser", intent.getPackage());
        assertEquals(android.content.Intent.ACTION_VIEW, intent.getAction());
        assertEquals("https://example.com", intent.getDataString());
        assertNull(intent.getComponent());
    }

    @Test
    public void browserPickerListsInstalledBrowsersAndPreservesUnavailableSelection() {
        android.app.Activity activity =
                org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        android.content.pm.ResolveInfo browser = new android.content.pm.ResolveInfo();
        browser.activityInfo = new android.content.pm.ActivityInfo();
        browser.activityInfo.packageName = "org.example.browser";
        browser.activityInfo.name = "BrowserActivity";
        browser.activityInfo.exported = true;
        browser.activityInfo.enabled = true;
        browser.activityInfo.applicationInfo = new android.content.pm.ApplicationInfo();
        browser.activityInfo.applicationInfo.enabled = true;
        browser.nonLocalizedLabel = "Example browser";
        android.content.Intent intent =
                new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://example.com"))
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE);
        org.robolectric.Shadows.shadowOf(activity.getPackageManager())
                .addResolveInfoForIntent(intent, browser);
        android.widget.Spinner spinner = new android.widget.Spinner(activity);
        LinkBrowserPicker picker = new LinkBrowserPicker(activity, spinner, "org.example.browser");
        assertEquals("org.example.browser", picker.selectedPackage());
        assertEquals("Example browser", spinner.getSelectedItem());
        spinner.setSelection(0);
        assertEquals("", picker.selectedPackage());
        LinkBrowserPicker unavailable = new LinkBrowserPicker(activity, spinner, "removed.browser");
        assertEquals("removed.browser", unavailable.selectedPackage());
        assertEquals("Недоступен: removed.browser", spinner.getSelectedItem());
    }

    @Test
    public void oldShortcutsDefaultToWebViewAndDeepLinksIgnoreBrowserChoice() {
        SharedPreferences preferences =
                RuntimeEnvironment.getApplication().getSharedPreferences("old-links", 0);
        preferences
                .edit()
                .putString(
                        "linkShortcuts",
                        "[{\"id\":\"old\",\"name\":\"Site\",\"address\":\"https://example.com\",\"deepLink\":false}]")
                .commit();
        assertEquals("", LinkShortcutStore.find(preferences, "old").browserPackage);
        assertEquals(
                "",
                new LinkShortcutStore.Entry("app", "App", "geo:0,0", true, "browser")
                        .browserPackage);
    }

    @Test
    public void missingHandlerShowsAnExplanation() {
        android.content.ContextWrapper context =
                new android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
                    @Override
                    public void startActivity(android.content.Intent intent) {
                        throw new android.content.ActivityNotFoundException();
                    }
                };
        LinkShortcutLauncher.open(
                context, new LinkShortcutStore.Entry("app", "App", "missing://page", true));
        assertEquals(
                "Нет приложения для открытия этой ссылки",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void validatesAddressesByType() {
        new LinkShortcutStore.Entry("one", "Site", "https://example.com/path?q=1", false);
        new LinkShortcutStore.Entry("two", "Map", "geo:0,0?q=Moscow", true);
        for (String address :
                new String[] {
                    "", "example.com", "https://", "file:///tmp/a", "javascript:alert(1)"
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new LinkShortcutStore.Entry("bad", "Site", address, false));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new LinkShortcutStore.Entry("bad", "", "https://example.com", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LinkShortcutStore.Entry("bad", "App", "relative/path", true));
    }

    @Test
    public void persistsEditsAndRemovalWithoutChangingTileIdentityOrOtherProfiles() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("links", 0);
        preferences.edit().clear().commit();
        SharedPreferences passenger = context.getSharedPreferences("passenger-links", 0);
        passenger.edit().clear().commit();
        LinkShortcutStore.put(
                preferences,
                new LinkShortcutStore.Entry("one", "Site", "https://example.com", false));
        TileOrderStore.sync(preferences, context.getPackageManager());
        assertTrue(
                TileOrderStore.load(preferences).stream()
                        .anyMatch(
                                tile ->
                                        tile.type.equals(TileOrderStore.Tile.TYPE_LINK)
                                                && tile.id.equals("one")));
        LinkShortcutStore.put(
                preferences, new LinkShortcutStore.Entry("one", "Map", "geo:0,0", true));
        assertEquals(1, LinkShortcutStore.load(preferences).size());
        assertEquals("Map", LinkShortcutStore.find(preferences, "one").name);
        assertTrue(LinkShortcutStore.find(preferences, "one").deepLink);
        assertTrue(LinkShortcutStore.load(passenger).isEmpty());
        LinkShortcutStore.remove(preferences, "one");
        TileOrderStore.sync(preferences, context.getPackageManager());
        assertFalse(
                TileOrderStore.load(preferences).stream().anyMatch(tile -> tile.id.equals("one")));
    }
}

package ru.big.town.restoremode.widgets.links;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.net.URISyntaxException;

public final class LinkShortcutLauncher {
    private LinkShortcutLauncher() {}

    public static void open(Context context, LinkShortcutStore.Entry entry) {
        try {
            Intent intent;
            if (entry.deepLink) {
                intent = deepLinkIntent(entry.address);
            } else if (!entry.browserPackage.isEmpty()) {
                intent =
                        new Intent(Intent.ACTION_VIEW, Uri.parse(entry.address))
                                .addCategory(Intent.CATEGORY_BROWSABLE)
                                .setPackage(entry.browserPackage);
            } else {
                intent =
                        new Intent(context, LinkWebViewActivity.class)
                                .setData(Uri.parse(entry.address));
            }
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(
                            context,
                            entry.browserPackage.isEmpty()
                                    ? "Нет приложения для открытия этой ссылки"
                                    : "Выбранный браузер недоступен. Измените приложение в"
                                          + " настройках ярлыка",
                            Toast.LENGTH_LONG)
                    .show();
        } catch (URISyntaxException exception) {
            Toast.makeText(context, "Некорректный диплинк", Toast.LENGTH_LONG).show();
        }
    }

    static Intent deepLinkIntent(String address) throws URISyntaxException {
        Intent intent =
                address.startsWith("intent:")
                        ? Intent.parseUri(address, Intent.URI_INTENT_SCHEME)
                        : new Intent(Intent.ACTION_VIEW, Uri.parse(address));
        intent.setAction(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setComponent(null);
        intent.setSelector(null);
        intent.setFlags(0);
        return intent;
    }
}

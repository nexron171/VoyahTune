package ru.big.town.restoremode.dashboard.tiles;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import ru.big.town.restoremode.widgets.energy.VehicleWidgets;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class LoweredTileLayoutTest {
    private final TileOrderStore.Tile tall = tile("tall");
    private final TileOrderStore.Tile shortTile = tile("short");

    @Test
    public void automaticLayoutClampsOnlyTallTilesAndKeepsTheirOriginalComposition() {
        SharedPreferences preferences = preferences("driver");
        LoweredTileLayout layout = load(preferences, Arrays.asList(tall, shortTile));
        assertArrayEquals(new int[] {3, 4}, layout.dimensions(tall));
        assertArrayEquals(new int[] {3, 5}, layout.contentDimensions(tall));
        assertArrayEquals(new int[] {2, 2}, layout.dimensions(shortTile));
        assertFalse(preferences.contains(LoweredTileLayout.PREFERENCE_KEY));
    }

    @Test
    public void firstEditSnapshotsEveryTileAndLaterRaisedEditsDoNotReplaceIt() {
        SharedPreferences preferences = preferences("driver");
        TileOrderStore.save(preferences, Arrays.asList(tall, shortTile));
        String original = preferences.getString(TileOrderStore.ORDER_KEY, "");
        LoweredTileLayout layout = load(preferences, Arrays.asList(tall, shortTile));
        layout.resize(shortTile, 4, 2);
        layout.move(0, 2);
        LoweredTileLayout restored =
                new LoweredTileLayout(
                        preferences, Arrays.asList(tall, shortTile), 4, tile -> new int[] {1, 1});
        assertEquals(Arrays.asList("short", "tall"), ids(restored));
        assertArrayEquals(new int[] {4, 2}, restored.dimensions(shortTile));
        assertArrayEquals(new int[] {3, 4}, restored.dimensions(tall));
        assertArrayEquals(new int[] {3, 5}, restored.contentDimensions(tall));
        assertEquals(original, preferences.getString(TileOrderStore.ORDER_KEY, ""));
    }

    @Test
    public void removingAndAddingDefinitionsPreservesSurvivorsAndAppendsNewTiles() {
        SharedPreferences preferences = preferences("driver");
        LoweredTileLayout layout = load(preferences, Arrays.asList(tall, shortTile));
        layout.move(1, 0);
        TileOrderStore.Tile added = tile("new");
        LoweredTileLayout restored = load(preferences, Arrays.asList(added, shortTile));
        assertEquals(Arrays.asList("short", "new"), ids(restored));
        assertArrayEquals(new int[] {2, 2}, restored.dimensions(shortTile));
        assertArrayEquals(new int[] {2, 2}, restored.dimensions(added));
    }

    @Test
    public void driverAndPassengerEditsAreIndependent() {
        SharedPreferences driver = preferences("driver");
        SharedPreferences passenger = preferences("passenger");
        load(driver, Arrays.asList(tall, shortTile)).resize(tall, 4, 4);
        assertArrayEquals(new int[] {4, 4}, load(driver, Arrays.asList(tall)).dimensions(tall));
        assertArrayEquals(new int[] {3, 4}, load(passenger, Arrays.asList(tall)).dimensions(tall));
        assertFalse(passenger.contains(LoweredTileLayout.PREFERENCE_KEY));
    }

    @Test
    public void malformedAndInvalidSavedEntriesFallBackToTheRaisedLayout() {
        SharedPreferences preferences = preferences("driver");
        for (String saved :
                new String[] {
                    "not json",
                    "[{\"type\":\"widget\",\"id\":\"tall\",\"columns\":0,\"rows\":4}]",
                    "[{\"type\":\"widget\",\"id\":\"tall\",\"columns\":3,\"rows\":99}]"
                }) {
            preferences.edit().putString(LoweredTileLayout.PREFERENCE_KEY, saved).apply();
            LoweredTileLayout layout = load(preferences, Arrays.asList(tall, shortTile));
            assertEquals(Arrays.asList("tall", "short"), ids(layout));
            assertArrayEquals(new int[] {3, 4}, layout.dimensions(tall));
            assertArrayEquals(new int[] {3, 5}, layout.contentDimensions(tall));
        }
    }

    @Test
    public void resizeLimitsAllowFittingAWidgetWithoutAFourRowVariant() {
        LoweredTileLayout layout = load(preferences("driver"), Arrays.asList(tall));
        assertTrue(layout.supportsWidgetSize(VehicleWidgets.ENERGY, 4, 4));
        assertFalse(TileSizeStore.supportsSize(VehicleWidgets.ENERGY, 4, 4));
        assertFalse(layout.supportsWidgetSize(VehicleWidgets.ENERGY, 4, 5));
        assertFalse(layout.supportsWidgetSize(VehicleWidgets.ENERGY, 4, 3));
        assertFalse(layout.supportsWidgetSize(VehicleWidgets.ENERGY, 13, 4));
        layout.resize(tall, 4, 4);
        assertArrayEquals(new int[] {4, 5}, layout.contentDimensions(tall));
    }

    @Test
    public void widthEditsUseASupportedCompositionWhenOnlyFiveRowsExistAtTheNewWidth() {
        TileOrderStore.Tile energy = tile(VehicleWidgets.ENERGY);
        LoweredTileLayout layout =
                new LoweredTileLayout(
                        preferences("driver"), Arrays.asList(energy), 4, tile -> new int[] {3, 4});
        layout.resize(energy, 4, 4);
        assertArrayEquals(new int[] {4, 4}, layout.dimensions(energy));
        assertArrayEquals(new int[] {4, 5}, layout.contentDimensions(energy));
        layout.resize(energy, 8, 4);
        assertArrayEquals(new int[] {8, 4}, layout.contentDimensions(energy));
    }

    @Test
    public void invalidAndUnchangedEditsDoNotMaterializeAConfiguration() {
        SharedPreferences preferences = preferences("driver");
        LoweredTileLayout layout = load(preferences, Arrays.asList(tall, shortTile));
        layout.move(0, 1);
        layout.move(-1, 2);
        layout.resize(tall, 3, 4);
        layout.resize(tall, 3, 5);
        layout.resize(tall, 0, 1);
        assertFalse(preferences.contains(LoweredTileLayout.PREFERENCE_KEY));
        assertArrayEquals(new int[] {3, 4}, layout.dimensions(tall));
    }

    @Test
    public void aShorterViewportFitsTheSavedCompositionWithoutChangingTheSnapshot() {
        SharedPreferences preferences = preferences("driver");
        load(preferences, Arrays.asList(tall, shortTile)).move(1, 0);
        String saved = preferences.getString(LoweredTileLayout.PREFERENCE_KEY, "");
        LoweredTileLayout shorter =
                new LoweredTileLayout(
                        preferences, Arrays.asList(tall, shortTile), 3, tile -> new int[] {1, 1});
        assertArrayEquals(new int[] {3, 3}, shorter.dimensions(tall));
        assertArrayEquals(new int[] {3, 5}, shorter.contentDimensions(tall));
        assertEquals(saved, preferences.getString(LoweredTileLayout.PREFERENCE_KEY, ""));
    }

    private LoweredTileLayout load(SharedPreferences preferences, List<TileOrderStore.Tile> tiles) {
        return new LoweredTileLayout(
                preferences,
                tiles,
                4,
                tile -> "tall".equals(tile.id) ? new int[] {3, 5} : new int[] {2, 2});
    }

    private static SharedPreferences preferences(String name) {
        return RuntimeEnvironment.getApplication().getSharedPreferences(name, 0);
    }

    private static TileOrderStore.Tile tile(String id) {
        return new TileOrderStore.Tile(TileOrderStore.Tile.TYPE_WIDGET, id);
    }

    private static List<String> ids(LoweredTileLayout layout) {
        return layout.tiles().stream().map(tile -> tile.id).collect(Collectors.toList());
    }
}

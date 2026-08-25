package ru.big.town.anative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Map;

import org.junit.Test;

public class FragranceRestorePolicyTest {
    @Test
    public void normalizesPersistedValuesFailClosed() {
        FragranceRestorePolicy.Settings valid = FragranceRestorePolicy.normalize(3, 2, 1);
        assertEquals(3, valid.taste);
        assertEquals(2, valid.duration);
        assertEquals(1, valid.intensity);

        FragranceRestorePolicy.Settings invalid = FragranceRestorePolicy.normalize(0, 3, 4);
        assertEquals(FragranceRestorePolicy.DEFAULT_TASTE, invalid.taste);
        assertEquals(FragranceRestorePolicy.DEFAULT_DURATION, invalid.duration);
        assertEquals(FragranceRestorePolicy.DEFAULT_INTENSITY, invalid.intensity);
    }

    @Test
    public void bundleContainsOnlyStockFragranceLinFields() {
        FragranceRestorePolicy.Settings settings = FragranceRestorePolicy.normalize(2, 1, 3);
        Map<String, Integer> bundle = FragranceRestorePolicy.fragranceBundle(settings);

        assertEquals(3, bundle.size());
        assertEquals(Integer.valueOf(2), bundle.get("IVI_FRAG_TASTE"));
        assertEquals(Integer.valueOf(3), bundle.get("IVI_FRAG_CONCERNTION"));
        assertEquals(Integer.valueOf(2), bundle.get("FCM_SW_REQ"));
        assertFalse(bundle.containsKey("IVI_FRAG_TYPE"));
        assertFalse(bundle.containsKey("FCM_DURATION_CONTROL"));
    }

    @Test
    public void stableIdsMatchAndroid11H97cAbi() {
        Map<String, Integer> ids = FragranceRestorePolicy.stableIds();
        assertEquals(Integer.valueOf(1067), ids.get("FCM_DURATION_CONTROL"));
        assertEquals(Integer.valueOf(774), ids.get("FCM_SW_REQ"));
        assertEquals(Integer.valueOf(775), ids.get("IVI_FRAG_TASTE"));
        assertEquals(Integer.valueOf(777), ids.get("IVI_FRAG_CONCERNTION"));
    }
}

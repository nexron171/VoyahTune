package ru.big.town.anative;
import org.junit.Test;
import static org.junit.Assert.*;
import ru.big.town.common.InstallModeValue;
public class InstallModeValueTest {
    @Test public void onlyExplicitFullEnablesFull() {
        assertEquals(InstallModeValue.FULL, InstallModeValue.parse("full"));
        assertEquals(InstallModeValue.LIGHT, InstallModeValue.parse("light"));
        for (String value : new String[]{null, "", "null", "FULL", "true", "1", "garbage"})
            assertEquals(InstallModeValue.UNKNOWN, InstallModeValue.parse(value));
    }
}

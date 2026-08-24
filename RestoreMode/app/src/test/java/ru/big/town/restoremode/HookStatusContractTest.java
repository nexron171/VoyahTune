package ru.big.town.restoremode;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HookStatusContractTest {
    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String VALID = "v=1;loader=running;pid=321;manifest=" + SHA
            + ";vd-bypass=active:100"
            + ";steering-wheel=injecting:101"
            + ";launcher-dock=waiting:0"
            + ";multi-display=failed:102"
            + ";apollo-tech=active:103"
            + ";keyboard-en=disabled:104"
            + ";keyboard-ru=invalid:104";

    @Test
    public void validPayloadIsRendered() {
        assertTrue(HookStatusContract.isValidPayload(VALID));
        String rendered = HookStatusContract.renderForUi(VALID, true);
        assertTrue(rendered.contains("Loader: работает (PID 321)"));
        assertTrue(rendered.contains("Окна / VirtualDisplay: активен (PID 100)"));
        assertTrue(rendered.contains("Кнопки руля: устанавливается (PID 101)"));
        assertTrue(rendered.contains("Клавиатура RU: ошибка целостности (PID 104)"));
    }

    @Test
    public void malformedOrUnknownPayloadFailsClosed() {
        assertFalse(HookStatusContract.isValidPayload(null));
        assertFalse(HookStatusContract.isValidPayload(""));
        assertFalse(HookStatusContract.isValidPayload(VALID.replace("v=1", "v=2")));
        assertFalse(HookStatusContract.isValidPayload(VALID.replace("active:100", "bogus:100")));
        assertFalse(HookStatusContract.isValidPayload(VALID.replace("active:100", "ACTIVE:100")));
        assertFalse(HookStatusContract.isValidPayload(VALID.replace("manifest=" + SHA,
                "manifest=")));
        assertFalse(HookStatusContract.isValidPayload(VALID.replace("pid=321", "pid=-1")));
        assertFalse(HookStatusContract.isValidPayload(VALID.replace("pid=321", "pid=2147483648")));
        assertFalse(HookStatusContract.isValidPayload(VALID.replace("pid=321", "pid=0")));
        assertFalse(HookStatusContract.isValidPayload(VALID.replace(
                "loader=running", "loader=stopped")));
        assertFalse(HookStatusContract.isValidPayload(VALID.replace(
                "launcher-dock=waiting:0", "launcher=waiting:0")));
        assertFalse(HookStatusContract.isValidPayload(VALID + "\n"));
        assertFalse(HookStatusContract.isValidPayload(VALID + ";extra=active:1"));
        assertFalse(HookStatusContract.isValidPayload(VALID + new String(new char[2_048])
                .replace('\0', 'x')));
    }

    @Test
    public void unavailableManifestAndStoppedLoaderAreExplicitValidStates() {
        String stopped = VALID.replace("loader=running", "loader=stopped")
                .replace("pid=321", "pid=0")
                .replace("manifest=" + SHA, "manifest=unavailable");
        assertTrue(HookStatusContract.isValidPayload(stopped));
        assertTrue(HookStatusContract.renderForUi(stopped, true).contains("Loader: остановлен"));
        assertTrue(HookStatusContract.renderForUi(stopped, true).contains("Manifest: недоступен"));
    }

    @Test
    public void lightFlavorNeverClaimsRootHooks() {
        assertTrue(HookStatusContract.renderForUi(VALID, false).contains("Light-версии"));
    }
}

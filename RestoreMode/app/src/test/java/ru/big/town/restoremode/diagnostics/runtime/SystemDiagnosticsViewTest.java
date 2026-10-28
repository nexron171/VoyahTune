package ru.big.town.restoremode.diagnostics.runtime;

import static org.junit.Assert.*;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import ru.big.town.restoremode.R;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@LooperMode(LooperMode.Mode.PAUSED)
public class SystemDiagnosticsViewTest {
    @Test
    public void statusChangesUpdateExistingRowsWithoutDuplicatingThem() {
        SystemDiagnosticsView view =
                (SystemDiagnosticsView) LayoutInflater.from(RuntimeEnvironment.getApplication())
                        .inflate(R.layout.settings_other_runtime, null);
        RuntimeStatusContract.Row active = new RuntimeStatusContract.Row(
                "phone", "Телефон", "Работает", RuntimeStatusContract.Level.GREEN, true);
        RuntimeStatusContract.Row waiting = new RuntimeStatusContract.Row(
                "launcher", "Лаунчер", "Ожидает процесс", RuntimeStatusContract.Level.YELLOW, true);
        view.render(new SystemDiagnosticsReader.Result(Arrays.asList(active, waiting), "Сейчас"));
        ViewGroup agents = view.findViewById(R.id.diagnosticsAgents);
        assertEquals(2, agents.getChildCount());
        View first = agents.getChildAt(0);
        RuntimeStatusContract.Row failed = new RuntimeStatusContract.Row(
                "phone", "Телефон", "Нет подтверждения", RuntimeStatusContract.Level.RED, true);
        view.render(new SystemDiagnosticsReader.Result(Arrays.asList(failed, waiting), "Сейчас"));
        assertSame(first, agents.getChildAt(0));
        assertEquals("Телефон: Нет подтверждения", first.getContentDescription().toString());
        assertTrue(((TextView) view.findViewById(R.id.diagnosticsSummary))
                           .getText()
                           .toString()
                           .contains("Ошибки: 1"));
        assertEquals(2, agents.getChildCount());
    }
}

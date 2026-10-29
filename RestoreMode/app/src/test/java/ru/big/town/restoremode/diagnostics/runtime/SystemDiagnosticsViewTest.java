package ru.big.town.restoremode.diagnostics.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
        SystemDiagnosticsView view = inflateDiagnostics();
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
        TextView summary = view.findViewById(R.id.diagnosticsSummary);
        assertTrue(summary.getText().toString().contains("Ошибки: 1"));
        assertEquals(2, agents.getChildCount());
    }

    @Test
    public void wideDiagnosticGridsPlaceFiveTilesBeforeWrapping() {
        SystemDiagnosticsView view = diagnosticsWithSixRowsPerGroup();
        for (int group : new int[] {R.id.diagnosticsInfrastructure, R.id.diagnosticsAgents}) {
            ViewGroup grid = measureGrid(view, group, 1360);
            View first = grid.getChildAt(0);
            for (int index = 1; index < 5; index++) {
                View tile = grid.getChildAt(index);
                assertEquals("Five tiles must share the first row", first.getTop(), tile.getTop());
                assertTrue("Status tiles overlap", grid.getChildAt(index - 1).getRight() < tile.getLeft());
            }
            assertTrue("The sixth tile must wrap", grid.getChildAt(5).getTop() > first.getBottom());
        }
    }

    @Test
    public void narrowDiagnosticGridsKeepTilesInsideTheirWidth() {
        SystemDiagnosticsView view = diagnosticsWithSixRowsPerGroup();
        for (int group : new int[] {R.id.diagnosticsInfrastructure, R.id.diagnosticsAgents}) {
            ViewGroup grid = measureGrid(view, group, 220);
            for (int index = 0; index < grid.getChildCount(); index++) {
                View tile = grid.getChildAt(index);
                assertEquals(0, tile.getLeft());
                assertEquals(grid.getWidth(), tile.getRight());
                if (index > 0) {
                    assertTrue(grid.getChildAt(index - 1).getBottom() < tile.getTop());
                }
            }
        }
    }

    private static SystemDiagnosticsView diagnosticsWithSixRowsPerGroup() {
        SystemDiagnosticsView view = inflateDiagnostics();
        List<RuntimeStatusContract.Row> rows = new ArrayList<>();
        for (boolean agent : new boolean[] {false, true}) {
            for (int index = 0; index < 6; index++) {
                rows.add(new RuntimeStatusContract.Row(
                        (agent ? "agent-" : "infrastructure-") + index,
                        "Проверяемая подсистема",
                        "Подтверждённое состояние",
                        RuntimeStatusContract.Level.GREEN,
                        agent));
            }
        }
        view.render(new SystemDiagnosticsReader.Result(rows, "Сейчас"));
        return view;
    }

    private static SystemDiagnosticsView inflateDiagnostics() {
        return (SystemDiagnosticsView) LayoutInflater.from(RuntimeEnvironment.getApplication())
                .inflate(R.layout.settings_other_runtime, null);
    }

    private static ViewGroup measureGrid(SystemDiagnosticsView view, int group, int widthDp) {
        ViewGroup grid = view.findViewById(group);
        float density = view.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density);
        grid.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, width, grid.getMeasuredHeight());
        return grid;
    }
}

import com.hbmzz.cecp.ControlRegistry;
import com.hbmzz.cecp.controls.ControlType;

import java.util.List;

/**
 * Offline check of the console editor's control-type table.
 *
 * <p>The editor crashed on open with a ConcurrentModificationException, and the
 * cause was invisible from either end: {@code ControlType.ALL_VALUES} is filled
 * by static initialisers, so the last three entries (the Leafia addon's) only
 * appear the first time some class touches them. The editor's type menu walked
 * that list while resolving every entry through the registry - which is exactly
 * what touches them - so the walk appended to itself and died.</p>
 *
 * <p>What makes this testable without a game is the invariant the fix
 * establishes: <b>after {@code ControlRegistry.init()}, the type table is
 * complete.</b> Everything after that is reading, so no GUIC can grow it. Run in
 * a fresh JVM, a regression shows up as "types after init: 9" followed by a
 * throw on the walk - the same 9/12 split the crash produced in the wild.</p>
 *
 * <pre>
 * ./tools/cecpcheck/run.sh
 * </pre>
 */
public final class CecpCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // 1. Build the registry the way preInit does.
        ControlRegistry.init();
        int afterInit = ControlType.count();

        // 2. The editor's menu builds itself from a snapshot; that snapshot must
        //    already contain everything, or the first open silently loses the
        //    addon types (and, before the fix, crashed instead).
        check(afterInit >= 12, "registry init leaves the type table complete", "got " + afterInit);

        // 3. Walk it the way the menu does: resolve every type through the
        //    registry while iterating. Before the fix this threw.
        List<ControlType> types = ControlType.all();
        boolean threw = false;
        String detail = "";
        try {
            for (ControlType type : types) {
                ControlRegistry.getAllControlsOfType(type);
            }
        } catch (Throwable error) {
            threw = true;
            detail = error.getClass().getSimpleName();
        }
        check(!threw, "walking the type table does not modify it", detail);
        check(ControlType.count() == afterInit, "the walk adds no types",
                afterInit + " -> " + ControlType.count());

        // 4. Snapshot, not view: growing the live list must not change a list
        //    already handed out. This is what keeps a resize from renumbering
        //    the buttons under the player.
        List<ControlType> snapshot = ControlType.all();
        int before = snapshot.size();
        ControlType.registerOrGet("Check Only Type");
        check(snapshot.size() == before, "a snapshot survives later registrations",
                before + " -> " + snapshot.size());
        check(ControlType.count() == afterInit + 1, "registerOrGet still appends",
                String.valueOf(ControlType.count()));
        check(ControlType.at(before) != null && "Check Only Type".equals(ControlType.at(before).name),
                "at(int) resolves the same order as all()", "index " + before);
        check(ControlType.at(-1) == null && ControlType.at(9999) == null,
                "at(int) refuses indexes outside the table", "");

        // 5. Every registered type has a display name; the menu draws these.
        boolean named = true;
        for (ControlType type : ControlType.all()) {
            if (type.getDisplayName() == null || type.getDisplayName().isEmpty()) {
                named = false;
            }
        }
        check(named, "every type has a display name", "");

        System.out.println();
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean ok, String name, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  -- " + detail));
        }
    }
}

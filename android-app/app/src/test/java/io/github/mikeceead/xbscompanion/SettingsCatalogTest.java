package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class SettingsCatalogTest {
    @Test public void catalogHasEightStableUniqueDestinations() {
        List<SettingsDestination> all = SettingsCatalog.all();
        assertEquals(8, all.size());
        Set<String> ids = new HashSet<>();
        for (SettingsDestination destination : all) {
            assertTrue(ids.add(destination.id));
            assertFalse(destination.title.isEmpty());
            assertFalse(destination.description.isEmpty());
            assertFalse(destination.keywords.isEmpty());
        }
        assertNotNull(SettingsCatalog.find(SettingsDestination.RUNTIME));
        assertNotNull(SettingsCatalog.find(SettingsDestination.NOTIFICATIONS));
        assertNotNull(SettingsCatalog.find(SettingsDestination.APPEARANCE));
        assertNotNull(SettingsCatalog.find(SettingsDestination.TASKER));
        assertNotNull(SettingsCatalog.find(SettingsDestination.DATA));
        assertNotNull(SettingsCatalog.find(SettingsDestination.CONFIGURATION));
        assertNotNull(SettingsCatalog.find(SettingsDestination.DIAGNOSTICS));
        assertNotNull(SettingsCatalog.find(SettingsDestination.ABOUT));
    }

    @Test public void searchIsCaseInsensitiveAndRequiresEveryToken() {
        assertEquals(SettingsDestination.NOTIFICATIONS, only("CHANNEL permission").id);
        assertEquals(SettingsDestination.RUNTIME, only("boot process").id);
        assertEquals(SettingsDestination.DATA, only("room cache").id);
        assertEquals(SettingsDestination.CONFIGURATION, only("typed command center").id);
        assertEquals(SettingsDestination.DIAGNOSTICS, only("root support bundle").id);
        assertEquals(SettingsDestination.ABOUT, only("version sdk").id);
        assertTrue(SettingsCatalog.filter("definitely absent").isEmpty());
    }

    @Test public void emptySearchReturnsStableCatalogOrder() {
        List<SettingsDestination> all = SettingsCatalog.all();
        List<SettingsDestination> filtered = SettingsCatalog.filter("  ");
        assertEquals(all.size(), filtered.size());
        for (int i = 0; i < all.size(); i++) assertEquals(all.get(i).id, filtered.get(i).id);
    }

    @Test public void oversizedSearchQueriesFailClosedForDirectFilters() {
        assertTrue(SettingsCatalog.filter(repeat('a', SettingsDestination.MAX_QUERY_CHARS + 1)).isEmpty());
        assertTrue(SettingsCatalog.filter("one two three four five six seven eight nine ten eleven twelve thirteen").isEmpty());
    }

    @Test public void boundedQueryIsSafeForUiSearch() {
        String bounded = SettingsDestination.boundedQuery("monitor\ncache	root" + repeat('x', 300));
        assertTrue(bounded.length() <= SettingsDestination.MAX_QUERY_CHARS);
        assertFalse(bounded.contains("\n"));
        assertFalse(bounded.contains("\t"));
    }

    private static SettingsDestination only(String query) {
        List<SettingsDestination> matches = SettingsCatalog.filter(query);
        assertEquals("Expected one match for " + query, 1, matches.size());
        return matches.get(0);
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}

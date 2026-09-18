package com.skystream.ssmusicplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class VersionComparatorTest {
    @Test
    public void comparesReleaseVersionsNumerically() {
        assertEquals(1, VersionComparator.compare("v0.10.0", "0.9.9"));
        assertEquals(0, VersionComparator.compare("v1.0", "1.0.0"));
        assertEquals(-1, VersionComparator.compare("v1.2.3", "1.3.0"));
    }

    @Test
    public void supportsLargeComponentsAndUppercasePrefix() {
        assertEquals(1, VersionComparator.compare("V99999999999999999999.1", "2147483647"));
        assertEquals(0, VersionComparator.compare("v01.002.0", "1.2"));
        assertEquals("1.2.0", VersionComparator.displayVersion("V1.2.0"));
    }

    @Test
    public void rejectsMissingMalformedAndPrereleaseVersions() {
        String[] invalid = {null, "", "v", "1.", ".1", "1..2", "-1", "1.2-beta",
                "1.2+build", " 1.2", "1.2 ", "release1", "1/2", "1\n"};
        for (String version : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> VersionComparator.compare(version, "1.0"));
            assertThrows(IllegalArgumentException.class,
                    () -> VersionComparator.compare("1.0", version));
            assertThrows(IllegalArgumentException.class,
                    () -> VersionComparator.displayVersion(version));
        }
        String tooLong = new String(new char[129]).replace('\0', '1');
        assertThrows(IllegalArgumentException.class,
                () -> VersionComparator.compare(tooLong, "1.0"));
    }
}

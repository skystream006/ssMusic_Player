package com.skystream.ssmusicplayer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UpdateCheckerTest {
    @Test
    public void comparesReleaseVersionsNumerically() {
        assertEquals(1, VersionComparator.compare("v0.10.0", "0.9.9"));
        assertEquals(0, VersionComparator.compare("v1.0", "1.0.0"));
        assertEquals(-1, VersionComparator.compare("v1.2.3", "1.3.0"));
    }
}

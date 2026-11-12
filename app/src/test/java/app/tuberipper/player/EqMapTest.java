package app.tuberipper.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EqMapTest {
    private static final short MIN = -1500, MAX = 1500;

    @Test
    public void aPhoneWithTheSameBandsGetsTheSameGains() {
        float[] gains = {6, 4, 0, -2, 3};
        assertArrayEquals(new short[]{600, 400, 0, -200, 300}, EqMap.levels(gains, EqMap.PAGE_BANDS_HZ, MIN, MAX));
    }

    @Test
    public void bandsOutsideTheRangeTakeTheEdgeGain() {
        float[] gains = {6, 4, 0, -2, 3};
        short[] l = EqMap.levels(gains, new float[]{30f, 20000f}, MIN, MAX);
        assertEquals(600, l[0]);
        assertEquals(300, l[1]);
    }

    @Test
    public void inBetweenIsInterpolatedOnALogScale() {
        float[] gains = {0, 10, 0, 0, 0};
        // 118 Hz is the log-midpoint of 60 and 230 Hz (sqrt(60*230) = 117.5)
        float mid = EqMap.interpolate(gains, 117.5f);
        assertEquals(5f, mid, 0.05f);
    }

    @Test
    public void levelsAreClampedToTheDevicesRange() {
        float[] gains = {12, 12, 12, 12, 12};
        short[] l = EqMap.levels(gains, EqMap.PAGE_BANDS_HZ, (short) -1000, (short) 500);
        for (short v : l) assertEquals(500, v);
    }

    @Test
    public void flatEqIsNeutral() {
        float[] flat = {0, 0, 0, 0, 0};
        for (short v : EqMap.levels(flat, new float[]{60f, 230f, 910f, 3600f, 14000f}, MIN, MAX)) assertEquals(0, v);
        assertEquals(1f, EqMap.headroom(flat), 0.0001f);
    }

    @Test
    public void headroomLowersTheVolumeByTheBiggestBoost() {
        assertEquals(0.5012f, EqMap.headroom(new float[]{6, 0, 0, -3, 2}), 0.001f);     // -6 dB
        assertEquals(1f, EqMap.headroom(new float[]{-4, -2, 0, 0, -1}), 0.0001f);       // cuts need none
        assertTrue(EqMap.headroom(new float[]{12, 12, 12, 12, 12}) < 0.26f);
    }

    @Test
    public void missingGainsDoNotCrash() {
        assertEquals(0f, EqMap.interpolate(new float[0], 500f), 0f);
        assertEquals(3f, EqMap.interpolate(new float[]{3f}, 5000f), 0f);
    }
}

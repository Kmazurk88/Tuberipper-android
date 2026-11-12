package app.tuberipper.player;

/** Maps the page's 5-band equalizer (60 Hz ... 14 kHz, -12..+12 dB) onto the bands the phone's equalizer really has
 *  (usually 5, but at other centre frequencies, with their own level range). Between the page's bands the gain is
 *  interpolated on a log-frequency scale. No Android classes, so it can be tested on a plain JVM. */
final class EqMap {
    private EqMap() { }

    /** The page's band centres (Hz), in the order of its gains. */
    static final float[] PAGE_BANDS_HZ = {60f, 230f, 910f, 3600f, 14000f};

    /** gainsDb: the page's gains. deviceCentreHz: the phone's band centres (Hz). Result: level per phone band in
     *  millibels, clamped to [minMb, maxMb]. */
    static short[] levels(float[] gainsDb, float[] deviceCentreHz, short minMb, short maxMb) {
        short[] out = new short[deviceCentreHz.length];
        for (int i = 0; i < out.length; i++) {
            float db = interpolate(gainsDb, deviceCentreHz[i]);
            int mb = Math.round(db * 100f);
            out[i] = (short) Math.max(minMb, Math.min(maxMb, mb));
        }
        return out;
    }

    static float interpolate(float[] gainsDb, float hz) {
        int n = Math.min(gainsDb.length, PAGE_BANDS_HZ.length);
        if (n == 0) return 0f;
        if (hz <= PAGE_BANDS_HZ[0]) return gainsDb[0];
        if (hz >= PAGE_BANDS_HZ[n - 1]) return gainsDb[n - 1];
        for (int i = 0; i < n - 1; i++) {
            if (hz <= PAGE_BANDS_HZ[i + 1]) {
                double a = Math.log(PAGE_BANDS_HZ[i]), b = Math.log(PAGE_BANDS_HZ[i + 1]);
                double t = (Math.log(hz) - a) / (b - a);
                return (float) (gainsDb[i] + t * (gainsDb[i + 1] - gainsDb[i]));
            }
        }
        return gainsDb[n - 1];
    }

    /** Headroom: how much to turn the whole signal down so the boosts don't clip (the page does the same). */
    static float headroom(float[] gainsDb) {
        float max = 0f;
        for (float g : gainsDb) max = Math.max(max, g);
        return (float) Math.pow(10.0, -max / 20.0);
    }
}

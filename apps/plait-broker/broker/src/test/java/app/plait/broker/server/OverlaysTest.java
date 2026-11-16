package app.plait.broker.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import org.junit.Test;

public class OverlaysTest {
    private static Overlays.Request reg(String stdin, String... args) {
        return Overlays.parse(Arrays.asList(args), stdin);
    }

    @Test
    public void acceptsColorsOnly() {
        Overlays.Request r = reg("color/system_accent1_500 ff3a7bd5\nandroid:color/x ff000000\n# c\n", "register", "plaitColors", "com.android.systemui");
        assertEquals(2, r.entries.size());
        assertEquals("plaitColors", r.name);
    }

    @Test
    public void acceptsNumbersForApps() {
        Overlays.Request r = reg("bool/flag true\ninteger/count -3\ndimen/size 12dp\ndimen/pad 0.5in\ncolor/c 80ffffff\n",
                "register", "plaitV", "com.example.app");
        assertEquals(5, r.entries.size());
        assertEquals(1, r.entries.get(0).data);
        assertEquals(Overlays.TYPE_INT_BOOLEAN, r.entries.get(0).type);
        assertEquals(-3, r.entries.get(1).data);
        assertEquals(Overlays.TYPE_DIMENSION, r.entries.get(2).type);
        assertEquals(false, r.colorsOnly());
    }

    @Test
    public void dimensionsArePackedLikeTypedValue() {
        // 12dp: mantissa 12, radix 0 (23p0), unit 1  ->  12 << 8 | 0 << 4 | 1
        assertEquals((12 << 8) | 1, Overlays.complexDimension(12f, "dp"));
        assertEquals((1 << 8) | 0, Overlays.complexDimension(1f, "px"));
        // fractions pick the smallest radix that holds them; TypedValue.complexToFloat must give the value back
        float[] radixMults = {0.00390625f, 3.051758E-5f, 1.192093E-7f, 4.656613E-10f};
        for (float v : new float[]{0.5f, 1.25f, 100.75f, -2.5f, 4000.125f}) {
            int packed = Overlays.complexDimension(v, "sp");
            assertEquals(2, packed & 0xf);
            // the mantissa is 24 bits, signed
            int mantissa = packed & 0xffffff00;
            assertEquals(v, mantissa * radixMults[(packed >> 4) & 3], Math.abs(v) * 1e-4f + 1e-4f);
        }
    }

    @Test
    public void frameworkTakesColorsOnly() {
        assertEquals(1, reg("color/c ff000000\n", "register", "plaitF", "android").entries.size());
        assertThrows(IllegalArgumentException.class, () -> reg("bool/x true\n", "register", "plaitF", "android"));
        assertThrows(IllegalArgumentException.class, () -> reg("integer/x 1\n", "register", "plaitF", "android"));
    }

    @Test
    public void behaviorSwitchesAreRefusedEverywhere() {
        assertThrows(IllegalArgumentException.class, () -> reg("bool/config_enableX true\n", "register", "plaitX", "com.android.systemui"));
        assertThrows(IllegalArgumentException.class, () -> reg("android:integer/config_y 1\n", "register", "plaitX", "com.example"));
    }

    @Test
    public void refusesAnythingElse() {
        assertThrows(IllegalArgumentException.class, () -> reg("bool/x 1\n", "register", "plaitX", "a.b"));
        assertThrows(IllegalArgumentException.class, () -> reg("dimen/x 12\n", "register", "plaitX", "a.b"));
        assertThrows(IllegalArgumentException.class, () -> reg("integer/x 99999999999\n", "register", "plaitX", "a.b"));
        assertThrows(IllegalArgumentException.class, () -> reg("string/app_name hello\n", "register", "plaitX", "a.b"));
        assertThrows(IllegalArgumentException.class, () -> reg("drawable/x @file\n", "register", "plaitX", "a.b"));
        assertThrows(IllegalArgumentException.class, () -> reg("color/x ff000000\n", "register", "other", "a.b"));
        assertThrows(IllegalArgumentException.class, () -> reg("color/x ff000000\n", "register", "plaitX", "a b; rm"));
        assertThrows(IllegalArgumentException.class, () -> reg("", "register", "plaitX", "a.b"));
        assertThrows(IllegalArgumentException.class, () -> reg("", "unregister", "systemui"));
    }

    @Test
    public void unregisterNeedsPlaitName() {
        assertEquals("plaitColors", reg("", "unregister", "plaitColors").name);
    }

    @Test
    public void requestSurvivesTheHelperRoundTrip() {
        Overlays.Request r = reg("color/a ff010203\n", "register", "plaitA", "x.y");
        String text = r.serialize();
        String[] header = text.substring(0, text.indexOf('\n')).split(" ");
        Overlays.Request back = Overlays.parse(Arrays.asList(header), text.substring(text.indexOf('\n') + 1));
        assertEquals(r.serialize(), back.serialize());
    }
}

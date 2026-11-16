package app.plait.broker.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RelaysTest {
    @Test
    public void brokerApiPermissionsAreRecognized() {
        assertTrue(Relays.isBrokerApi("moe.shizuku.manager.permission.API_V23"));
        assertTrue(Relays.isBrokerApi("moe.shizuku.manager.permission.API"));
        assertTrue(Relays.isBrokerApi("com.rosan.dhizuku.permission.API"));
        assertTrue(Relays.isBrokerApi("io.github.someone.shizukufork.permission.API_V23"));
    }

    @Test
    public void ordinaryPermissionsAreNot() {
        assertFalse(Relays.isBrokerApi(null));
        assertFalse(Relays.isBrokerApi("android.permission.INTERNET"));
        assertFalse(Relays.isBrokerApi("app.morphe.manager.permission.C2D_MESSAGE"));
        assertFalse(Relays.isBrokerApi("moe.shizuku.manager.permission.API_V23_EXTRA"));
    }
}

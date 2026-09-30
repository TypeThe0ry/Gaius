package dev.gaius.browser;

import net.minecraft.server.players.PlayerList;

/**
 * Minecraft 26.3 version of the PlayerList compatibility helper.
 *
 * <p>26.3 removed PlayerList.setAllowCommandsForAllPlayers(boolean). Its
 * replacement (a Gaius flag read by the patched PlayerList.isOp) belongs to
 * work package P7a; until then a 26.3 Worker fails loudly here instead of
 * starting without local command permissions.
 */
public final class BrowserPlayerListCompat {
    private BrowserPlayerListCompat() {}

    /** Lets the single local Worker player use commands without writing ops.json. */
    public static void allowCommandsForAllPlayers(PlayerList playerList) {
        throw new UnsupportedOperationException("P7a");
    }
}

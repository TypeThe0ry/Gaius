package dev.gaius.browser;

import net.minecraft.server.players.PlayerList;

/**
 * Version-specific PlayerList operations used by the browser Worker server.
 *
 * <p>Minecraft 26.2 keeps the vanilla "allow commands for all players" setter.
 * Profiles whose PlayerList differs provide a replacement with the same
 * relative path under port/src/versions/&lt;profile&gt;/java.
 */
public final class BrowserPlayerListCompat {
    private BrowserPlayerListCompat() {}

    /** Lets the single local Worker player use commands without writing ops.json. */
    public static void allowCommandsForAllPlayers(PlayerList playerList) {
        playerList.setAllowCommandsForAllPlayers(true);
    }
}

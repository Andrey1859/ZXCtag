package ru.andrey1859.npcskin;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Handles /npc commands. The skin lookup runs on a background thread;
 * NPC creation happens on the main thread after the lookup completes.
 */
public final class NpcCommand implements CommandExecutor {

    private static final Pattern NICK_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private final NpcPlugin plugin;

    public NpcCommand(NpcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        if (!sender.hasPermission("npc.use")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§eUsage: /npc <nick> | /npc clear");
            return true;
        }
        if ("clear".equalsIgnoreCase(args[0])) {
            plugin.getManager().removeAll();
            player.sendMessage("§aAll NPCs were removed.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("§eUsage: /npc <nick> | /npc clear");
            return true;
        }
        String nick = args[0];
        if (!NICK_PATTERN.matcher(nick).matches()) {
            player.sendMessage("§cInvalid nickname. Use 1-16 characters: letters, digits and _.");
            return true;
        }

        // Capture everything from the main thread before going async.
        Location location = player.getLocation();
        float bodyYaw = location.getYaw();
        float pitch = location.getPitch();
        float headYaw = ((CraftPlayer) player).getHandle().yHeadRot;
        boolean crouching = player.isSneaking();

        CompletableFuture.supplyAsync(() -> {
            try {
                return SkinResolver.fetch(nick);
            } catch (SkinResolver.SkinException e) {
                throw new CompletionException(e);
            }
        }, plugin.getSkinExecutor())
                .thenAcceptAsync(skin -> {
                    // Do not create the NPC if the player left or the plugin is disabled.
                    if (!plugin.isEnabled() || !player.isOnline()) {
                        return;
                    }
                    plugin.getScheduler().runTask(plugin, () -> createNpc(
                            player, nick, skin, location, bodyYaw, headYaw, pitch, crouching));
                }, task -> plugin.getScheduler().runTask(plugin, task))
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    plugin.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage("§cFailed to load skin: " + cause.getMessage());
                        }
                    });
                    return null;
                });
        return true;
    }

    private void createNpc(Player player, String nick, SkinResolver.SkinData skin, Location location,
                           float bodyYaw, float headYaw, float pitch, boolean crouching) {
        try {
            NpcManager.NpcView view = plugin.getManager().create(
                    player, nick, skin, location, bodyYaw, headYaw, pitch, crouching);
            plugin.getManager().showTo(player);
            player.sendMessage("§aNPC created: " + view.profileName() + " (skin: " + nick + ").");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create NPC for " + nick + ": " + e.getMessage());
            player.sendMessage("§cFailed to create NPC: " + e.getMessage());
        }
    }
}

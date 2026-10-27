package ru.andrey1859.npcskin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.level.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftPlayer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory registry of fake player NPCs. Nothing is persisted.
 */
public final class NpcManager {

    /** One NPC: profile identity, the NMS entity and the data needed to re-show it. */
    public record NpcView(
            UUID profileUuid,
            String profileName,
            ServerPlayer player,
            World world,
            SkinResolver.SkinData skin) {}

    private static final int MAX_NPCS = 100;

    private final NpcPlugin plugin;
    private final NpcPacketAdapter adapter;
    private final Map<UUID, NpcView> npcs = new LinkedHashMap<>();
    private int profileNameCounter;

    public NpcManager(NpcPlugin plugin) {
        this.plugin = plugin;
        this.adapter = new NpcPacketAdapter();
    }

    /** Creates an NPC at the given location. Main thread only. */
    public synchronized NpcView create(Player creator, String nick, SkinResolver.SkinData skin, Location location,
                                       float bodyYaw, float headYaw, float pitch, boolean crouching) {
        if (npcs.size() >= MAX_NPCS) {
            throw new IllegalStateException("Server NPC limit reached (" + MAX_NPCS + ")");
        }
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalStateException("Player is not in a world");
        }
        MinecraftServer server = ((CraftServer) plugin.getServer()).getServer();
        ServerLevel level = ((CraftWorld) world).getHandle();
        String profileName = uniqueProfileName(nick);
        GameProfile profile = new GameProfile(UUID.randomUUID(), profileName);
        profile.getProperties().put("textures", new Property("textures", skin.value(), skin.signature()));
        NpcView view = adapter.create(server, level, profile, skin, location, bodyYaw, headYaw, pitch, crouching);
        npcs.put(profile.getId(), view);
        return view;
    }

    /** Shows all NPCs of the viewer's world to the viewer. Main thread only. */
    public synchronized void showTo(Player viewer) {
        World world = viewer.getWorld();
        if (world == null) {
            return;
        }
        ServerPlayer handle = ((CraftPlayer) viewer).getHandle();
        for (NpcView view : npcs.values()) {
            if (view.world().equals(world)) {
                adapter.show(handle, view);
            }
        }
    }

    /** Removes the NPC representations of one world for one viewer (NPCs stay for others). */
    public synchronized void hideWorld(Player viewer, World world) {
        ServerPlayer handle = ((CraftPlayer) viewer).getHandle();
        for (NpcView view : npcs.values()) {
            if (view.world().equals(world)) {
                adapter.remove(handle, view);
            }
        }
    }

    /** Removes all NPCs and sends removal packets to every online viewer. */
    public synchronized void removeAll() {
        if (npcs.isEmpty()) {
            return;
        }
        for (NpcView view : npcs.values()) {
            for (Player player : view.world().getPlayers()) {
                adapter.remove(((CraftPlayer) player).getHandle(), view);
            }
        }
        npcs.clear();
    }

    public synchronized int count() {
        return npcs.size();
    }

    /** Unique profile name not longer than 16 characters. */
    private String uniqueProfileName(String nick) {
        String base = nick.length() > 10 ? nick.substring(0, 10) : nick;
        String candidate = base;
        while (npcs.values().stream().anyMatch(view -> view.profileName().equals(candidate))) {
            candidate = base + "-" + (++profileNameCounter);
            if (candidate.length() > 16) {
                candidate = candidate.substring(0, 16);
            }
        }
        return candidate;
    }
}

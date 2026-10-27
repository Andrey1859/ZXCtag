package ru.andrey1859.npcskin;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;

import java.util.List;

/**
 * All NMS interaction for fake skin NPCs: ServerPlayer creation and the packet
 * sequence that makes a normal Minecraft client display the NPC without mods.
 */
public final class NpcPacketAdapter {

    /** Mask of all 7 PlayerModelPart flags: every standard skin layer is visible. */
    private static final int ALL_MODEL_PARTS = 0x7F;

    public NpcPacketAdapter() {}

    /**
     * Creates the fake player entity. Must be called on the main thread.
     */
    public NpcManager.NpcView create(MinecraftServer server, ServerLevel level, GameProfile profile,
                                     SkinResolver.SkinData skin, Location location,
                                     float bodyYaw, float headYaw, float pitch, boolean crouching) {
        ClientInformation info = new ClientInformation(
                "en_us", 2, ChatVisiblity.FULL, true,
                ALL_MODEL_PARTS, Player.DEFAULT_MAIN_HAND,
                false, false, ParticleStatus.ALL);
        ServerPlayer npc = new ServerPlayer(server, level, profile, info);
        // Player info packets read connection/gameMode/chat state from the ServerPlayer.
        npc.connection = new ServerGamePacketListenerImpl(
                server, new Connection(PacketFlow.SERVERBOUND()), npc,
                CommonListenerCookie.createInitial(profile, false));

        npc.setPos(location.getX(), location.getY(), location.getZ());
        npc.setYRot(bodyYaw);
        npc.setXRot(pitch);
        // Sync old and current rotations so the first spawn shows no rotation snap.
        npc.yRotO = bodyYaw;
        npc.xRotO = pitch;
        npc.yBodyRot = bodyYaw;
        npc.yBodyRotO = bodyYaw;
        npc.yHeadRot = headYaw;
        npc.yHeadRotO = headYaw;
        npc.setPose(crouching ? Pose.CROUCHING : Pose.STANDING);
        npc.setShiftKeyDown(crouching);

        return new NpcManager.NpcView(profile.getId(), profile.getName(), npc, location.getWorld(), skin);
    }

    /** Sends the full spawn sequence for one NPC to one viewer. Main thread only. */
    public void show(ServerPlayer viewer, NpcManager.NpcView view) {
        ServerPlayer npc = view.player();
        viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(npc)));
        viewer.connection.send(new ClientboundAddEntityPacket(
                npc.getId(), npc.getUUID(),
                npc.getX(), npc.getY(), npc.getZ(),
                npc.getXRot(), npc.getYRot(),
                EntityType.PLAYER, 0, Vec3.ZERO,
                npc.getYHeadRot()));
        viewer.connection.send(new ClientboundSetEntityDataPacket(
                npc.getId(), npc.getEntityData().getNonDefaultValues()));
        viewer.connection.send(new ClientboundRotateHeadPacket(npc, Mth.packDegrees(npc.getYHeadRot())));
        viewer.connection.send(new ClientboundMoveEntityPacket.Rot(
                npc.getId(), Mth.packDegrees(npc.getYRot()), Mth.packDegrees(npc.getXRot()), false));
    }

    /** Sends the removal sequence (entity + profile info) for one NPC to one viewer. */
    public void remove(ServerPlayer viewer, NpcManager.NpcView view) {
        viewer.connection.send(new ClientboundRemoveEntitiesPacket(view.player().getId()));
        viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(view.profileUuid())));
    }
}

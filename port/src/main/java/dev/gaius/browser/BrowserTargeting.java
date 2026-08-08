package dev.gaius.browser;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Recomputes browser block targeting from the camera state rendered in the current frame. */
public final class BrowserTargeting {
    private BrowserTargeting() {
    }

    public static HitResult stabilizeBlockHit(
            HitResult current,
            Minecraft minecraft,
            Camera camera) {
        if (current instanceof EntityHitResult
                || minecraft == null
                || minecraft.level == null
                || minecraft.player == null
                || camera == null
                || !camera.isInitialized()
                || camera.entity() == null) {
            return current;
        }

        Vec3 start = camera.position();
        Vec3 forward = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        Entity cameraEntity = camera.entity();

        double range = minecraft.player.blockInteractionRange();
        Vec3 end = start.add(
                forward.x * range,
                forward.y * range,
                forward.z * range);
        return minecraft.level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                cameraEntity));
    }
}

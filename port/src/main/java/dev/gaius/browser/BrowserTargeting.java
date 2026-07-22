package dev.gaius.browser;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/** Recomputes browser block targeting from the camera state rendered in the current frame. */
public final class BrowserTargeting {
    private static Object lastLevel;
    private static Object lastCameraEntity;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static float lastForwardX;
    private static float lastForwardY;
    private static float lastForwardZ;
    private static boolean hasLastCamera;

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
        Vector3fc forward = camera.forwardVector();
        Entity cameraEntity = camera.entity();
        if (hasLastCamera
                && lastLevel == minecraft.level
                && lastCameraEntity == cameraEntity
                && Math.abs(start.x - lastX) < 1.0E-9
                && Math.abs(start.y - lastY) < 1.0E-9
                && Math.abs(start.z - lastZ) < 1.0E-9
                && Math.abs(forward.x() - lastForwardX) < 1.0E-7F
                && Math.abs(forward.y() - lastForwardY) < 1.0E-7F
                && Math.abs(forward.z() - lastForwardZ) < 1.0E-7F) {
            return current;
        }
        hasLastCamera = true;
        lastLevel = minecraft.level;
        lastCameraEntity = cameraEntity;
        lastX = start.x;
        lastY = start.y;
        lastZ = start.z;
        lastForwardX = forward.x();
        lastForwardY = forward.y();
        lastForwardZ = forward.z();

        double range = minecraft.player.blockInteractionRange();
        Vec3 end = start.add(
                forward.x() * range,
                forward.y() * range,
                forward.z() * range);
        return minecraft.level.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                cameraEntity));
    }
}

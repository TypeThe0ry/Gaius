package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Exact browser implementation of biome zoom's nearest-corner calculation. */
public final class BrowserBiomeManager {
    private BrowserBiomeManager() {
    }

    @JSBody(params = {"seed", "shiftedX", "shiftedY", "shiftedZ"}, script = """
            const constants = globalThis.__gaiusBiomeManagerConstants
              || (globalThis.__gaiusBiomeManagerConstants = {
                multiplier: BigInt("6364136223846793005"),
                increment: BigInt("1442695040888963407"),
                shift: BigInt(24),
                mask: BigInt(1023)
              });
            const multiplier = constants.multiplier;
            const increment = constants.increment;

            const baseX = (shiftedX | 0) >> 2;
            const baseY = (shiftedY | 0) >> 2;
            const baseZ = (shiftedZ | 0) >> 2;
            const fractionX = (shiftedX & 3) / 4.0;
            const fractionY = (shiftedY & 3) / 4.0;
            const fractionZ = (shiftedZ & 3) / 4.0;
            const quartX0 = BigInt(baseX);
            const quartX1 = BigInt(baseX + 1);
            const quartY0 = BigInt(baseY);
            const quartY1 = BigInt(baseY + 1);
            const quartZ0 = BigInt(baseZ);
            const quartZ1 = BigInt(baseZ + 1);

            let nearest = 0;
            let nearestDistance = Infinity;
            for (let corner = 0; corner < 8; corner++) {
              const highX = (corner & 4) !== 0;
              const highY = (corner & 2) !== 0;
              const highZ = (corner & 1) !== 0;
              const quartX = highX ? quartX1 : quartX0;
              const quartY = highY ? quartY1 : quartY0;
              const quartZ = highZ ? quartZ1 : quartZ0;
              let value = BigInt.asIntN(
                64, seed * (seed * multiplier + increment) + quartX);
              value = BigInt.asIntN(
                64, value * (value * multiplier + increment) + quartY);
              value = BigInt.asIntN(
                64, value * (value * multiplier + increment) + quartZ);
              value = BigInt.asIntN(
                64, value * (value * multiplier + increment) + quartX);
              value = BigInt.asIntN(
                64, value * (value * multiplier + increment) + quartY);
              value = BigInt.asIntN(
                64, value * (value * multiplier + increment) + quartZ);
              const distanceX = (highX ? fractionX - 1.0 : fractionX)
                + (Number((value >> constants.shift) & constants.mask) / 1024.0 - 0.5) * 0.9;
              value = BigInt.asIntN(
                64, value * (value * multiplier + increment) + seed);
              const distanceY = (highY ? fractionY - 1.0 : fractionY)
                + (Number((value >> constants.shift) & constants.mask) / 1024.0 - 0.5) * 0.9;
              value = BigInt.asIntN(
                64, value * (value * multiplier + increment) + seed);
              const distanceZ = (highZ ? fractionZ - 1.0 : fractionZ)
                + (Number((value >> constants.shift) & constants.mask) / 1024.0 - 0.5) * 0.9;
              const distance = distanceZ * distanceZ
                + distanceY * distanceY + distanceX * distanceX;
              if (nearestDistance > distance) {
                nearest = corner;
                nearestDistance = distance;
              }
            }
            return nearest | 0;
            """)
    public static native int nearestCorner(long seed, int shiftedX, int shiftedY, int shiftedZ);
}

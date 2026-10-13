package net.minecraft.world.level;

public final class ChunkPos {
    public final int x;
    public final int z;

    public ChunkPos(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public int getRegionLocalX() {
        return x & 31;
    }

    public int getRegionLocalZ() {
        return z & 31;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    @Override
    public String toString() {
        return "[" + x + ", " + z + "]";
    }
}

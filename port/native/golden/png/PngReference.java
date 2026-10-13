package org.lwjgl.stb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PngReference {
    private static volatile int blackhole;

    private record Input(byte[] encoded, int desiredChannels, Path expected) {
    }

    private static byte[] decode(Input input, boolean flip) {
        IntBuffer width = IntBuffer.allocate(2);
        IntBuffer height = IntBuffer.allocate(2);
        IntBuffer channels = IntBuffer.allocate(2);
        width.position(1);
        height.position(1);
        channels.position(1);
        ByteBuffer encoded = ByteBuffer.allocate(input.encoded.length + 5);
        encoded.position(3);
        encoded.put(input.encoded).flip().position(3);
        STBImage.stbi_set_flip_vertically_on_load(flip);
        ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, width, height, channels, input.desiredChannels);
        if (pixels == null) {
            throw new AssertionError(input.expected + ": " + STBImage.stbi_failure_reason());
        }
        if (encoded.position() != 3 || width.position() != 1 || height.position() != 1 || channels.position() != 1) {
            throw new AssertionError("buffer positions changed");
        }
        int outputChannels = input.desiredChannels == 0 ? channels.get(1) : input.desiredChannels;
        byte[] result = new byte[16 + pixels.remaining()];
        ByteBuffer payload = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(width.get(1)).putInt(height.get(1)).putInt(channels.get(1)).putInt(outputChannels);
        payload.put(pixels);
        STBImage.stbi_image_free(pixels);
        return result;
    }

    private static void checkBridge(Input input, byte[] expected) {
        for (boolean flip : new boolean[] { false, true }) {
            IntBuffer width = IntBuffer.allocate(2);
            IntBuffer height = IntBuffer.allocate(2);
            IntBuffer channels = IntBuffer.allocate(2);
            width.position(1);
            height.position(1);
            channels.position(1);
            ByteBuffer pixels = PngPixels.materialize(expected, input.desiredChannels, flip, width, height, channels);
            byte[] reference = flip ? decode(input, true) : expected;
            byte[] actual = new byte[pixels.remaining()];
            pixels.get(actual);
            ByteBuffer header = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN);
            if (width.get(1) != header.getInt() || height.get(1) != header.getInt()
                    || channels.get(1) != header.getInt() || width.position() != 1
                    || !Arrays.equals(actual, Arrays.copyOfRange(reference, 16, reference.length))) {
                throw new AssertionError("bridge mismatch: " + input.expected + " flip=" + flip);
            }
            STBImage.stbi_image_free(pixels);
        }
    }

    private static void benchmark(Input input) {
        STBImage.stbi_set_flip_vertically_on_load(false);
        ByteBuffer pixels = STBImage.stbi_load_from_memory(ByteBuffer.wrap(input.encoded),
                IntBuffer.allocate(1), IntBuffer.allocate(1), IntBuffer.allocate(1), input.desiredChannels);
        if (pixels == null) {
            throw new AssertionError(STBImage.stbi_failure_reason());
        }
        blackhole ^= pixels.get(0);
        STBImage.stbi_image_free(pixels);
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("PngReference <manifest.tsv> <rounds> <write|verify>");
        }
        int rounds = Integer.parseInt(arguments[1]);
        if (rounds < 1) {
            throw new IllegalArgumentException("rounds must be positive");
        }
        List<Input> inputs = new ArrayList<>();
        long bytes = 0;
        long outputBytes = 0;
        for (String line : Files.readAllLines(Path.of(arguments[0]))) {
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t");
            Input input = new Input(Files.readAllBytes(Path.of(fields[0])), Integer.parseInt(fields[1]), Path.of(fields[2]));
            byte[] expected = decode(input, false);
            checkBridge(input, expected);
            if (arguments[2].equals("write")) {
                Files.write(input.expected, expected);
            } else if (!Arrays.equals(Files.readAllBytes(input.expected), expected)) {
                throw new AssertionError("reference changed: " + input.expected);
            }
            bytes += input.encoded.length;
            outputBytes += expected.length - 16;
            inputs.add(input);
        }
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("empty manifest");
        }
        for (int warmup = 0; warmup < 2; warmup++) {
            for (Input input : inputs) {
                benchmark(input);
            }
        }
        long start = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            for (Input input : inputs) {
                benchmark(input);
            }
        }
        double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf(java.util.Locale.ROOT,
                "{\"count\":%d,\"bytes\":%d,\"outputBytes\":%d,\"rounds\":%d,\"totalMs\":%.6f,\"msPerRound\":%.6f,\"parity\":true}%n",
                inputs.size(), bytes, outputBytes, rounds, milliseconds, milliseconds / rounds);
    }
}

package com.terracontinental.atlas;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The atlas, sent to an admin who opens the World Atlas item. Per point one byte of height
 * (8 blocks a step from sea level) and one of owner (0 sea, 255 island, else continent + 1),
 * deflated: ~440k points come to a few hundred KB, under the 1 MB custom-payload limit.
 */
public record WorldMapPayload(int half, int step, int sea, byte[] packed, List<Entry> continents,
                              String pregen) implements CustomPacketPayload {

    public static final Type<WorldMapPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("terra_continental", "world_map"));

    public record Entry(String name, String key, float areaKm2, int centerX, int centerZ,
                        int minX, int minZ, int maxX, int maxZ, boolean pregenerated) {
    }

    public static final StreamCodec<ByteBuf, WorldMapPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                FriendlyByteBuf f = new FriendlyByteBuf(buf);
                f.writeVarInt(p.half);
                f.writeVarInt(p.step);
                f.writeVarInt(p.sea);
                f.writeByteArray(p.packed);
                f.writeVarInt(p.continents.size());
                for (Entry e : p.continents) {
                    f.writeUtf(e.name);
                    f.writeUtf(e.key);
                    f.writeFloat(e.areaKm2);
                    f.writeInt(e.centerX);
                    f.writeInt(e.centerZ);
                    f.writeInt(e.minX);
                    f.writeInt(e.minZ);
                    f.writeInt(e.maxX);
                    f.writeInt(e.maxZ);
                    f.writeBoolean(e.pregenerated);
                }
                f.writeUtf(p.pregen);
            },
            buf -> {
                FriendlyByteBuf f = new FriendlyByteBuf(buf);
                int half = f.readVarInt(), step = f.readVarInt(), sea = f.readVarInt();
                byte[] packed = f.readByteArray(4 << 20);
                int count = f.readVarInt();
                List<Entry> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    list.add(new Entry(f.readUtf(), f.readUtf(), f.readFloat(), f.readInt(), f.readInt(),
                            f.readInt(), f.readInt(), f.readInt(), f.readInt(), f.readBoolean()));
                }
                return new WorldMapPayload(half, step, sea, packed, list, f.readUtf());
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public int n() {
        return 2 * half + 1;
    }

    public static WorldMapPayload of(ContinentAtlas a, java.util.Set<String> pregenerated, String pregen) {
        int nn = a.n * a.n;
        byte[] raw = new byte[2 * nn];
        for (int i = 0; i < nn; i++) {
            raw[i] = (byte) Math.max(-127, Math.min(127, Math.floorDiv(a.height[i] - a.sea, 8)));
            int l = a.label[i];
            raw[nn + i] = l == 0 ? 0 : a.componentContinent[l - 1] < 0 ? (byte) 255 : (byte) (a.componentContinent[l - 1] + 1);
        }
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4);
        byte[] chunk = new byte[65536];
        while (!d.finished()) out.write(chunk, 0, d.deflate(chunk));
        d.end();
        List<Entry> list = new ArrayList<>();
        for (ContinentAtlas.Continent c : a.continents) {
            list.add(new Entry(c.name(), c.key(), (float) c.areaKm2(), c.centerX(), c.centerZ(),
                    c.minX(), c.minZ(), c.maxX(), c.maxZ(), pregenerated.contains(c.key())));
        }
        byte[] packed = out.toByteArray();
        com.mojang.logging.LogUtils.getLogger().info("[atlas] world map payload: {} KB for {} points", packed.length / 1024, nn);
        return new WorldMapPayload(a.half, ContinentAtlas.STEP, a.sea, packed, list, pregen);
    }

    /** {heights, owners}, each n x n. */
    public byte[][] unpack() {
        int nn = n() * n();
        byte[] raw = new byte[2 * nn];
        Inflater inf = new Inflater();
        inf.setInput(packed);
        try {
            int off = 0;
            while (off < raw.length && !inf.finished()) off += inf.inflate(raw, off, raw.length - off);
        } catch (DataFormatException e) {
            throw new IllegalStateException("bad world map payload", e);
        } finally {
            inf.end();
        }
        byte[] h = new byte[nn], o = new byte[nn];
        System.arraycopy(raw, 0, h, 0, nn);
        System.arraycopy(raw, nn, o, 0, nn);
        return new byte[][]{h, o};
    }
}

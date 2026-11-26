package io.evangelium.server.player;

import com.mojang.serialization.DynamicOps;
import io.evangelium.server.config.EvangeliumServerConfiguration;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.tags.TagNetworkSerialization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Evangelium fork: the registry data and the tags a client gets in the configuration phase, built once per known-packs set instead
 * of once per join (the main thread spent 10-50 ms per join on it: every registry element not in a known pack is encoded with its
 * codec, every tag of every network registry is turned into id lists). Cleared whenever tags change (datapack reload, tag binding)
 * or the registry access object changes. Every join still gets its own packet objects and its own outer lists/maps (a plugin that
 * edits a packet in place cannot reach the next player); the elements inside (NBT of registry entries, tag id lists) are shared and
 * never modified by the server.
 */
public final class RegistrySyncCache {

    private static final boolean PROPERTY = Boolean.parseBoolean(System.getProperty("evangeliumserver.cacheRegistrySync", "true"));
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final Map<Set<KnownPack>, Entry> CACHE = new ConcurrentHashMap<>();

    private record Entry(Object registries, int generation, List<Map.Entry<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>>> registryData,
                         Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags) {
    }

    private RegistrySyncCache() {
    }

    public static boolean enabled() {
        return PROPERTY && EvangeliumServerConfiguration.get().optimizations.cacheRegistrySync;
    }

    /** Tags (or registries) changed: build the packets again at the next join. */
    public static void invalidate() {
        GENERATION.incrementAndGet();
        CACHE.clear();
    }

    /** Sends what SynchronizeRegistriesTask.sendRegistries sends, from the cache. */
    public static void send(LayeredRegistryAccess<RegistryLayer> registries, Set<KnownPack> packs, Consumer<Packet<?>> packetSender) {
        int generation = GENERATION.get();
        Entry entry = CACHE.get(packs);
        if (entry == null || entry.registries != registries || entry.generation != generation) {
            DynamicOps<Tag> dynamicOps = registries.compositeAccess().createSerializationContext(NbtOps.INSTANCE);
            List<Map.Entry<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>>> data = new ArrayList<>();
            RegistrySynchronization.packRegistries(dynamicOps, registries.getAccessFrom(RegistryLayer.WORLD), packs,
                (resourceKey, list) -> data.add(Map.entry(resourceKey, List.copyOf(list))));
            entry = new Entry(registries, generation, List.copyOf(data), Map.copyOf(TagNetworkSerialization.serializeTagsToNetwork(registries)));
            if (GENERATION.get() == generation) {
                CACHE.put(Set.copyOf(packs), entry);
            }
        }
        for (Map.Entry<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>> e : entry.registryData) {
            packetSender.accept(new ClientboundRegistryDataPacket(e.getKey(), new ArrayList<>(e.getValue())));
        }
        packetSender.accept(new ClientboundUpdateTagsPacket(new HashMap<>(entry.tags)));
    }
}

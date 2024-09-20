/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.neoforge;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.lucko.spark.common.platform.world.AbstractChunkInfo;
import me.lucko.spark.common.platform.world.CountMap;
import me.lucko.spark.common.platform.world.WorldInfoProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public abstract class NeoForgeWorldInfoProvider implements WorldInfoProvider {

    protected abstract PackRepository getPackRepository();

    @Override
    public Collection<DataPackInfo> pollDataPacks() {
        return getPackRepository().getSelectedPacks().stream()
                .map(pack -> new DataPackInfo(
                        pack.getId(),
                        pack.getDescription().getString(),
                        resourcePackSource(pack.getPackSource())
                ))
                .collect(Collectors.toList());
    }

    private static String resourcePackSource(PackSource source) {
        if (source == PackSource.DEFAULT) {
            return "none";
        } else if (source == PackSource.BUILT_IN) {
            return "builtin";
        } else if (source == PackSource.WORLD) {
            return "world";
        } else if (source == PackSource.SERVER) {
            return "server";
        } else {
            return "unknown";
        }
    }

    public static final class Server extends NeoForgeWorldInfoProvider {
        private final MinecraftServer server;

        public Server(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public CountsResult pollCounts() {
            int players = this.server.getPlayerCount();
            int entities = 0;
            int chunks = 0;

            for (ServerLevel level : this.server.getAllLevels()) {

                if (ModList.get().isLoaded("moonrise")) {
                    entities += MoonriseMethods.getEntityCount(level.getEntities());
                } else {
                    PersistentEntitySectionManager<Entity> entityManager = level.entityManager;
                    EntityLookup<Entity> entityIndex = entityManager.visibleEntityStorage;
                    entities += entityIndex.count();
                }

                chunks += level.getChunkSource().getLoadedChunksCount();
            }

            return new CountsResult(players, entities, -1, chunks);
        }

        @Override
        public ChunksResult<ForgeChunkInfo> pollChunks() {
            ChunksResult<ForgeChunkInfo> data = new ChunksResult<>();

            for (ServerLevel level : this.server.getAllLevels()) {
                Long2ObjectOpenHashMap<ForgeChunkInfo> levelInfos = new Long2ObjectOpenHashMap<>();

                for (Entity entity : level.getEntities().getAll()) {
                    ForgeChunkInfo info = levelInfos.computeIfAbsent(
                        entity.chunkPosition().toLong(), ForgeChunkInfo::new);
                    info.entityCounts.increment(entity.getType());
                }

                data.put(level.dimension().location().getPath(), List.copyOf(levelInfos.values()));
            }

            return data;
        }

        @Override
        public GameRulesResult pollGameRules() {
            GameRulesResult data = new GameRulesResult();
            Iterable<ServerLevel> levels = this.server.getAllLevels();

            GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
                @Override
                public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                    String defaultValue = type.createRule().serialize();
                    data.putDefault(key.getId(), defaultValue);

                    for (ServerLevel level : levels) {
                        String levelName = level.dimension().location().getPath();

                        String value = level.getGameRules().getRule(key).serialize();
                        data.put(key.getId(), levelName, value);
                    }
                }
            });

            return data;
        }

        @Override
        protected PackRepository getPackRepository() {
            return this.server.getPackRepository();
        }
    }

    public static final class Client extends NeoForgeWorldInfoProvider {
        private final Minecraft client;

        public Client(Minecraft client) {
            this.client = client;
        }

        @Override
        public CountsResult pollCounts() {
            ClientLevel level = this.client.level;
            if (level == null) {
                return null;
            }

            int entities;
            if (ModList.get().isLoaded("moonrise")) {
                entities = MoonriseMethods.getEntityCount(level.getEntities());
            } else {
                TransientEntitySectionManager<Entity> entityManager = level.entityStorage;
                EntityLookup<Entity> entityIndex = entityManager.entityStorage;
                entities = entityIndex.count();
            }

            int chunks = level.getChunkSource().getLoadedChunksCount();

            return new CountsResult(-1, entities, -1, chunks);
        }

        @Override
        public ChunksResult<ForgeChunkInfo> pollChunks() {
            ClientLevel level = this.client.level;
            if (level == null) {
                return null;
            }

            ChunksResult<ForgeChunkInfo> data = new ChunksResult<>();

            Long2ObjectOpenHashMap<ForgeChunkInfo> levelInfos = new Long2ObjectOpenHashMap<>();

            for (Entity entity : level.getEntities().getAll()) {
                ForgeChunkInfo info = levelInfos.computeIfAbsent(entity.chunkPosition().toLong(), ForgeChunkInfo::new);
                info.entityCounts.increment(entity.getType());
            }

            data.put(level.dimension().location().getPath(), List.copyOf(levelInfos.values()));

            return data;
        }

        @Override
        public GameRulesResult pollGameRules() {
            ClientLevel level = this.client.level;
            if (level == null) {
                return null;
            }

            GameRulesResult data = new GameRulesResult();

            String levelName = level.dimension().location().getPath();
            GameRules levelRules = level.getGameRules();

            GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
                @Override
                public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                    String defaultValue = type.createRule().serialize();
                    data.putDefault(key.getId(), defaultValue);

                    String value = levelRules.getRule(key).serialize();
                    data.put(key.getId(), levelName, value);
                }
            });

            return data;
        }

        @Override
        protected PackRepository getPackRepository() {
            return this.client.getResourcePackRepository();
        }
    }

    public static final class ForgeChunkInfo extends AbstractChunkInfo<EntityType<?>> {
        private final CountMap<EntityType<?>> entityCounts;

        ForgeChunkInfo(long chunkPos) {
            super(ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos));

            this.entityCounts = new CountMap.Simple<>(new HashMap<>());
        }

        @Override
        public CountMap<EntityType<?>> getEntityCounts() {
            return this.entityCounts;
        }

        @Override
        public String entityTypeName(EntityType<?> type) {
            return EntityType.getKey(type).toString();
        }
    }

    private static final class MoonriseMethods {
        private static Method getEntityCount;

        private static Method getEntityCountMethod(LevelEntityGetter<Entity> getter) {
            if (getEntityCount == null) {
                try {
                    getEntityCount = getter.getClass().getMethod("getEntityCount");
                } catch (final ReflectiveOperationException e) {
                    throw new RuntimeException("Cannot find Moonrise getEntityCount method", e);
                }
            }
            return getEntityCount;
        }

        private static int getEntityCount(LevelEntityGetter<Entity> getter) {
            try {
                return (int) getEntityCountMethod(getter).invoke(getter);
            } catch (final ReflectiveOperationException e) {
                throw new RuntimeException("Failed to invoke Moonrise getEntityCount method", e);
            }
        }
    }

}
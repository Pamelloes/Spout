/*
 * This file is part of Spout (http://www.spout.org/).
 *
 * Spout is licensed under the SpoutDev License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the SpoutDev License Version 1.
 *
 * Spout is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the SpoutDev License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://www.spout.org/SpoutDevLicenseV1.txt> for the full license,
 * including the MIT license.
 */
package org.spout.engine.world;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import gnu.trove.set.hash.TByteHashSet;

import org.spout.api.Source;
import org.spout.api.Spout;
import org.spout.api.datatable.DatatableMap;
import org.spout.api.entity.BlockController;
import org.spout.api.entity.Entity;
import org.spout.api.entity.PlayerController;
import org.spout.api.generator.Populator;
import org.spout.api.generator.WorldGeneratorUtils;
import org.spout.api.generator.biome.Biome;
import org.spout.api.geo.cuboid.Block;
import org.spout.api.geo.cuboid.Chunk;
import org.spout.api.geo.cuboid.ChunkSnapshot;
import org.spout.api.geo.cuboid.Region;
import org.spout.api.material.BlockMaterial;
import org.spout.api.material.block.BlockFullState;
import org.spout.api.math.MathHelper;
import org.spout.api.math.Vector3;
import org.spout.api.player.Player;
import org.spout.api.protocol.NetworkSynchronizer;
import org.spout.api.scheduler.TickStage;
import org.spout.api.util.HashUtil;
import org.spout.api.util.map.concurrent.AtomicBlockStore;

import org.spout.engine.entity.SpoutEntity;
import org.spout.engine.filesystem.WorldFiles;
import org.spout.engine.util.thread.snapshotable.SnapshotManager;
import org.spout.engine.util.thread.snapshotable.SnapshotableBoolean;
import org.spout.engine.util.thread.snapshotable.SnapshotableHashMap;
import org.spout.engine.util.thread.snapshotable.SnapshotableHashSet;

public class SpoutChunk extends Chunk {
	/**
	 * Time in ms between chunk reaper unload checks
	 */
	private static final long UNLOAD_PERIOD = 30000;
	/**
	 * Storage for block ids, data and auxiliary data. For blocks with data = 0
	 * and auxiliary data = null, the block is stored as a short.
	 */
	protected AtomicBlockStore<DatatableMap> blockStore;
	/**
	 * Indicates that the chunk should be saved if unloaded
	 */
	private final AtomicReference<SaveState> saveState = new AtomicReference<SaveState>(SaveState.NONE);
	/**
	 * The parent region that manages this chunk
	 */
	private final SpoutRegion parentRegion;
	/**
	 * Holds if the chunk is populated
	 */
	private SnapshotableBoolean populated;
	/**
	 * Snapshot Manager
	 */
	private final SnapshotManager snapshotManager = new SnapshotManager();
	/**
	 * A set of all entities who are observing this chunk
	 */
	private final SnapshotableHashMap<Entity, Integer> observers = new SnapshotableHashMap<Entity, Integer>(snapshotManager);
	/**
	 * A set of entities contained in the chunk
	 */
	// Hash set should return "dirty" list
	private final SnapshotableHashSet<Entity> entities = new SnapshotableHashSet<Entity>(snapshotManager);
	/**
	 * Stores a short value of the sky light
	 * <p/>
	 * Note: These do not need to be thread-safe as long as only one thread (the region)
	 * is allowed to modify the values. If setters are provided, this will need to be made safe.
	 */
	protected final byte[] skyLight;
	protected final byte[] blockLight;
	/**
	 * Stores queued column updates for skylight to be processed at the next tick
	 */
	private final TByteHashSet skyLightQueue;
	/**
	 * Stores queued column updates for block light to be processed at the next tick
	 */
	private final TByteHashSet blockLightQueue;
	/**
	 * The mask that should be applied to the x, y and z coords
	 */
	private final int coordMask;
	private final SpoutColumn column;
	private final AtomicBoolean columnRegistered = new AtomicBoolean(true);
	private final AtomicLong lastUnloadCheck = new AtomicLong();
	/**
	 * True if this chunk should be resent due to light calculations
	 */
	private final AtomicBoolean lightDirty = new AtomicBoolean(false);

	public SpoutChunk(SpoutWorld world, SpoutRegion region, float x, float y, float z, short[] initial) {
		this(world, region, x, y, z, false, initial, null, null, null);
	}

	public SpoutChunk(SpoutWorld world, SpoutRegion region, float x, float y, float z, boolean populated, short[] blocks, short[] data, byte[] skyLight, byte[] blockLight) {
		super(world, x * Chunk.CHUNK_SIZE, y * Chunk.CHUNK_SIZE, z * Chunk.CHUNK_SIZE);
		coordMask = Chunk.CHUNK_SIZE - 1;
		parentRegion = region;
		blockStore = new AtomicBlockStore<DatatableMap>(Chunk.CHUNK_SIZE_BITS, 10, blocks, data);
		this.populated = new SnapshotableBoolean(snapshotManager, populated);
		this.skyLight = skyLight != null ? skyLight : new byte[CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE / 2];
		for (int i = 0; i < this.skyLight.length; i++) {
			this.skyLight[i] = 0;
		}
		this.blockLight = blockLight != null ? blockLight : new byte[CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE / 2];

		skyLightQueue = new TByteHashSet();
		blockLightQueue = new TByteHashSet();
		column = world.getColumn(((int) x) << Chunk.CHUNK_SIZE_BITS, ((int) z) << Chunk.CHUNK_SIZE_BITS, true);
		column.registerChunk();
		columnRegistered.set(true);
		lastUnloadCheck.set(world.getAge());
	}

	@Override
	public SpoutWorld getWorld() {
		return (SpoutWorld) super.getWorld();
	}

	@Override
	public boolean setBlockData(int x, int y, int z, short data, Source source) {
		if (source == null) {
			throw new NullPointerException("Source can not be null");
		}
		checkChunkLoaded();

		blockStore.setBlock(x & coordMask, y & coordMask, z & coordMask, getBlockMaterial(x, y, z).getId(), data);

		column.notifyBlockChange(x, (getY() << Chunk.CHUNK_SIZE_BITS) + (y & coordMask), z);

		return true;
	}

	@Override
	public boolean setBlockMaterial(int x, int y, int z, BlockMaterial material, short data, Source source) {
		if (source == null) {
			throw new NullPointerException("Source can not be null");
		}
		checkChunkLoaded();
		BlockMaterial previous = getBlockMaterial(x, y, z);
		blockStore.setBlock(x & coordMask, y & coordMask, z & coordMask, material.getId(), data);

		column.notifyBlockChange(x, (getY() << Chunk.CHUNK_SIZE_BITS) + (y & coordMask), z);

		boolean sky = previous.getOpacity() != material.getOpacity();
		boolean block = previous.getLightLevel() != material.getLightLevel();
		if (sky || block) {
			this.queueLightUpdate(x & coordMask, z & coordMask, sky, block);
		}
		return true;
	}

	@Override
	public BlockMaterial getBlockMaterial(int x, int y, int z) {
		checkChunkLoaded();
		BlockFullState fullState = blockStore.getFullData(x & coordMask, y & coordMask, z & coordMask);
		short id = fullState.getId();
		BlockMaterial mat = BlockMaterial.get(id);
		return mat == null ? BlockMaterial.AIR : mat;
	}

	@Override
	public short getBlockData(int x, int y, int z) {
		checkChunkLoaded();
		return (short) blockStore.getData(x & coordMask, y & coordMask, z & coordMask);
	}

	@Override
	public boolean setBlockLight(int x, int y, int z, byte light, Source source) {
		if (source == null) {
			throw new NullPointerException("Source can not be null");
		}
		checkChunkLoaded();
		int index = getBlockIndex(x, y, z);
		byte old = blockLight[index / 2];
		if ((index & 1) == 0) {
			blockLight[index / 2] = HashUtil.nibbleToByte(light, old);
		} else {
			blockLight[index / 2] = HashUtil.nibbleToByte(old, light);
		}
		return true;
	}

	@Override
	public boolean setBlockSkyLight(int x, int y, int z, byte light, Source source) {
		if (source == null) {
			throw new NullPointerException("Source can not be null");
		}
		checkChunkLoaded();
		int index = getBlockIndex(x, y, z);
		byte old = skyLight[index / 2];
		if ((index & 1) == 0) {
			skyLight[index / 2] = HashUtil.nibbleToByte(light, old);
		} else {
			skyLight[index / 2] = HashUtil.nibbleToByte(old, light);
		}
		return true;
	}

	@Override
	public byte getBlockSkyLight(int x, int y, int z) {
		checkChunkLoaded();
		int index = getBlockIndex(x, y, z);
		byte light = skyLight[index / 2];
		if ((index & 1) == 0) {
			return (byte) ((light >> 4) & 0xF);
		}
		return (byte) (light & 0xF);
	}

	@Override
	public byte getBlockLight(int x, int y, int z) {
		checkChunkLoaded();
		int index = getBlockIndex(x, y, z);
		byte light = blockLight[index / 2];
		if ((index & 1) == 0) {
			return (byte) ((light >> 4) & 0xF);
		}
		return (byte) (light & 0xF);
	}

	@Override
	public void updateBlockPhysics(int x, int y, int z) {
		checkChunkLoaded();
		SpoutRegion region = parentRegion.getWorld().getRegionFromBlock(x, y, z);
		if (region != null) {
			region.queuePhysicsUpdate(x, y, z);
		}
	}

	/**
	 * Gets the sky brightness, may look up from neighbor chunks.
	 * @param x
	 * @param y
	 * @param z
	 * @return brightness
	 */
	private byte getSkyBrightness(int x, int y, int z) {
		if (x >= 0 && y >= 0 && z >= 0 && x < CHUNK_SIZE && y < CHUNK_SIZE && z < CHUNK_SIZE) {
			return getBlockSkyLight(x, y, z);
		}
		SpoutChunk chunk = getWorld().getChunk(getX() + (x >> 4), getY() + (y >> 4), getZ() + (z >> 4), false);
		if (chunk != null) {
			return chunk.getBlockLight(x, y, z);
		}
		return 0;
	}

	private int getBlockIndex(int x, int y, int z) {
		return (y & coordMask) << 8 | (z & coordMask) << 4 | (x & coordMask);
	}

	/**
	 * Recalculates the sky light in the x, z column.
	 * <p/>
	 * May queue more lighting updates in chunks underneath.
	 * @param x coordinate
	 * @param z coordinate
	 */
	private void recalculateSkyLighting(int x, int z) {
		SpoutWorld world = getWorld();
		SpoutChunk aboveChunk = world.getChunk(getX(), getY() + 1, getZ(), false);
		byte prevValue;
		if (aboveChunk != null) {
			//find the sunlight shining through the bottom of the chunk above us
			prevValue = aboveChunk.getBlockSkyLight(x, 0, z);
		} else {
			//assume the sun is shining through ungenerated air
			prevValue = 0xF;
		}
		for (int y = CHUNK_SIZE - 1; y >= 0; --y) {
			//Check adjacent blocks, skylight spreads horizontally
			if (prevValue < 0xF) {
				prevValue = (byte) Math.max(prevValue, getSkyBrightness(x + 1, y, z) - 1);
				prevValue = (byte) Math.max(prevValue, getSkyBrightness(x - 1, y, z) - 1);
				prevValue = (byte) Math.max(prevValue, getSkyBrightness(x, y, z + 1) - 1);
				prevValue = (byte) Math.max(prevValue, getSkyBrightness(x, y, z - 1) - 1);
			}

			//Don't check the opacity unless there is some light
			if (prevValue > 0) {
				BlockMaterial type = getBlockMaterial(x, y, z);
				prevValue -= type.getOpacity();
				if (prevValue < 0) {
					prevValue = 0;
				}
			}

			this.setBlockSkyLight(x, y, z, prevValue, world);
		}

		SpoutChunk belowChunk = world.getChunk(getX(), getY() - 1, getZ(), false);
		if (belowChunk != null) {
			if (belowChunk.getBlockSkyLight(x, CHUNK_SIZE - 1, z) != prevValue) {
				belowChunk.queueLightUpdate(x, z, true, false);
			}
		}
	}

	/**
	 * Recalculates the block light in the x, z column
	 * <p/>
	 * May queue more lighting updates in neighbor chunks.
	 * @param x coordinate
	 * @param z coordinate
	 */
	private void recalculateBlockLighting(int x, int z) {
		//TODO: this is wrong
		//Block light has to spread out from the source
		//And decay 1 for each block away it is from the source
		//But this does not do that
		//for (int y = CHUNK_SIZE - 1; y >= 0; --y) {
		//	BlockMaterial type = getBlockMaterial(x, y, z);
		//	blockLight.set(toIndex(x, y, z), type.getLightLevel());
		//}
	}

	/**
	 * Queues a lighting update for the column. This will be processed in a later tick.
	 * @param x     coordinate of the column
	 * @param z     coordinate of the column
	 * @param sky   whether to update the sky lighting
	 * @param block whether to update the block lighting
	 */
	private void queueLightUpdate(int x, int z, boolean sky, boolean block) {
		if (!sky && !block) {
			throw new IllegalStateException("Invalid paramaters");
		}
		if (sky) {
			synchronized (skyLightQueue) {
				skyLightQueue.add(HashUtil.nibbleToByte(x & 0xF, z & 0xF));
			}
		}
		if (block) {
			synchronized (blockLightQueue) {
				blockLightQueue.add(HashUtil.nibbleToByte(x & 0xF, z & 0xF));
			}
		}
		parentRegion.queueLighting(this);
	}

	/**
	 * Processes the queued lighting updates for this chunk.
	 * <p/>
	 * This should only be called from the SpoutRegion that manages this chunk, during the first tick stage.
	 */
	protected void processQueuedLighting() {
		byte[] queue;
		setLightDirty(skyLightQueue.size() + blockLightQueue.size() > (CHUNK_SIZE * CHUNK_SIZE / 4));
		synchronized (skyLightQueue) {
			queue = skyLightQueue.toArray();
			skyLightQueue.clear();
		}
		for (byte b : queue) {
			int x = HashUtil.byteToNibble1(b);
			int z = HashUtil.byteToNibble2(b);
			recalculateSkyLighting(x, z);
		}
		synchronized (blockLightQueue) {
			queue = blockLightQueue.toArray();
			blockLightQueue.clear();
		}
		for (byte b : queue) {
			int x = HashUtil.byteToNibble1(b);
			int z = HashUtil.byteToNibble2(b);
			recalculateBlockLighting(x, z);
		}
	}

	@Override
	public void unload(boolean save) {
		unloadNoMark(save);
		markForSaveUnload();
	}

	public void unloadNoMark(boolean save) {
		boolean success = false;
		while (!success) {
			SaveState state = saveState.get();
			SaveState nextState;
			switch (state) {
				case UNLOAD_SAVE:
					nextState = SaveState.UNLOAD_SAVE;
					break;
				case UNLOAD:
					nextState = save ? SaveState.UNLOAD_SAVE : SaveState.UNLOAD;
					break;
				case SAVE:
					nextState = SaveState.UNLOAD_SAVE;
					break;
				case NONE:
					nextState = save ? SaveState.UNLOAD_SAVE : SaveState.UNLOAD;
					break;
				case UNLOADED:
					nextState = SaveState.UNLOADED;
					break;
				default:
					throw new IllegalStateException("Unknown save state: " + state);
			}
			success = saveState.compareAndSet(state, nextState);
		}
	}

	@Override
	public void save() {
		checkChunkLoaded();
		saveNoMark();
		markForSaveUnload();
	}

	private void markForSaveUnload() {
		(parentRegion).markForSaveUnload(this);
	}

	public void saveNoMark() {
		boolean success = false;
		while (!success) {
			SaveState state = saveState.get();
			SaveState nextState;
			switch (state) {
				case UNLOAD_SAVE:
					nextState = SaveState.UNLOAD_SAVE;
					break;
				case UNLOAD:
					nextState = SaveState.UNLOAD_SAVE;
					break;
				case SAVE:
					nextState = SaveState.SAVE;
					break;
				case NONE:
					nextState = SaveState.SAVE;
					break;
				case UNLOADED:
					nextState = SaveState.UNLOADED;
					break;
				default:
					throw new IllegalStateException("Unknown save state: " + state);
			}
			saveState.compareAndSet(state, nextState);
		}
	}

	public SaveState getAndResetSaveState() {
		boolean success = false;
		SaveState old = null;
		while (!success) {
			old = saveState.get();
			if (old != SaveState.UNLOADED) {
				success = saveState.compareAndSet(old, SaveState.NONE);
			} else {
				success = saveState.compareAndSet(old, SaveState.UNLOADED);
			}
		}
		return old;
	}

	public void copySnapshotRun() throws InterruptedException {
		snapshotManager.copyAllSnapshots();
	}

	// Saves the chunk data - this occurs directly after a snapshot update
	public void syncSave() {
		WorldFiles.saveChunk(this, blockStore.getBlockIdArray(), blockStore.getDataArray(), skyLight, blockLight, this.parentRegion.getChunkOutputStream(this));
	}

	@Override
	public ChunkSnapshot getSnapshot() {
		return getSnapshot(true);
	}

	@Override
	public ChunkSnapshot getSnapshot(boolean entities) {
		checkChunkLoaded();
		byte[] blockLightCopy = new byte[blockLight.length];
		System.arraycopy(blockLight, 0, blockLightCopy, 0, blockLight.length);
		byte[] skyLightCopy = new byte[skyLight.length];
		System.arraycopy(skyLight, 0, skyLightCopy, 0, skyLight.length);
		return new SpoutChunkSnapshot(this, blockStore.getBlockIdArray(), blockStore.getDataArray(), blockLightCopy, skyLightCopy, entities);
	}

	@Override
	public boolean refreshObserver(Entity entity) {
		if (!entity.isObserver()) {
			throw new IllegalArgumentException("Cannot add an entity that isn't marked as an observer!");
		}
		checkChunkLoaded();
		TickStage.checkStage(TickStage.FINALIZE);
		int distance = (int) ((SpoutEntity) entity).getChunkLive().getBase().getDistance(getBase());
		Integer oldDistance = observers.put(entity, distance);
		if (oldDistance == null) {
			parentRegion.unloadQueue.remove(this);
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean removeObserver(Entity entity) {
		checkChunkLoaded();
		TickStage.checkStage(TickStage.FINALIZE);
		Integer oldDistance = observers.remove(entity);
		if (oldDistance != null) {
			if (observers.isEmptyLive()) {
				parentRegion.unloadQueue.add(this);
			}
			return true;
		} else {
			return false;
		}
	}

	public Set<Entity> getObserversLive() {
		return observers.getLive().keySet();
	}

	public Set<Entity> getObservers() {
		return observers.get().keySet();
	}

	public boolean compressIfRequired() {
		if (blockStore.needsCompression()) {
			blockStore.compress();
			return true;
		} else {
			return false;
		}
	}

	public void setLightDirty(boolean dirty) {
		lightDirty.set(dirty);
	}

	public boolean isLightDirty() {
		return lightDirty.get();
	}

	public boolean isDirty() {
		return blockStore.isDirty();
	}

	public boolean isDirtyOverflow() {
		return blockStore.isDirtyOverflow();
	}

	public Block getDirtyBlock(int i) {
		return blockStore.getDirtyBlock(i, this.getWorld());
	}

	public void resetDirtyArrays() {
		blockStore.resetDirtyArrays();
	}

	@Override
	public boolean isLoaded() {
		return saveState.get() != SaveState.UNLOADED;
	}

	public void setUnloaded() {
		TickStage.checkStage(TickStage.SNAPSHOT);
		saveState.set(SaveState.UNLOADED);
		blockStore = null;
		deregisterFromColumn();
	}

	private void checkChunkLoaded() {
		if (saveState.get() == SaveState.UNLOADED) {
			throw new ChunkAccessException("Chunk has been unloaded");
		}
	}

	@Override
	public Biome getBiomeType(int x, int y, int z) {
		return getWorld().getBiomeType((getX() << CHUNK_SIZE_BITS) + x,
				(getY() << CHUNK_SIZE_BITS) + y, (getZ() << CHUNK_SIZE_BITS) + z);
	}

	public static enum SaveState {

		UNLOAD_SAVE,
		UNLOAD,
		SAVE,
		NONE,
		UNLOADED;

		public boolean isSave() {
			return this == SAVE || this == UNLOAD_SAVE;
		}

		public boolean isUnload() {
			return this == UNLOAD_SAVE || this == UNLOAD;
		}
	}

	@Override
	public Region getRegion() {
		return parentRegion;
	}

	@Override
	public void populate() {
		populate(false);
	}

	@Override
	public void populate(boolean force) {
		if (isPopulated() && !force) {
			return;
		}

		final Random random = new Random(WorldGeneratorUtils.getSeed(getWorld(), getX(), getY(), getZ(), 42));

		for (Populator populator : getWorld().getGenerator().getPopulators()) {
			try {
				populator.populate(this, random);
			} catch (Exception e) {
				Spout.getEngine().getLogger().log(Level.SEVERE, "Could not populate Chunk with " + populator.toString());
				e.printStackTrace();
			}
		}

		//Recalculate lighting
		for (int dx = CHUNK_SIZE - 1; dx >= 0; --dx) {
			for (int dz = CHUNK_SIZE - 1; dz >= 0; --dz) {
				queueLightUpdate(dx, dz, true, true);
			}
		}

		populated.set(true);
		parentRegion.onChunkPopulated(this);
	}

	@Override
	public boolean isPopulated() {
		return populated.get();
	}

	public boolean addEntity(SpoutEntity entity) {
		checkChunkLoaded();
		TickStage.checkStage(TickStage.FINALIZE);
		return entities.add(entity);
	}

	public boolean removeEntity(SpoutEntity entity) {
		checkChunkLoaded();
		TickStage.checkStage(TickStage.FINALIZE);
		return entities.remove(entity);
	}

	@Override
	public Set<Entity> getEntities() {
		return entities.get();
	}

	@Override
	public Set<Entity> getLiveEntities() {
		return entities.getLive();
	}

	// Handles network updates for all entities that were
	// - in the chunk at the last snapshot
	// - were not in a chunk at the last snapshot and are now in this chunk
	public void preSnapshot() {
		Map<Entity, Integer> observerSnapshot = observers.get();
		Map<Entity, Integer> observerLive = observers.getLive();

		//If we are observed and not populated, queue population
		if (!isPopulated() && observers.getLive().size() > 0) {
			parentRegion.queueChunkForPopulation(this);
		}

		Set<Entity> entitiesSnapshot = entities.get();
		entities.getLive();

		// Changed means entered/left the chunk
		List<Entity> changedEntities = entities.getDirtyList();
		List<Entity> changedObservers = observers.getDirtyList();

		if (entitiesSnapshot.size() > 0) {
			for (Entity p : changedObservers) {
				Integer playerDistanceOld = observerSnapshot.get(p);
				if (playerDistanceOld == null) {
					playerDistanceOld = Integer.MAX_VALUE;
				}
				Integer playerDistanceNew = observerLive.get(p);
				if (playerDistanceNew == null) {
					playerDistanceNew = Integer.MAX_VALUE;
				}
				//Player Network sync
				if (p.getController() instanceof PlayerController) {
					Player player = ((PlayerController) p.getController()).getPlayer();

					NetworkSynchronizer n = player.getNetworkSynchronizer();
					for (Entity e : entitiesSnapshot) {
						if (player.getEntity().equals(e)) {
							continue;
						}
						int entityViewDistanceOld = ((SpoutEntity) e).getPrevViewDistance();
						int entityViewDistanceNew = e.getViewDistance();

						if (playerDistanceOld <= entityViewDistanceOld && playerDistanceNew > entityViewDistanceNew) {
							n.destroyEntity(e);
						} else if (playerDistanceNew <= entityViewDistanceNew && playerDistanceOld > entityViewDistanceOld) {
							n.spawnEntity(e);
						}
					}
				}
			}
		}

		for (Entity e : changedEntities) {
			SpoutChunk oldChunk = (SpoutChunk) e.getChunk();
			if (((SpoutEntity) e).justSpawned()) {
				oldChunk = null;
			}
			SpoutChunk newChunk = (SpoutChunk) ((SpoutEntity) e).getChunkLive();
			if (!(oldChunk != null && oldChunk.equals(this)) && !((SpoutEntity) e).justSpawned()) {
				continue;
			}
			for (Entity p : observerLive.keySet()) {
				if (p == null || p.equals(e)) {
					continue;
				}
				if (p.getController() instanceof PlayerController) {
					Integer playerDistanceOld;
					if (oldChunk == null) {
						playerDistanceOld = Integer.MAX_VALUE;
					} else {
						playerDistanceOld = oldChunk.observers.getLive().get(p);
						if (playerDistanceOld == null) {
							playerDistanceOld = Integer.MAX_VALUE;
						}
					}
					Integer playerDistanceNew;
					if (newChunk == null) {
						playerDistanceNew = Integer.MAX_VALUE;
					} else {
						playerDistanceNew = newChunk.observers.getLive().get(p);
						if (playerDistanceNew == null) {
							playerDistanceNew = Integer.MAX_VALUE;
						}
					}
					int entityViewDistanceOld = ((SpoutEntity) e).getPrevViewDistance();
					int entityViewDistanceNew = e.getViewDistance();

					Player player = ((PlayerController) p.getController()).getPlayer();
					NetworkSynchronizer n = player.getNetworkSynchronizer();

					if (playerDistanceOld <= entityViewDistanceOld && playerDistanceNew > entityViewDistanceNew) {
						n.destroyEntity(e);
					} else if (playerDistanceNew <= entityViewDistanceNew && playerDistanceOld > entityViewDistanceOld) {
						n.spawnEntity(e);
					}
				}
			}
		}

		// Update all entities that are in the chunk
		// TODO - should have sorting based on view distance
		for (Map.Entry<Entity, Integer> entry : observerLive.entrySet()) {
			Entity p = entry.getKey();
			if (p.getController() instanceof PlayerController) {
				Player player = ((PlayerController) p.getController()).getPlayer();
				NetworkSynchronizer n = player.getNetworkSynchronizer();
				if (n != null) {
					int playerDistance = entry.getValue();
					Entity playerEntity = p;
					for (Entity e : entitiesSnapshot) {
						if (playerEntity != e) {
							if (playerDistance <= e.getViewDistance()) {
								if (((SpoutEntity) e).getPrevController() != e.getController()) {
									n.destroyEntity(e);
									n.spawnEntity(e);
								}
								n.syncEntity(e);
							}
						}
					}
					for (Entity e : changedEntities) {
						if (entitiesSnapshot.contains(e)) {
							continue;
						} else if (((SpoutEntity) e).justSpawned()) {
							if (playerEntity != e) {
								if (playerDistance <= e.getViewDistance()) {
									if (((SpoutEntity) e).getPrevController() != e.getController()) {
										n.destroyEntity(e);
										n.spawnEntity(e);
									}
									n.syncEntity(e);
								}
							}
						}
					}
				}
			}
		}
	}

	public void deregisterFromColumn() {
		deregisterFromColumn(true);
	}

	public void deregisterFromColumn(boolean save) {
		if (columnRegistered.compareAndSet(true, false)) {
			column.deregisterChunk(save);
		} else {
			throw new IllegalStateException("Chunk at " + getX() + ", " + getZ() + " deregistered from column more than once");
		}
	}

	public boolean isReapable() {
		return isReapable(getWorld().getAge());
	}

	public boolean isReapable(long worldAge) {
		if (lastUnloadCheck.get() + UNLOAD_PERIOD < worldAge) {
			lastUnloadCheck.set(worldAge);
			return this.observers.getLive().size() <= 0 && this.observers.get().size() <= 0;
		} else {
			return false;
		}
	}

	public void notifyColumn() {
		for (int x = 0; x < Chunk.CHUNK_SIZE; x++) {
			for (int z = 0; z < Chunk.CHUNK_SIZE; z++) {
				notifyColumn(x, z);
			}
		}
	}

	private void notifyColumn(int x, int z) {
		if (columnRegistered.get()) {
			column.notifyChunkAdded(this, x, z);
		}
	}

	@Override
	public String toString() {
		return "SpoutChunk{ (" + getX() + ", " + getY() + ", " + getZ() + ") }";
	}

	public static class ChunkAccessException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public ChunkAccessException(String message) {
			super(message);
		}
	}

	@Override
	public void setBlockController(int x, int y, int z, BlockController controller) {
		getRegion().setBlockController(x, y, z, controller);
	}

	@Override
	public BlockController getBlockController(int x, int y, int z) {
		return getRegion().getBlockController(x, y, z);
	}

	@Override
	public Block getBlock(int x, int y, int z) {
		return this.getBlock(x, y, z, this.getWorld());
	}

	@Override
	public Block getBlock(int x, int y, int z, Source source) {
		return new SpoutBlock(this.getWorld(), x, y, z, this, source);
	}

	@Override
	public Block getBlock(float x, float y, float z) {
		return this.getBlock(x, y, z, this.getWorld());
	}

	@Override
	public Block getBlock(float x, float y, float z, Source source) {
		return getBlock(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z), source);
	}

	@Override
	public Block getBlock(Vector3 position) {
		return getBlock(position, this.getWorld());
	}

	@Override
	public Block getBlock(Vector3 position, Source source) {
		return getBlock(position.getX(), position.getY(), position.getZ(), source);
	}

	@Override
	public boolean compareAndSetData(int x, int y, int z, BlockFullState expect, short data) {
		return this.blockStore.compareAndSetBlock(x & coordMask, y & coordMask, z & coordMask, expect.getId(), expect.getData(), expect.getId(), data);
	}
}

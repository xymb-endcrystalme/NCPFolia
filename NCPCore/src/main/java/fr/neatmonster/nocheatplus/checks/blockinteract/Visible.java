/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.blockinteract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.util.Vector;

import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.net.FlyingQueueHandle;
import fr.neatmonster.nocheatplus.checks.net.FlyingQueueLookBlockChecker;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.collision.Axis;
import fr.neatmonster.nocheatplus.utilities.collision.InteractAxisTracing;
import fr.neatmonster.nocheatplus.utilities.ds.map.CoordHash;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.WrapBlockCache;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;

public class Visible extends Check {

    private final class RayChecker extends FlyingQueueLookBlockChecker {

        private BlockFace face;
        private List<String> tags;
        private boolean debug;
        private Player player;

        @Override
        protected boolean check(final double x, final double y, final double z, 
                final float yaw, final float pitch, 
                final int blockX, final int blockY, final int blockZ) {
            // Run ray-tracing again with updated pitch and yaw.
            useLoc.setPitch(pitch);
            useLoc.setYaw(yaw);
            final Vector direction = useLoc.getDirection(); // TODO: Better.
            tags.clear();
            if (checkRayTracing(x, y, z, direction.getX(), direction.getY(), direction.getZ(), blockX, blockY, blockZ, face, tags, debug)) {
                // Collision still.
                if (debug) {
                    debug(player, "pitch=" + pitch + ",yaw=" + yaw + " tags=" + StringUtil.join(tags, "+"));
                }
                return false;
            }
            return true;
        }

        public boolean checkFlyingQueue(double x, double y, double z, float oldYaw, float oldPitch, int blockX,
                int blockY, int blockZ, FlyingQueueHandle flyingHandle, 
                BlockFace face, List<String> tags, boolean debug, Player player) {
            this.face = face;
            this.tags = tags;
            this.debug = debug;
            this.player = player;
            return super.checkFlyingQueue(x, y, z, oldYaw, oldPitch, blockX, blockY, blockZ, flyingHandle);
        }

        @Override
        public boolean checkFlyingQueue(double x, double y, double z, float oldYaw, float oldPitch, int blockX,
                int blockY, int blockZ, FlyingQueueHandle flyingHandle) {
            throw new UnsupportedOperationException("Use the other method.");
        }

        public void cleanup () {
            this.player = null;
            this.face = null;
            this.debug = false;
            this.tags = null;
        }
    }

    private final WrapBlockCache wrapBlockCache;

    private final InteractAxisTracing rayTracing = new InteractAxisTracing();

    private final RayChecker checker = new RayChecker();

    private final List<String> tags = new ArrayList<String>();

    /** For temporary use, no nested use, setWorld(null) after use, etc. */
    private final Location useLoc = new Location(null, 0, 0, 0);

    public Visible() {
        super(CheckType.BLOCKINTERACT_VISIBLE);
        wrapBlockCache = new WrapBlockCache();
        rayTracing.setMaxSteps(60); // TODO: Configurable ?
    }

    public boolean check(final Player player, final Location loc, final double eyeHeight, final Block block, 
            final BlockFace face, final Action action, final FlyingQueueHandle flyingHandle, 
            final BlockInteractData data, final BlockInteractConfig cc, final IPlayerData pData) {

        // TODO: This check might make parts of interact/blockbreak/... + direction (+?) obsolete.
        // TODO: Might confine what to check for (left/right-click, target blocks depending on item in hand, container blocks).
        boolean collides;
        final int blockX = block.getX();
        final int blockY = block.getY();
        final int blockZ = block.getZ();
        final double eyeX = loc.getX();
        final double eyeY = loc.getY() + eyeHeight;
        final double eyeZ = loc.getZ();
        final boolean debug = pData.isDebugActive(type);

        tags.clear();
        if (TrigUtil.isSameBlock(blockX, blockY, blockZ, eyeX, eyeY, eyeZ)) {
            // Player is interacting with the block their head is in.
            // TODO: Should the reachable-face-check be done here too (if it is added at all)?
            collides = false;
        }
        else {
            // Ray-tracing.
            // Initialize.
            final BlockCache blockCache = this.wrapBlockCache.getBlockCache();
            blockCache.setAccess(loc.getWorld());
            rayTracing.setBlockCache(blockCache);
            //collides = !checker.checkFlyingQueue(eyeX, eyeY, eyeZ, loc.getYaw(), loc.getPitch(), 
            //        blockX, blockY, blockZ, flyingHandle, face, tags, debug, player);
            rayTracing.set(blockX, blockY, blockZ, eyeX, eyeY, eyeZ);
            rayTracing.loop();
            if (rayTracing.collides()) {
                collides = true;
                BlockCoord bc = new BlockCoord(blockX, blockY, blockZ);
                Vector direction = new Vector(eyeX - blockX, eyeY - blockY, eyeZ - blockZ).normalize();
                boolean canContinue;
                Set<BlockCoord> visited = new HashSet<BlockCoord>();
                do {
                    canContinue = false;
                for (BlockCoord neighbor : getNeighborsInDirection(bc, direction, eyeX, eyeY, eyeZ)) {
                    if (canPassThrough(blockCache, bc, neighbor.getX(), neighbor.getY(), neighbor.getZ(), direction, eyeX, eyeY, eyeZ, eyeHeight) && correctDir(neighbor.getY(), blockY, Location.locToBlock(eyeY)) && !visited.contains(neighbor)) {
                        visited.add(neighbor);
                        rayTracing.set(neighbor.getX(), neighbor.getY(), neighbor.getZ(), eyeX, eyeY, eyeZ);
                        rayTracing.loop();
                        canContinue = true;
                        collides = rayTracing.collides();
                        //if (!collides) break;
                        //bc = new BlockCoord(rayTracing.getBlockX(), rayTracing.getBlockY(), rayTracing.getBlockZ());
                        //direction = new Vector(eyeX - rayTracing.getBlockX(), eyeY - rayTracing.getBlockY(), eyeZ - rayTracing.getBlockZ()).normalize();
                        bc = new BlockCoord(neighbor.getX(), neighbor.getY(), neighbor.getZ());
                        direction = new Vector(eyeX - neighbor.getX(), eyeY - neighbor.getY(), eyeZ - neighbor.getZ()).normalize();
                        break;
                    }
                }
                } while (collides && canContinue);
                if (collides) tags.add("raytracing");
            }
            else if (rayTracing.getStepsDone() > rayTracing.getMaxSteps()) {
                tags.add("raytracing_maxsteps");
                collides = true;
            }
            else {
                collides = false;
            }
            
            checker.cleanup();
            useLoc.setWorld(null);
            //Cleanup.
            rayTracing.cleanup();
            //rayTracing.setIgnoreInitiallyColliding(false);
            blockCache.cleanup();
        }

        // Actions ?
        boolean cancel = false;
        if (collides) {
            data.visibleVL += 1;
            final ViolationData vd = new ViolationData(this, player, data.visibleVL, 1, cc.visibleActions);
            //            if (data.debug || vd.needsParameters()) {
            //                // TODO: Consider adding the start/end/block-type information if debug is set.
            //                vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
            //            }
            if (executeActions(vd).willCancel()) {
                cancel = true;
            }
        }
        else {
            data.visibleVL *= 0.99;
            data.addPassedCheck(this.type);
            if (debug) {
                debug(player, "pitch=" + loc.getPitch() + ",yaw=" + loc.getYaw() + " tags=" + StringUtil.join(tags, "+"));
            }
        }
        return cancel;
    }
    
    private boolean correctDir(int neighbor, int block, int eyeBlock) {
        int d = eyeBlock - block;
        if (d > 0) {
            if (neighbor > eyeBlock) return false;
        } else if (d < 0) {
            if (neighbor < eyeBlock) return false;
        } else {
            if (neighbor < eyeBlock || neighbor > eyeBlock) return false;
        }
        return true;
    }

    private boolean canPassThrough(BlockCache blockCache, BlockCoord lastBlock, int x, int y, int z, Vector direction, double eyeX, double eyeY, double eyeZ, double eyeHeight) {
        double[] nextBounds = blockCache.getBounds(x, y, z);
        final Material mat = blockCache.getType(x, y, z);
        final long flags = BlockFlags.getBlockFlags(mat);
        //double[] lastBounds = blockCache.getBounds(lastBlock.getX(), lastBlock.getY(), lastBlock.getZ());
        if (nextBounds == null || canPassThroughWorkAround(blockCache, x, y, z, direction, eyeX, eyeY, eyeZ, eyeHeight)) return true;
        //if (lastBounds == null) return true;
        int dy = y - lastBlock.getY();
        int dx = x - lastBlock.getX();
        int dz = z - lastBlock.getZ();
        double stepX = dx * 0.99;
        double stepY = dy * 0.99;
        double stepZ = dz * 0.99;
        //rayTracing.setAxisOrder(Axis.AXIS_ORDER_XZY);
        rayTracing.set(lastBlock.getX(), lastBlock.getY(), lastBlock.getZ(), x + stepX, y + stepY, z + stepZ);
        rayTracing.setIgnoreInitiallyColliding(true);
        rayTracing.loop();
        rayTracing.setIgnoreInitiallyColliding(false);
        if (!rayTracing.collides()) return true;
        // Too headache to think out a perfect algorithm
        boolean wallConnector = (flags & (BlockFlags.F_THICK_FENCE | BlockFlags.F_THIN_FENCE)) != 0;
        boolean door = BlockProperties.isDoor(mat);
        if ((flags & BlockFlags.F_STAIRS) != 0) {
            if (dy == 0) {
                int eyeBlockY = Location.locToBlock(eyeY);
                if (eyeBlockY > y && nextBounds[4] == 1.0) return false;
                if (eyeBlockY < y && nextBounds[1] == 0.0) return false;
            }
            if (dx != 0) {
                // first bound is always a slab
                for (int i = 2; i <= (int)nextBounds.length / 6; i++) {
                    if (nextBounds[i*6-4] == 0.0 && nextBounds[i*6-1] == 1.0 && (dx < 0 ? nextBounds[i*6-3] == 1.0 : nextBounds[i*6-6] == 0.0)) return false;
                }
            }
            if (dz != 0) {
                // first bound is always a slab
                for (int i = 2; i <= (int)nextBounds.length / 6; i++) {
                    if (nextBounds[i*6-6] == 0.0 && nextBounds[i*6-3] == 1.0 && (dz < 0 ? nextBounds[i*6-1] == 1.0 : nextBounds[i*6-4] == 0.0)) return false;
                }
            }
        }
        if (dy != 0) {
            if (nextBounds[0] == 0.0 && nextBounds[3] == 1.0 && nextBounds[2] == 0.0 && nextBounds[5] == 1.0) return wallConnector || door ? rayTracing.getCollidingAxis() != Axis.Y_AXIS : dy > 0 ? nextBounds[1] != 0.0 : nextBounds[4] != 1.0;
            return true;
        }
        if (dx != 0) {
            if (nextBounds[1] == 0.0 && nextBounds[4] == 1.0 && nextBounds[2] == 0.0 && nextBounds[5] == 1.0) return wallConnector || door ? rayTracing.getCollidingAxis() != Axis.X_AXIS : dx > 0 ? nextBounds[0] != 0.0 : nextBounds[3] != 1.0;
            return true;
        }
        if (dz != 0) {
            if (nextBounds[0] == 0.0 && nextBounds[3] == 1.0 && nextBounds[1] == 0.0 && nextBounds[4] == 1.0) return wallConnector || door ? rayTracing.getCollidingAxis() != Axis.Z_AXIS : dz > 0 ? nextBounds[2] != 0.0 : nextBounds[5] != 1.0;
            return true;
        }
        return false;
    }

    private boolean canPassThroughWorkAround(BlockCache blockCache, int blockX, int blockY, int blockZ, Vector direction, double eyeX, double eyeY, double eyeZ, double eyeHeight) {
        final Material mat = blockCache.getType(blockX, blockY, blockZ);
        final long flags = BlockFlags.getBlockFlags(mat);
        if ((flags & BlockFlags.F_SOLID) == 0) {
            // Ignore non solid blocks anyway.
            return true;
        }
        if ((flags & (BlockFlags.F_LIQUID | BlockFlags.F_IGN_PASSABLE)) != 0) {
            return true;
        }

        if ((flags & (BlockFlags.F_THICK_FENCE | BlockFlags.F_THIN_FENCE)) != 0) {
            int entityBlockY = Location.locToBlock(eyeY - eyeHeight);
            return direction.getY() > 0.76 && entityBlockY > blockY || direction.getY() < -0.76 && entityBlockY < blockY;
        }
        return false;
    }

    private List<BlockCoord> getNeighborsInDirection(BlockCoord currentBlock, Vector direction, double eyeX, double eyeY, double eyeZ) {
        List<BlockCoord> neighbors = new ArrayList<>();
        int stepY = direction.getY() > 0 ? 1 : (direction.getY() < 0 ? -1 : 0);
        int stepX = direction.getX() > 0 ? 1 : (direction.getX() < 0 ? -1 : 0);
        int stepZ = direction.getZ() > 0 ? 1 : (direction.getZ() < 0 ? -1 : 0);
        
        final double dYM = TrigUtil.manhattan(currentBlock.getX(), currentBlock.getY() + stepY, currentBlock.getZ(), eyeX, eyeY, eyeZ);
        final double dZM = TrigUtil.manhattan(currentBlock.getX(), currentBlock.getY(), currentBlock.getZ() + stepZ, eyeX, eyeY, eyeZ);
        final double dXM = TrigUtil.manhattan(currentBlock.getX() + stepX, currentBlock.getY(), currentBlock.getZ(), eyeX, eyeY, eyeZ);
        
        if (dYM <= dXM && dYM <= dZM) {
            neighbors.add(new BlockCoord(currentBlock.getX(), currentBlock.getY() + stepY, currentBlock.getZ()));
            neighbors.add(new BlockCoord(currentBlock.getX() + stepX, currentBlock.getY(), currentBlock.getZ()));
            neighbors.add(new BlockCoord(currentBlock.getX(), currentBlock.getY(), currentBlock.getZ() + stepZ));
            return neighbors;
        }

        if (dXM < dZM) {
            neighbors.add(new BlockCoord(currentBlock.getX() + stepX, currentBlock.getY(), currentBlock.getZ()));
            neighbors.add(new BlockCoord(currentBlock.getX(), currentBlock.getY(), currentBlock.getZ() + stepZ));
            neighbors.add(new BlockCoord(currentBlock.getX(), currentBlock.getY() + stepY, currentBlock.getZ()));
        } else {
            neighbors.add(new BlockCoord(currentBlock.getX(), currentBlock.getY(), currentBlock.getZ() + stepZ));
            neighbors.add(new BlockCoord(currentBlock.getX() + stepX, currentBlock.getY(), currentBlock.getZ()));
            neighbors.add(new BlockCoord(currentBlock.getX(), currentBlock.getY() + stepY, currentBlock.getZ()));
        }
        return neighbors;
    }

    private boolean checkRayTracing(final double eyeX, final double eyeY, final double eyeZ, final double dirX, final double dirY, final double dirZ, final int blockX, final int blockY, 
                                    final int blockZ, final BlockFace face, final List<String> tags, final boolean debug) {
        // Block of eyes.
        final int eyeBlockX = Location.locToBlock(eyeX);
        final int eyeBlockY = Location.locToBlock(eyeY);
        final int eyeBlockZ = Location.locToBlock(eyeZ);
        // Distance in blocks from eyes to clicked block.
        final int bdX = blockX - eyeBlockX;
        final int bdY = blockY - eyeBlockY;
        final int bdZ = blockZ - eyeBlockZ;

        // Coarse orientation check.
        // TODO: Might skip (axis transitions...)?
        //        if (bdX != 0 && dirX * bdX <= 0.0 || bdY != 0 && dirY * bdY <= 0.0 || bdZ != 0 && dirZ * bdZ <= 0.0) {
        //            // TODO: There seem to be false positives, do add debug logging with/before violation handling.
        //            tags.add("coarse_orient");
        //            return true;
        //        }

        // TODO: If medium strict, check if the given BlockFace seems acceptable.

        // Time windows for coordinates passing through the target block.
        final double tMinX = getMinTime(eyeX, eyeBlockX, dirX, bdX);
        final double tMinY = getMinTime(eyeY, eyeBlockY, dirY, bdY);
        final double tMinZ = getMinTime(eyeZ, eyeBlockZ, dirZ, bdZ);
        final double tMaxX = getMaxTime(eyeX, eyeBlockX, dirX, tMinX);
        final double tMaxY = getMaxTime(eyeY, eyeBlockY, dirY, tMinY);
        final double tMaxZ = getMaxTime(eyeZ, eyeBlockZ, dirZ, tMinZ);

        // Point of time of collision.
        final double tCollide = Math.max(0.0, Math.max(tMinX, Math.max(tMinY, tMinZ)));
        // Collision location (corrected to be on the clicked block).
        double collideX = toBlock(eyeX + dirX * tCollide, blockX);
        double collideY = toBlock(eyeY + dirY * tCollide, blockY);
        double collideZ = toBlock(eyeZ + dirZ * tCollide, blockZ);

        if (TrigUtil.distanceSquared(0.5 + blockX, 0.5 + blockY, 0.5 + blockZ, collideX, collideY, collideZ) > 0.75) {
            tags.add("early_block_miss");
        }

        // Check if the the block is hit by the direction at all (timing interval).
        if (tMinX > tMaxY && tMinX > tMaxZ || 
            tMinY > tMaxX && tMinY > tMaxZ || 
            tMinZ > tMaxX && tMaxZ > tMaxY) {
            // TODO: Option to tolerate a minimal difference in t and use a corrected position then.
            tags.add("time_miss");
            //            Bukkit.getServer().broadcastMessage("visible: " + tMinX + "," + tMaxX + " | " + tMinY + "," + tMaxY + " | " + tMinZ + "," + tMaxZ);
            // return true; // TODO: Strict or not (direction check ...).
            // Attempt to correct somehow.
            collideX = postCorrect(blockX, bdX, collideX);
            collideY = postCorrect(blockY, bdY, collideY);
            collideZ = postCorrect(blockZ, bdZ, collideZ);
        }

        // Correct the last-on-block to be on the edge (could be two).
        // TODO: Correct towards minimum of all time values, then towards block, rather.
        if (tMinX == tCollide) {
            collideX = Math.round(collideX);
        }
        if (tMinY == tCollide) {
            collideY = Math.round(collideY);
        }
        if (tMinZ == tCollide) {
            collideZ = Math.round(collideZ);
        }

        if (TrigUtil.distanceSquared(0.5 + blockX, 0.5 + blockY, 0.5 + blockZ, collideX, collideY, collideZ) > 0.75) {
            tags.add("late_block_miss");
        }

        /*
         * TODO: Still false positives on transitions between blocks. The
         * location does not reflect the latest flying packet(s).
         */

        // Perform ray-tracing.
        //rayTracing.set(eyeX, eyeY, eyeZ, collideX, collideY, collideZ, blockX, blockY, blockZ);
        rayTracing.loop();

        final boolean collides;
        if (rayTracing.collides()) {
            tags.add("raytracing");
            collides = true;
        }
        else if (rayTracing.getStepsDone() > rayTracing.getMaxSteps()) {
            tags.add("raytracing_maxsteps");
            collides = true;
        }
        else {
            collides = false;
        }
        if (collides && debug) {
            /*
             * Consider using a configuration setting for extended debugging
             * (e.g. make DEBUG_LEVEL accessible by API and config).
             */
            // TEST: Log as a false positive (!).
            // debug(player, "test case:\n" + rayTracing.getTestCase(1.05, false));
        }
        return collides;
    }

    /**
     * Correct onto the block (from off-block), against the direction.
     * 
     * @param blockC
     * @param bdC
     * @param collideC
     * @return
     */
    private double postCorrect(int blockC, int bdC, double collideC) {
        int ref = bdC < 0 ? blockC + 1 : blockC;
        if (Location.locToBlock(collideC) == ref) {
            return collideC;
        }
        return ref;
    }

    /**
     * Time until on the block (time = steps of dir).
     * @param eye
     * @param eyeBlock
     * @param dir
     * @param blockDiff
     * @return
     */
    private double getMinTime(final double eye, final int eyeBlock, final double dir, final int blockDiff) {
        if (blockDiff == 0) {
            // Already on the block.
            return 0.0;
        }
        // Calculate the time needed to be on the (close edge of the block coordinate).
        final double eyeOffset = Math.abs(eye - eyeBlock); // (abs not needed)
        return ((dir < 0.0 ? eyeOffset : 1.0 - eyeOffset) + (double) (Math.abs(blockDiff) - 1)) / Math.abs(dir);
    }

    /**
     * Time when not on the block anymore (after having hit it, time = steps of dir).
     * @param eye
     * @param eyeBlock
     * @param dir
     * @param blockDiff
     * @param tMin Result of getMinTime for this coordinate.
     * @return
     */
    private double getMaxTime(final double eye, final int eyeBlock, final double dir, final double tMin) {
        if (dir == 0.0) {
            // Always on (blockDiff == 0 as well).
            return Double.MAX_VALUE;
        }
        if (tMin == 0.0) {
            //  Already on the block, return "rest on block".
            final double eyeOffset = Math.abs(eye - eyeBlock); // (abs not needed)
            return (dir < 0.0 ? eyeOffset : 1.0 - eyeOffset) / Math.abs(dir);
        }
        // Just the time within range.
        return tMin + 1.0 /  Math.abs(dir);
    }

    /**
     * Correct the coordinate to be on the block (only if outside, for
     * correcting inside-block to edge tMin has to be checked.
     * 
     * @param coord
     * @param block
     * @return
     */
    private double toBlock(final double coord, final int block) {
        final int blockDiff = block - Location.locToBlock(coord);
        if (blockDiff == 0) {
            return coord;
        }
        else {
            return Math.round(coord);
        }
    }
    
    private class BlockCoord {
        private final int x;
        private final int y;
        private final int z;
        
        public BlockCoord(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        public int getX() {
            return x;
        }
        public int getY() {
            return y;
        }
        public int getZ() {
            return z;
        }

        @Override
        public int hashCode() {
            return CoordHash.hashCode3DPrimes(x, y, z);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            BlockCoord bc = (BlockCoord)obj;
            return bc.getX() == x && bc.getY() == y && bc.getZ() == z;
        }
    }
}
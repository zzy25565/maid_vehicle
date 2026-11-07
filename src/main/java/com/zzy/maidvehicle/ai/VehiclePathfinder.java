package com.zzy.maidvehicle.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 载具二维栅格 A* 寻路（基地巷道 / 城市道路等复杂环境）。
 *
 * <p>与生物的寻路不同，载具没有原版 WalkNodeEvaluator 可用：
 * <ul>
 *   <li>采样范围是以载具为中心的方形栅格（{@link #SEARCH_RADIUS} 格）；</li>
 *   <li>每格用方块碰撞形状判定"载具高度内是否空旷"，允许 1 格高差（小坡可过），
 *       并按地面载具 / 船分别校验脚下支撑或水域，避免把悬崖深坑上方的空气当路；</li>
 *   <li>按载具包围盒宽度做<b>障碍膨胀</b>，保证规划的通道车真能开过去；</li>
 *   <li>8 向 A*（禁止斜穿拐角），目标不可达时返回"离目标最近的可达点"路径；</li>
 *   <li>路径做视线简化，只保留拐点；简化时用 supercover 视线并同样禁止切角。</li>
 * </ul>
 *
 * <p>规划结果为空表示周围没有可走的路（例如被地形困死），调用方应退回直线追踪。
 */
public final class VehiclePathfinder {
    /** 搜索半径（格）。巷道场景 24 格足够覆盖一个街口。 */
    private static final int SEARCH_RADIUS = 24;
    /** A* 最大扩展节点数，防全开图导致卡顿。 */
    private static final int MAX_EXPANSIONS = 4000;
    /** 高度检查上限（格），防止异常特大的载具把采样搞爆。 */
    private static final int MAX_HEIGHT_CHECK = 6;

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private VehiclePathfinder() {
    }

    /**
     * 规划从载具当前位置到主人附近的路径。
     *
     * @return 航点列表（世界坐标，y 取载具当前层）；空表表示无路可走
     */
    public static List<BlockPos> findPath(ServerLevel level, VehicleEntity vehicle, LivingEntity owner) {
        BlockPos start = vehicle.blockPosition();
        int baseY = start.getY();
        BlockPos goal = owner.blockPosition();

        int minX = start.getX() - SEARCH_RADIUS;
        int maxX = start.getX() + SEARCH_RADIUS;
        int minZ = start.getZ() - SEARCH_RADIUS;
        int maxZ = start.getZ() + SEARCH_RADIUS;
        int gx = Mth.clamp(goal.getX(), minX, maxX);
        int gz = Mth.clamp(goal.getZ(), minZ, maxZ);

        int w = maxX - minX + 1;
        int d = maxZ - minZ + 1;
        boolean[] passable = samplePassable(level, vehicle, minX, minZ, w, d, baseY);

        int sx = start.getX() - minX;
        int sz = start.getZ() - minZ;
        int gxi = gx - minX;
        int gzi = gz - minZ;
        // 载具自身占位，起点必须是可通行
        passable[sz * w + sx] = true;

        int startIdx = sz * w + sx;
        int goalIdx = gzi * w + gxi;

        float[] gScore = new float[w * d];
        Arrays.fill(gScore, Float.MAX_VALUE);
        int[] cameFrom = new int[w * d];
        Arrays.fill(cameFrom, -1);
        boolean[] closed = new boolean[w * d];

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        gScore[startIdx] = 0.0F;
        open.add(new Node(startIdx, heuristic(sx, sz, gxi, gzi)));

        int bestIdx = startIdx;
        double bestH = heuristic(sx, sz, gxi, gzi);
        int expansions = 0;

        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            Node current = open.poll();
            if (closed[current.idx]) {
                continue;
            }
            closed[current.idx] = true;
            expansions++;

            int cx = current.idx % w;
            int cz = current.idx / w;

            double h = heuristic(cx, cz, gxi, gzi);
            if (h < bestH) {
                bestH = h;
                bestIdx = current.idx;
            }
            if (cx == gxi && cz == gzi) {
                bestIdx = current.idx;
                break;
            }

            for (int[] dir : DIRS) {
                int nx = cx + dir[0];
                int nz = cz + dir[1];
                if (nx < 0 || nx >= w || nz < 0 || nz >= d) {
                    continue;
                }
                int ni = nz * w + nx;
                if (closed[ni] || !passable[ni]) {
                    continue;
                }
                boolean diagonal = dir[0] != 0 && dir[1] != 0;
                if (diagonal && (!passable[cz * w + nx] || !passable[nz * w + cx])) {
                    // 禁止斜穿拐角
                    continue;
                }
                float ng = gScore[current.idx] + (diagonal ? 1.4142135F : 1.0F);
                if (ng < gScore[ni]) {
                    gScore[ni] = ng;
                    cameFrom[ni] = current.idx;
                    open.add(new Node(ni, ng + heuristic(nx, nz, gxi, gzi)));
                }
            }
        }

        if (bestIdx == startIdx) {
            return List.of();
        }

        // 回溯栅格路径
        List<int[]> raw = new ArrayList<>();
        int cursor = bestIdx;
        while (cursor != startIdx && cursor >= 0) {
            raw.add(new int[]{cursor % w, cursor / w});
            cursor = cameFrom[cursor];
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        Collections.reverse(raw);

        // 视线简化：只保留能直线到达的拐点
        List<BlockPos> result = new ArrayList<>();
        int fx = sx;
        int fz = sz;
        int i = 0;
        while (i < raw.size()) {
            int j = raw.size() - 1;
            while (j > i && !lineClear(passable, w, d, fx, fz, raw.get(j)[0], raw.get(j)[1])) {
                j--;
            }
            int[] p = raw.get(j);
            result.add(new BlockPos(minX + p[0], baseY, minZ + p[1]));
            fx = p[0];
            fz = p[1];
            i = j + 1;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 通行性采样
    // ------------------------------------------------------------------

    private static boolean[] samplePassable(ServerLevel level, VehicleEntity vehicle,
                                            int minX, int minZ, int w, int d, int baseY) {
        int height = Mth.clamp(Mth.ceil(vehicle.getBbHeight()), 2, MAX_HEIGHT_CHECK);
        boolean ship = vehicle.computed().getEngineType() == EngineType.SHIP;
        boolean[] raw = new boolean[w * d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int bx = minX + x;
                int bz = minZ + z;
                // 只看"车身空间是空气"不够：悬崖 / 深坑上方同样是空气。
                // 必须再确认脚下有支撑（地面载具）或脚下是水（船）。
                boolean atBase = columnPassable(level, bx, baseY, bz, height)
                        && hasSupport(level, bx, baseY, bz, ship);
                boolean oneBelow = columnPassable(level, bx, baseY - 1, bz, height)
                        && hasSupport(level, bx, baseY - 1, bz, ship);
                raw[z * w + x] = atBase || oneBelow;
            }
        }

        // 障碍膨胀：载具宽度越大，通道要求越宽
        int inflate = Math.max(1, (Mth.ceil(vehicle.getBbWidth()) - 1) / 2);
        boolean[] passable = raw.clone();
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                if (raw[z * w + x]) {
                    continue;
                }
                for (int dz = -inflate; dz <= inflate; dz++) {
                    for (int dx = -inflate; dx <= inflate; dx++) {
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx >= 0 && nx < w && nz >= 0 && nz < d) {
                            passable[nz * w + nx] = false;
                        }
                    }
                }
            }
        }
        return passable;
    }

    /** 检查 (x,z) 处高度 height 的空间是否无碰撞。 */
    private static boolean columnPassable(ServerLevel level, int x, int y, int z, int height) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < height; dy++) {
            pos.set(x, y + dy, z);
            if (!level.isLoaded(pos)) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 载具所在层 y 的脚下是否有支撑。
     *
     * <p>地面载具要求 y-1 有碰撞形状（水、空气、深坑都不算路）；
     * 船要求 y-1 或 y 有水，否则会把干燥的坑底当成可航行区域。
     */
    private static boolean hasSupport(ServerLevel level, int x, int y, int z, boolean ship) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, y - 1, z);
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState below = level.getBlockState(pos);
        if (ship) {
            return below.getFluidState().is(FluidTags.WATER)
                    || level.getBlockState(pos.set(x, y, z)).getFluidState().is(FluidTags.WATER);
        }
        return !below.getCollisionShape(level, pos).isEmpty();
    }

    /** 单格通行判定（统一做边界检查，供视线检查复用）。 */
    private static boolean isPassable(boolean[] passable, int w, int d, int x, int z) {
        return x >= 0 && x < w && z >= 0 && z < d && passable[z * w + x];
    }

    /**
     * Supercover 视线检查（用于路径简化）。
     *
     * <p>普通 Bresenham 只采样线段"经过"的格子，对角线会漏掉两侧格子：
     * 从 (0,0) 到 (2,2) 时 (1,0) 被墙挡住也可能判为可直达。
     * 这里对每个对角步额外检查两个正交邻格，与 A* 的"禁止斜穿拐角"规则保持一致。
     */
    private static boolean lineClear(boolean[] passable, int w, int d, int x0, int z0, int x1, int z1) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0;
        int z = z0;
        while (true) {
            if (!isPassable(passable, w, d, x, z)) {
                return false;
            }
            if (x == x1 && z == z1) {
                return true;
            }
            int px = x;
            int pz = z;
            int e2 = 2 * err;
            boolean stepX = false;
            boolean stepZ = false;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
                stepX = true;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
                stepZ = true;
            }
            if (stepX && stepZ
                    && (!isPassable(passable, w, d, px, z)
                        || !isPassable(passable, w, d, x, pz))) {
                // 对角步：两个正交邻格任一被挡都不许切角
                return false;
            }
        }
    }

    /** 栅格中心欧氏距离（容许 1.5 格误差，等效目标格判定放宽）。 */
    private static double heuristic(int x, int z, int gx, int gz) {
        double dx = x - gx;
        double dz = z - gz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private record Node(int idx, double f) {
    }
}

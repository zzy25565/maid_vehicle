package com.zzy.maidvehicle.ai;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "载具操控"任务的核心行为（服务端）。
 *
 * <p>三个职责：
 * <ol>
 *   <li><b>找车上车</b>：按优先级选载具（空载具 &gt; 玩家载具副炮位 &gt; 其他女仆载具副炮位 &gt; 玩家载具搭车），
 *       同级内血量高、距离近者优先；</li>
 *   <li><b>驾驶跟随</b>：女仆是第一个乘客（驾驶员）时，调用
 *       {@link VehicleEntity#processInput(short)} 追着主人开（SBW 的驾驶输入是纯服务端公开方法）；</li>
 *   <li><b>索敌开火</b>：设置女仆攻击目标（脑记忆 ATTACK_TARGET + setTarget）——
 *       SBW 对载具上的 Mob 自带弹道解算自动瞄准与按 RPM 自动开火，无需我们干预。</li>
 * </ol>
 *
 * <p>输入位编码与 SBW {@code VehicleMovementMessage} 一致：
 * bit0 左 / bit1 右 / bit2 前 / bit3 后。仅在按键意图变化时调用 processInput，
 * 与 SBW 客户端（keys != keysCache 才发包）同频。
 */
public class DriveVehicleBehavior extends Behavior<EntityMaid> {
    // ===== 驾驶输入位（SBW 同款编码）=====
    private static final short KEY_LEFT = 0b0000_0001;
    private static final short KEY_RIGHT = 0b0000_0010;
    private static final short KEY_FORWARD = 0b0000_0100;
    private static final short KEY_BACK = 0b0000_1000;

    // ===== 可调参数 =====
    private static final int SCAN_INTERVAL = 10;
    private static final double BOARDING_DIST = 3.5;
    private static final double FORCE_STOP_DIST = 8.0;
    private static final double VEHICLE_SCAN_RANGE = 48.0;
    private static final double TARGET_SEARCH_RANGE = 48.0;
    private static final double TARGET_MAX_RANGE = 96.0;
    private static final int STUCK_TICKS = 40;
    private static final int REVERSE_TICKS = 20;
    private static final int MAX_STUCK_ATTEMPTS = 3;
    private static final int GIVE_UP_IDLE_TICKS = 200;
    private static final int ENERGY_CHECK_INTERVAL = 40;

    /** 寻路重规划周期（tick） */
    private static final int REPLAN_INTERVAL = 30;
    /** 目标移动超过该距离（格）时立刻重规划 */
    private static final double REPLAN_GOAL_MOVE = 6.0;

    /**
     * 按引擎类型分化的驾驶参数。
     *
     * <p>SBW 物理差异（源码实证）：
     * <ul>
     *   <li>履带 {@code trackEngine}：{@code yRot -= 8.0 * deltaRot}，转向不依赖速度 → 能原地转；</li>
     *   <li>轮式 {@code wheelEngine}：{@code yRot -= max(12 * 水平速度, 0) * rudder}，转向 ∝ 速度
     *       → 静止打方向无效，必须带油门才能转弯；倒车时转向自动反向（SBW 已处理）。</li>
     * </ul>
     *
     * @param turnDeadzone     转向死区（度）
     * @param pivotYawLimit    超过该角度误差时履带原地转向（不踩油门）
     * @param stopDist         跟随停车距离（格）
     * @param coastDist        滑行松油距离（格，轮式高速防过冲；0 = 不减速）
     * @param waypointReach    航点到达判定半径（格）
     * @param pivotInPlace     是否允许原地转向
     * @param maxPivotTicks    原地转向超时（超过则强制给油防止卡死）
     */
    private record DriveProfile(float turnDeadzone, float pivotYawLimit, double stopDist,
                                double coastDist, double waypointReach,
                                boolean pivotInPlace, int maxPivotTicks) {
    }

    /** 履带：可原地转向，转向精确，可贴脸停车 */
    private static final DriveProfile TRACK_PROFILE =
            new DriveProfile(3.0F, 45.0F, 12.0, 0.0, 3.5, true, 60);
    /** 轮式 / 船 / 轮椅：转向依赖速度，必须边走边转；转弯半径大，提前滑行减速 */
    private static final DriveProfile WHEEL_PROFILE =
            new DriveProfile(5.0F, 120.0F, 14.0, 26.0, 5.0, false, 0);

    // ===== 选车优先级（用户定稿）=====
    private static final int PRI_EMPTY_VEHICLE = 1;
    private static final int PRI_OWNER_WITH_WEAPON = 2;
    private static final int PRI_MAID_WITH_WEAPON = 3;
    private static final int PRI_OWNER_PLAIN = 4;

    /** 第一版开放驾驶的引擎类型：轮式 / 履带 / 船 / 轮椅 */
    private static final Set<EngineType> DRIVABLE =
            EnumSet.of(EngineType.WHEEL, EngineType.TRACK, EngineType.WHEELCHAIR, EngineType.SHIP);

    // ===== 运行状态 =====
    private short lastKeys = 0;
    private int stuckTicks = 0;
    private int reverseTicks = 0;
    private int stuckAttempts = 0;
    private int giveUpTicks = 0;
    private int energyTimer = 0;
    private int energyCache = Integer.MAX_VALUE;
    @Nullable
    private Vec3 lastVehiclePos = null;

    // ===== 寻路状态 =====
    private List<BlockPos> path = List.of();
    private int pathIndex = 0;
    private int replanTimer = 0;
    @Nullable
    private BlockPos lastGoal = null;
    /** 原地转向计时（履带专用，防止原地卡死） */
    private int pivotTicks = 0;

    public DriveVehicleBehavior() {
        // 10 分钟超时上限（超时会 stop 再 start，字段保留，无缝续跑）
        super(Map.of(), 20 * 60 * 10);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        LivingEntity owner = maid.getOwner();
        return maid.isAlive() && !maid.isMaidInSittingPose() && maid.isRideable()
                && owner != null && owner.isAlive();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        LivingEntity owner = maid.getOwner();
        return maid.isAlive() && !maid.isMaidInSittingPose() && owner != null && owner.isAlive();
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        LivingEntity owner = maid.getOwner();
        if (owner == null) {
            return;
        }

        if (maid.isPassenger() && maid.getVehicle() instanceof VehicleEntity vehicle) {
            if (vehicle.isWreck()) {
                maid.stopRiding();
                return;
            }
            if (vehicle.getFirstPassenger() == maid) {
                driveTowardOwner(level, maid, vehicle, owner);
            } else {
                applyKeys(vehicle, (short) 0);
            }
            updateAttackTarget(level, maid, vehicle, owner);
        } else if (!maid.isPassenger()) {
            if (maid.tickCount % SCAN_INTERVAL != 0) {
                return;
            }
            if (!tryBoardVehicle(level, maid, owner)) {
                // 无车可上：步行跟随主人兜底
                BehaviorUtils.setWalkAndLookTargetMemories(maid, owner, 1.0F, 2);
            }
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        // 行为被移除或超时重启：松开驾驶键，防止载具保持最后输入继续行驶。
        // 不在此处强制下车——任务切换后女仆留在车上更符合直觉。
        if (maid.isPassenger() && maid.getVehicle() instanceof VehicleEntity vehicle
                && vehicle.getFirstPassenger() == maid) {
            vehicle.processInput((short) 0);
        }
        lastKeys = 0;
        stuckTicks = 0;
        reverseTicks = 0;
        stuckAttempts = 0;
        giveUpTicks = 0;
        lastVehiclePos = null;
        path = List.of();
        pathIndex = 0;
        replanTimer = 0;
        lastGoal = null;
        pivotTicks = 0;
    }

    // =====================================================================
    // 驾驶
    // =====================================================================

    private void driveTowardOwner(ServerLevel level, EntityMaid maid, VehicleEntity vehicle, LivingEntity owner) {
        DriveProfile profile = profileFor(vehicle);

        if (giveUpTicks > 0) {
            giveUpTicks--;
            applyKeys(vehicle, (short) 0);
            return;
        }

        // 能量检查（带缓存，getEnergy 在无储能时会刷日志）
        if (energyTimer <= 0) {
            energyCache = vehicle.getEnergy();
            energyTimer = ENERGY_CHECK_INTERVAL;
        }
        energyTimer--;
        if (energyCache <= 0) {
            applyKeys(vehicle, (short) 0);
            return;
        }

        // 脱困倒车阶段：倒车 + 反向转向（SBW 对履带/轮式的倒车转向均已自动反向）
        if (reverseTicks > 0) {
            reverseTicks--;
            short key = pickTurnKey(vehicle, owner.getX(), owner.getZ(), true, profile.turnDeadzone());
            applyKeys(vehicle, (short) (KEY_BACK | key));
            return;
        }

        double dx = owner.getX() - vehicle.getX();
        double dz = owner.getZ() - vehicle.getZ();
        double distSq = dx * dx + dz * dz;

        // 到了：贴脸（任何情况）或进入跟随距离且能直视
        if (distSq <= FORCE_STOP_DIST * FORCE_STOP_DIST
                || (distSq <= profile.stopDist() * profile.stopDist() && hasClearLine(level, vehicle, owner))) {
            applyKeys(vehicle, (short) 0);
            resetStuck();
            path = List.of();
            pathIndex = 0;
            pivotTicks = 0;
            return;
        }

        // 规划 / 重规划（巷道绕障的核心）
        BlockPos goalPos = owner.blockPosition();
        boolean needReplan = path.isEmpty()
                || pathIndex >= path.size()
                || replanTimer <= 0
                || lastGoal == null
                || lastGoal.distSqr(goalPos) > REPLAN_GOAL_MOVE * REPLAN_GOAL_MOVE;
        if (needReplan) {
            path = VehiclePathfinder.findPath(level, vehicle, owner);
            pathIndex = 0;
            replanTimer = REPLAN_INTERVAL;
            lastGoal = goalPos;
        }

        double targetX;
        double targetZ;
        double targetDistSq;
        if (path.isEmpty()) {
            // 无路可走：退回直线追踪（靠卡住脱困兜底）
            targetX = owner.getX();
            targetZ = owner.getZ();
            targetDistSq = distSq;
        } else {
            // 推进已到达的航点
            while (pathIndex < path.size() - 1
                    && reachedWaypoint(vehicle, path.get(pathIndex), profile.waypointReach())) {
                pathIndex++;
            }
            BlockPos wp = path.get(Math.min(pathIndex, path.size() - 1));
            targetX = wp.getX() + 0.5;
            targetZ = wp.getZ() + 0.5;
            targetDistSq = distanceSqTo(vehicle, targetX, targetZ);
            // 最后一段：主人已在停车距离内且能直视，直接停车
            if (distSq <= profile.stopDist() * profile.stopDist() && hasClearLine(level, vehicle, owner)) {
                applyKeys(vehicle, (short) 0);
                resetStuck();
                return;
            }
        }
        replanTimer--;

        // ---- 转向 + 油门：按引擎类型分化 ----
        short keys = pickTurnKey(vehicle, targetX, targetZ, false, profile.turnDeadzone());
        float yawErrAbs = Math.abs(yawErrorTo(vehicle, targetX, targetZ));
        boolean wantForward;
        if (yawErrAbs >= profile.pivotYawLimit()) {
            // 大角度误差：履带原地转向（不踩油门），轮式必须带油门否则打不动方向
            wantForward = !profile.pivotInPlace();
        } else {
            wantForward = true;
        }
        // 轮式高速接近时提前松油门滑行，避免冲过头
        if (profile.coastDist() > 0 && targetDistSq <= profile.coastDist() * profile.coastDist()) {
            wantForward = false;
        }
        // 原地转向超时保护：长时间没转正就强行给油，防止僵死
        if (wantForward) {
            pivotTicks = 0;
        } else if (profile.pivotInPlace() && ++pivotTicks > profile.maxPivotTicks()) {
            wantForward = true;
            pivotTicks = 0;
        }
        if (wantForward) {
            keys |= KEY_FORWARD;
        }
        applyKeys(vehicle, keys);
        detectStuck(vehicle, keys);
    }

    /** 按引擎类型取驾驶参数：履带可原地转，轮式/船/轮椅必须边走边转。 */
    private static DriveProfile profileFor(VehicleEntity vehicle) {
        return vehicle.computed().getEngineType() == EngineType.TRACK ? TRACK_PROFILE : WHEEL_PROFILE;
    }

    private static double distanceSqTo(VehicleEntity vehicle, double x, double z) {
        double dx = x - vehicle.getX();
        double dz = z - vehicle.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean reachedWaypoint(VehicleEntity vehicle, BlockPos wp, double reach) {
        double dx = wp.getX() + 0.5 - vehicle.getX();
        double dz = wp.getZ() + 0.5 - vehicle.getZ();
        return dx * dx + dz * dz <= reach * reach;
    }

    /** 两实体视点之间是否有方块阻挡（1.21.1 没有 Entity#hasLineOfSight）。 */
    private static boolean hasClearLine(ServerLevel level, Entity from, Entity to) {
        Vec3 start = from.getEyePosition();
        Vec3 end = to.getEyePosition();
        ClipContext ctx = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from);
        return level.clip(ctx).getType() == HitResult.Type.MISS;
    }

    /** 计算朝向目标点的 yaw 误差（度，wrap 后）。 */
    private static float yawErrorTo(VehicleEntity vehicle, double targetX, double targetZ) {
        double dx = targetX - vehicle.getX();
        double dz = targetZ - vehicle.getZ();
        // MC 实体 yaw：0=+Z，90=-X；朝向 (dx,dz) 需要 yaw = atan2(-dx, dz)
        float targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
        return Mth.wrapDegrees(targetYaw - vehicle.getYRot());
    }

    /**
     * 依据 yaw 误差取转向键。MC 中 yaw 增大 = 车头右转（南→西为左转，yaw 减小），
     * 故 err &gt; 0 打右键；若实测转向相反，只需交换两个返回值。
     */
    private short pickTurnKey(VehicleEntity vehicle, double targetX, double targetZ, boolean invert, float deadzone) {
        float yawErr = yawErrorTo(vehicle, targetX, targetZ);
        if (invert) {
            yawErr = -yawErr;
        }
        if (yawErr > deadzone) {
            return KEY_RIGHT;
        }
        if (yawErr < -deadzone) {
            return KEY_LEFT;
        }
        return 0;
    }

    private void applyKeys(VehicleEntity vehicle, short keys) {
        if (keys != lastKeys) {
            vehicle.processInput(keys);
            lastKeys = keys;
        }
    }

    private void detectStuck(VehicleEntity vehicle, short keys) {
        if ((keys & KEY_FORWARD) == 0) {
            resetStuck();
            return;
        }
        Vec3 pos = vehicle.position();
        if (lastVehiclePos != null && pos.distanceToSqr(lastVehiclePos) < 0.0004) {
            stuckTicks++;
            if (stuckTicks >= STUCK_TICKS) {
                stuckTicks = 0;
                if (stuckAttempts < MAX_STUCK_ATTEMPTS) {
                    stuckAttempts++;
                    reverseTicks = REVERSE_TICKS;
                    // 撞墙大概率说明当前路径不可行：丢掉路径强制重规划
                    path = List.of();
                    pathIndex = 0;
                    replanTimer = 0;
                } else {
                    // 连续脱困失败：原地休眠 10 秒再试（大概率没油或被地形卡死）
                    giveUpTicks = GIVE_UP_IDLE_TICKS;
                    stuckAttempts = 0;
                }
            }
        } else {
            stuckTicks = 0;
            stuckAttempts = 0;
        }
        lastVehiclePos = pos;
    }

    private void resetStuck() {
        stuckTicks = 0;
        stuckAttempts = 0;
        lastVehiclePos = null;
    }

    // =====================================================================
    // 选车上车
    // =====================================================================

    /**
     * 按优先级选车并接近/上车。
     *
     * @return 是否存在值得接近的载具（含已开始接近）
     */
    private boolean tryBoardVehicle(ServerLevel level, EntityMaid maid, LivingEntity owner) {
        List<VehicleEntity> candidates = level.getEntitiesOfClass(VehicleEntity.class,
                maid.getBoundingBox().inflate(VEHICLE_SCAN_RANGE, 16, VEHICLE_SCAN_RANGE),
                v -> !v.isWreck() && !v.computed().seats().isEmpty());
        if (candidates.isEmpty()) {
            return false;
        }

        Entity ownerVehicleRaw = owner.getVehicle();
        boolean ownerOnSbw = ownerVehicleRaw instanceof VehicleEntity ownerVehicle
                && ownerVehicle.getFirstPassenger() == owner;

        VehicleEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        int bestPri = 0;
        for (VehicleEntity v : candidates) {
            int pri = classify(v, owner, ownerOnSbw, maid);
            if (pri <= 0) {
                continue;
            }
            // 同级内：血量高者优先（血量归一到 1000），其次距离近者优先
            double score = pri * 1_000_000.0
                    + Math.min(v.getHealth(), 1000.0F) * 100.0
                    - maid.distanceTo(v);
            if (score > bestScore) {
                bestScore = score;
                best = v;
                bestPri = pri;
            }
        }
        if (best == null) {
            return false;
        }

        if (maid.distanceTo(best) <= BOARDING_DIST) {
            if (!maid.startRiding(best)) {
                return false;
            }
            // 上玩家的车/女仆的车：挪到武器站控制位（副炮位）
            if (bestPri == PRI_OWNER_WITH_WEAPON || bestPri == PRI_MAID_WITH_WEAPON) {
                int ws = best.getPassengerWeaponStationControllerIndex();
                if (best.hasPassengerWeaponStation() && best.getGunData(ws) != null
                        && best.getNthEntity(ws) == null) {
                    best.changeSeat(maid, ws);
                }
            }
            return true;
        }

        if (!maid.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
            BehaviorUtils.setWalkAndLookTargetMemories(maid, best, 1.0F, 0);
        }
        return true;
    }

    /**
     * 载具分类（返回优先级，0 = 不考虑）。
     *
     * <pre>
     * 1. 空载具（可驾驶类型）——女仆自己开
     * 2. 玩家载具（副炮位空且有武器）——上副炮位
     * 3. 其他女仆驾驶载具（副炮位空且有武器）——上副炮位
     * 4. 玩家载具（无可用武器位但有空位）——搭车
     * </pre>
     * "都已有女仆乘坐时优先玩家载具副炮位"由 2 &gt; 3 的优先级顺序天然保证。
     */
    private int classify(VehicleEntity v, LivingEntity owner, boolean ownerOnSbw, EntityMaid maid) {
        Entity first = v.getFirstPassenger();

        if (first == null) {
            return DRIVABLE.contains(v.computed().getEngineType()) ? PRI_EMPTY_VEHICLE : 0;
        }

        boolean ownerDriving = ownerOnSbw && first == owner;
        int ws = v.getPassengerWeaponStationControllerIndex();
        boolean freeWeaponSeat = v.hasPassengerWeaponStation()
                && v.getGunData(ws) != null
                && v.getNthEntity(ws) == null;
        boolean hasFreeSeat = v.getPassengers().size() < v.computed().seats().size();

        if (ownerDriving) {
            if (freeWeaponSeat) {
                return PRI_OWNER_WITH_WEAPON;
            }
            return hasFreeSeat ? PRI_OWNER_PLAIN : 0;
        }
        if (first instanceof EntityMaid) {
            return freeWeaponSeat ? PRI_MAID_WITH_WEAPON : 0;
        }
        return 0;
    }

    // =====================================================================
    // 索敌（SBW 原生负责瞄准与开火，我们只喂目标）
    // =====================================================================

    private void updateAttackTarget(ServerLevel level, EntityMaid maid, VehicleEntity vehicle, LivingEntity owner) {
        if (maid.tickCount % SCAN_INTERVAL != 0) {
            return;
        }
        LivingEntity current = maid.getTarget();
        if (isValidTarget(current, maid, owner, vehicle)) {
            // 目标仍有效：续期脑记忆（expiry 100t < 验证间隔会中断 SBW 开火循环）
            maid.getBrain().setMemoryWithExpiry(MemoryModuleType.ATTACK_TARGET, current, 100L);
            return;
        }
        setTarget(maid, pickTarget(level, maid, vehicle, owner));
    }

    @Nullable
    private LivingEntity pickTarget(ServerLevel level, EntityMaid maid, VehicleEntity vehicle, LivingEntity owner) {
        // 1) 主人最近攻击的目标
        LivingEntity ownerTarget = owner.getLastHurtMob();
        if (isValidTarget(ownerTarget, maid, owner, vehicle)) {
            return ownerTarget;
        }
        // 2) 最近攻击主人的敌人
        LivingEntity ownerAttacker = owner.getLastHurtByMob();
        if (isValidTarget(ownerAttacker, maid, owner, vehicle)) {
            return ownerAttacker;
        }
        // 3) 载具附近最近的敌对生物
        AABB box = vehicle.getBoundingBox().inflate(TARGET_SEARCH_RANGE, 16, TARGET_SEARCH_RANGE);
        LivingEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e instanceof Enemy && e != maid && e != owner
                        && !e.isPassengerOfSameVehicle(maid))) {
            double d = vehicle.distanceToSqr(e);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = e;
            }
        }
        return best;
    }

    private boolean isValidTarget(@Nullable LivingEntity t, EntityMaid maid, LivingEntity owner, VehicleEntity vehicle) {
        if (t == null || !t.isAlive() || t == maid || t == owner || t instanceof EntityMaid) {
            return false;
        }
        if (t.isPassengerOfSameVehicle(maid) || t.isAlliedTo(maid) || t.isAlliedTo(owner)) {
            return false;
        }
        if (vehicle.distanceToSqr(t) > TARGET_MAX_RANGE * TARGET_MAX_RANGE) {
            return false;
        }
        return t instanceof Enemy || t == owner.getLastHurtMob() || t == owner.getLastHurtByMob();
    }

    private void setTarget(EntityMaid maid, @Nullable LivingEntity target) {
        // setTarget 触发 NeoForge LivingChangeTargetEvent → SBW 自动同步 aiTurretTargetUUID
        maid.setTarget(target);
        // EntityMaid.getTarget() 读脑记忆 ATTACK_TARGET（SBW 的 mob.target 即调它）
        if (target != null) {
            maid.getBrain().setMemoryWithExpiry(MemoryModuleType.ATTACK_TARGET, target, 100L);
        } else {
            maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        }
    }
}

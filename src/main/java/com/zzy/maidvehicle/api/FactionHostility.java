package com.zzy.maidvehicle.api;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

import org.jetbrains.annotations.Nullable;
import java.util.Objects;

/**
 * 阵营敌对策略的注册门面。
 *
 * <p><b>默认状态：{@code policy == null}，即「未应用」。</b>
 * 本 mod 内部没有任何地方调用 {@link #register(FactionHostilityPolicy)}，
 * 因此所有静态判定方法都返回 {@code false}，索敌逻辑与 1.0.0d 逐字等价。
 * 注册是未来启用 PVP 阵营战的唯一开关。
 *
 * <p>字段用 {@code volatile}：注册可能发生在模组初始化线程，读取发生在服务端 tick 线程。
 */
public final class FactionHostility {

    @Nullable
    private static volatile FactionHostilityPolicy policy = null;

    private FactionHostility() {
    }

    /** 注册策略（重复注册以最后一次为准）。需要关闭时调用 {@link #clear()}。 */
    public static void register(FactionHostilityPolicy newPolicy) {
        policy = Objects.requireNonNull(newPolicy, "FactionHostilityPolicy");
    }

    /** 取消注册，回到默认（关闭）状态。 */
    public static void clear() {
        policy = null;
    }

    /** 取当前策略，未注册时返回 {@code null}。 */
    @Nullable
    public static FactionHostilityPolicy get() {
        return policy;
    }

    /** 是否存在已注册的策略。仅用于诊断 / 日志，不参与判定。 */
    public static boolean isEnabled() {
        return policy != null;
    }

    /**
     * 目标有效性判定用的敌对询问。未注册策略时恒为 {@code false}。
     */
    public static boolean isHostile(LivingEntity candidate, EntityMaid maid,
                                    LivingEntity owner, VehicleEntity vehicle) {
        FactionHostilityPolicy p = policy;
        return p != null && p.isHostile(candidate, maid, owner, vehicle);
    }

    /**
     * 主动索敌扫描用的候选判定：只对非 {@link Enemy} 实体生效，且要求策略显式开启主动扫描。
     * 未注册策略时恒为 {@code false}，扫描谓词退化为原来的 {@code e instanceof Enemy}。
     */
    public static boolean isProactiveCandidate(LivingEntity candidate, EntityMaid maid,
                                               LivingEntity owner, VehicleEntity vehicle) {
        FactionHostilityPolicy p = policy;
        return p != null
                && !(candidate instanceof Enemy)
                && p.enableProactiveScan()
                && p.isHostile(candidate, maid, owner, vehicle);
    }
}

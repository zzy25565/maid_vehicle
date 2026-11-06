package com.zzy.maidvehicle.api;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;

/**
 * PVP / 阵营战敌我识别策略（预留扩展点）。
 *
 * <p><b>当前版本未注册任何实现，本接口完全不生效</b>：女仆的索敌行为与 1.0.0d 一致，
 * 只攻击 {@code Enemy} 怪物以及最近攻击过主人 / 女仆自己的实体。
 * 由未来的阵营战模组（或本 mod 的后续版本）自行实现并通过
 * {@link FactionHostility#register(FactionHostilityPolicy)} 注册后才启用。
 *
 * <p>调用前 addon 已完成基础安全过滤，实现方<b>无需重复</b>检查：
 * <ul>
 *   <li>目标存活、不是女仆自己、不是主人、不是其他女仆；</li>
 *   <li>不与女仆同乘一辆载具；</li>
 *   <li>不被 {@code isAlliedTo(maid)} / {@code isAlliedTo(owner)} 视为同盟
 *       （scoreboard 同队玩家在这里已经被挡掉）；</li>
 *   <li>在载具的最大索敌距离内。</li>
 * </ul>
 *
 * <p>实现必须是纯判定、无副作用、可被高频调用（每次索敌扫描对候选实体逐个调用），
 * 不要在这里做 IO、发包或修改世界。
 */
@FunctionalInterface
public interface FactionHostilityPolicy {

    /**
     * 判断候选实体是否应被视为敌对目标。
     *
     * <p>返回 {@code true} 会同时影响：
     * <ol>
     *   <li>目标有效性续期（已锁定的目标不会因 {@code ATTACK_TARGET} 记忆过期而中断开火）；</li>
     *   <li>反击链路：主人 / 女仆最近被该实体攻击时可以把目标回喂给 SBW。</li>
     * </ol>
     *
     * @param candidate 候选目标（可能是玩家、怪物或任意 LivingEntity）
     * @param maid      执行「载具操控」任务的女仆
     * @param owner     女仆的主人
     * @param vehicle   女仆当前乘坐的载具
     */
    boolean isHostile(LivingEntity candidate, EntityMaid maid,
                      LivingEntity owner, VehicleEntity vehicle);

    /**
     * 是否允许把<b>非 {@code Enemy}</b> 实体纳入主动索敌扫描（载具周围的 AABB 轮询）。
     *
     * <p>默认 {@code false}：即便注册了策略，女仆也只在被打 / 主人开火时反击，
     * 不会主动对范围内的玩家开火。想让女仆主动清场才覆写为 {@code true}。
     * 开启前请自行处理出生点保护、中立玩家白名单等规则。
     */
    default boolean enableProactiveScan() {
        return false;
    }
}

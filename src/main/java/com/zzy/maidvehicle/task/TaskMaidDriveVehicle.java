package com.zzy.maidvehicle.task;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import com.zzy.maidvehicle.MaidVehicleMod;
import com.zzy.maidvehicle.ai.DriveVehicleBehavior;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.jetbrains.annotations.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * 女仆任务栏中的"载具操控"任务卡。
 *
 * <p>行为挂两处：
 * <ul>
 *   <li>{@link #createBrainTasks} —— 女仆未骑乘时：走向选中的载具并上车；</li>
 *   <li>{@link #createRideBrainTasks} —— 女仆骑乘时（TLM 骑乘状态下大脑会切换到该列表）：
 *       驾驶跟随主人 / 坐副炮位索敌开火。</li>
 * </ul>
 * 两个列表返回同一个 Behavior 实例，由其内部根据是否在 SBW 载具上自动切换行为。
 */
public class TaskMaidDriveVehicle implements IMaidTask {
    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(MaidVehicleMod.MODID, "drive_vehicle");

    private static ItemStack ICON;

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        if (ICON == null) {
            ICON = new ItemStack(Items.MINECART);
        }
        return ICON;
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public boolean enablePanic(EntityMaid maid) {
        return false;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        // 优先级 5：高于多数常规任务行为，保证接近载具的动作不被走动 AI 干扰
        return Collections.singletonList(Pair.of(5, new DriveVehicleBehavior()));
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createRideBrainTasks(EntityMaid maid) {
        // TLM 骑乘大脑里 6=MaidRideBegTask / 7=MaidWorkMealTask，本行为取 5 优先执行
        return Collections.singletonList(Pair.of(5, new DriveVehicleBehavior()));
    }
}

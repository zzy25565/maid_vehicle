package com.zzy.maidvehicle;

import net.neoforged.fml.common.Mod;

@Mod(MaidVehicleMod.MODID)
public final class MaidVehicleMod {
    public static final String MODID = "maid_vehicle";

    // NeoForge 21.1 (FML 4.0.x) 空构造即可；
    // 任务注册走 MaidVehicleExtension（@LittleMaidExtension），无实体/方块注册需求。
    public MaidVehicleMod() {
    }
}

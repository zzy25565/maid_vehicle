package com.zzy.maidvehicle;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.zzy.maidvehicle.task.TaskMaidDriveVehicle;

/**
 * TLM 扩展入口：TLM 启动时通过注解扫描发现本类，
 * 在 TaskManager.init() 中回调 addMaidTask 注册"载具操控"任务卡。
 */
@LittleMaidExtension
public class MaidVehicleExtension implements ILittleMaid {
    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new TaskMaidDriveVehicle());
    }
}

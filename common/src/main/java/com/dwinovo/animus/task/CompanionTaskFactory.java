package com.dwinovo.animus.task;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.task.tasks.BreakBlockCompanionTask;
import com.dwinovo.animus.task.tasks.BreakBlockTaskRecord;
import com.dwinovo.animus.task.tasks.DropCompanionTask;
import com.dwinovo.animus.task.tasks.DropItemsTaskRecord;
import com.dwinovo.animus.task.tasks.EatCompanionTask;
import com.dwinovo.animus.task.tasks.EatItemTaskRecord;
import com.dwinovo.animus.task.tasks.EquipCompanionTask;
import com.dwinovo.animus.task.tasks.HuntCompanionTask;
import com.dwinovo.animus.task.tasks.HuntTaskRecord;
import com.dwinovo.animus.task.tasks.ShootCompanionTask;
import com.dwinovo.animus.task.tasks.ShootTaskRecord;
import com.dwinovo.animus.task.tasks.EquipTaskRecord;
import com.dwinovo.animus.task.tasks.MineBlockTaskRecord;
import com.dwinovo.animus.task.tasks.MineCompanionTask;
import com.dwinovo.animus.task.tasks.MoveToCompanionTask;
import com.dwinovo.animus.task.tasks.MoveToTaskRecord;
import com.dwinovo.animus.task.tasks.PlaceBlockCompanionTask;
import com.dwinovo.animus.task.tasks.PlaceBlockTaskRecord;
import com.dwinovo.animus.task.tasks.WaitCompanionTask;
import com.dwinovo.animus.task.tasks.WaitTaskRecord;

/**
 * Maps a queued {@link TaskRecord} to the {@link CompanionTask} that runs it on
 * the player body. Phase 0 wires {@code move_to} and {@code auto_mine}; every
 * other tool resolves to {@link UnsupportedCompanionTask} until its executor is
 * ported off the old Mob {@code LlmTaskGoal}.
 */
public final class CompanionTaskFactory {

    private CompanionTaskFactory() {}

    public static CompanionTask create(AnimusPlayer player, TaskRecord record) {
        if (record instanceof MoveToTaskRecord r) return new MoveToCompanionTask(player, r);
        if (record instanceof MineBlockTaskRecord r) return new MineCompanionTask(player, r);
        if (record instanceof EquipTaskRecord r) return new EquipCompanionTask(player, r);
        if (record instanceof WaitTaskRecord r) return new WaitCompanionTask(player, r);
        if (record instanceof DropItemsTaskRecord r) return new DropCompanionTask(player, r);
        if (record instanceof BreakBlockTaskRecord r) return new BreakBlockCompanionTask(player, r);
        if (record instanceof EatItemTaskRecord r) return new EatCompanionTask(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.DepositItemsTaskRecord r)
            return new com.dwinovo.animus.task.tasks.DepositItemsTaskGoal(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.TakeItemsTaskRecord r)
            return new com.dwinovo.animus.task.tasks.TakeItemsTaskGoal(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.CheckFurnaceTaskRecord r)
            return new com.dwinovo.animus.task.tasks.CheckFurnaceTaskGoal(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.LoadFurnaceTaskRecord r)
            return new com.dwinovo.animus.task.tasks.LoadFurnaceTaskGoal(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.CollectFurnaceTaskRecord r)
            return new com.dwinovo.animus.task.tasks.CollectFurnaceTaskGoal(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.CraftTaskRecord r)
            return new com.dwinovo.animus.task.tasks.CraftTaskGoal(player, r);
        if (record instanceof HuntTaskRecord r) return new HuntCompanionTask(player, r);
        if (record instanceof ShootTaskRecord r) return new ShootCompanionTask(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.CollectItemsTaskRecord r)
            return new com.dwinovo.animus.task.tasks.CollectItemsTaskGoal(player, r);
        if (record instanceof PlaceBlockTaskRecord r) return new PlaceBlockCompanionTask(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.InteractAtTaskRecord r)
            return new com.dwinovo.animus.task.tasks.InteractAtCompanionTask(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.InteractEntityTaskRecord r)
            return new com.dwinovo.animus.task.tasks.InteractEntityCompanionTask(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.LocateStructureTaskRecord r)
            return new com.dwinovo.animus.task.tasks.LocateStructureTaskGoal(player, r);
        if (record instanceof com.dwinovo.animus.task.tasks.LocateBiomeTaskRecord r)
            return new com.dwinovo.animus.task.tasks.LocateBiomeTaskGoal(player, r);
        return new UnsupportedCompanionTask(record);
    }
}

package com.dwinovo.animus.task;

import com.dwinovo.animus.entity.AnimusPlayer;
import com.dwinovo.animus.task.tasks.EquipCompanionTask;
import com.dwinovo.animus.task.tasks.EquipTaskRecord;
import com.dwinovo.animus.task.tasks.MineBlockTaskRecord;
import com.dwinovo.animus.task.tasks.MineCompanionTask;
import com.dwinovo.animus.task.tasks.MoveToCompanionTask;
import com.dwinovo.animus.task.tasks.MoveToTaskRecord;

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
        return new UnsupportedCompanionTask(record);
    }
}

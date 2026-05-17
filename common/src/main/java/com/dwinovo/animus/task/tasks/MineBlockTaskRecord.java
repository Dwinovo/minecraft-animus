package com.dwinovo.animus.task.tasks;

import com.dwinovo.animus.task.TaskRecord;
import net.minecraft.core.BlockPos;

/**
 * Typed task descriptor for the {@code mine_block} tool. Carries the target
 * block position; tool-tier / drops behaviour follows from whatever the
 * entity is currently holding at start time.
 *
 * <p>Tool layer applies a generous default timeout (60s at 20 tps) so even
 * obsidian-with-iron-pickaxe completes (~10s actual) and stone-with-bare-hands
 * (~7.5s) finishes well within the budget. Mining bedrock or anything with
 * negative hardness fails immediately at the goal's start hook.
 */
public final class MineBlockTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "mine_block";

    public final BlockPos pos;

    public MineBlockTaskRecord(String toolCallId, long deadlineGameTime, BlockPos pos) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.pos = pos;
    }
}

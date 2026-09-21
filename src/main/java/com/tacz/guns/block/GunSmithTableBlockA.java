package com.tacz.guns.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 单方块的枪械工作台
 */
public class GunSmithTableBlockA extends AbstractGunSmithTableBlock {
    public GunSmithTableBlockA(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction direction = context.getHorizontalDirection().getOpposite();
        return this.defaultBlockState().setValue(FACING, direction);
    }

    @Override
    public boolean isRoot(BlockState blockState) {
        return true;
    }

    @Override
    public BlockPos getRootPos(BlockPos pos, BlockState blockState) {
        return pos;
    }
}

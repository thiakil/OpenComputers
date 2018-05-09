package li.cil.oc.common.block

import li.cil.oc.common.block.property.PropertyTile
import li.cil.oc.common.tileentity
import li.cil.oc.integration.util.Wrench
import net.minecraft.block.state.IBlockState
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.BlockPos
import net.minecraft.world.IBlockAccess
import net.minecraft.world.World
import net.minecraftforge.common.property.ExtendedBlockState
import net.minecraftforge.common.property.IExtendedBlockState
import net.minecraftforge.common.property.IUnlistedProperty

class NetSplitter extends RedstoneAware {
  override def createBlockState() = new ExtendedBlockState(this, Array.empty, Array(NetSplitter.OpenSides))

  override def getExtendedState(state: IBlockState, world: IBlockAccess, pos: BlockPos): IBlockState =
    (state, world.getTileEntity(pos)) match {
      case (extendedState: IExtendedBlockState, t: tileentity.NetSplitter) =>
        extendedState.withProperty(NetSplitter.OpenSides, EnumFacing.values().map(t.isSideOpen))
      case _ => state
    }

  // ----------------------------------------------------------------------- //

  override def isSideSolid(state: IBlockState, world: IBlockAccess, pos: BlockPos, side: EnumFacing): Boolean = false

  // ----------------------------------------------------------------------- //

  override def createNewTileEntity(world: World, meta: Int) = new tileentity.NetSplitter()

  // ----------------------------------------------------------------------- //

  // NOTE: must not be final for immibis microblocks to work.
  override def onBlockActivated(world: World, pos: BlockPos, state: IBlockState, player: EntityPlayer, hand: EnumHand, side: EnumFacing, hitX: Float, hitY: Float, hitZ: Float): Boolean = {
    if (Wrench.holdsApplicableWrench(player, pos)) {
      val sideToToggle = if (player.isSneaking) side.getOpposite else side
      world.getTileEntity(pos) match {
        case splitter: tileentity.NetSplitter =>
          if (!world.isRemote) {
            val oldValue = splitter.openSides(sideToToggle.ordinal())
            splitter.setSideOpen(sideToToggle, !oldValue)
          }
          true
        case _ => false
      }
    }
    else super.onBlockActivated(world, pos, state, player, hand, side, hitX, hitY, hitZ)
  }
}

object NetSplitter {
  object OpenSides extends IUnlistedProperty[Array[Boolean]]{
    override def getName: String = "open_sides"

    override def isValid(value: Array[Boolean]): Boolean = value != null && value.length == EnumFacing.values().length

    override def getType: Class[Array[Boolean]] = classOf[Array[Boolean]]

    override def valueToString(value: Array[Boolean]): String = value.toString
  }
}

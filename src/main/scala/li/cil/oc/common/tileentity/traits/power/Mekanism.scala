package li.cil.oc.common.tileentity.traits.power

import li.cil.oc.common.asm.Injectable
import li.cil.oc.integration.Mods
import li.cil.oc.integration.util.Power
import net.minecraft.util.EnumFacing
import net.minecraftforge.fml.common.Optional

@Injectable.InterfaceList(Array(
  new Injectable.Interface(value = "mekanism.api.energy.IStrictEnergyAcceptor", modid = Mods.IDs.Mekanism),
  new Injectable.Interface(value = "mekanism.api.energy.IStrictEnergyStorage", modid = Mods.IDs.Mekanism)
))
trait Mekanism extends Common {
  @Optional.Method(modid = Mods.IDs.Mekanism)
  def canReceiveEnergy(side: EnumFacing):Boolean = Mods.Mekanism.isModAvailable && canConnectPower(side)

  @Optional.Method(modid = Mods.IDs.Mekanism)
  def acceptEnergy(side: EnumFacing, amount: Double, simulate: Boolean): Double =
    if (!Mods.Mekanism.isModAvailable) 0D
    else Power.toJoules(tryChangeBuffer(side, Power.fromJoules(amount), !simulate))

  @Optional.Method(modid = Mods.IDs.Mekanism)
  def getMaxEnergy: Double = Power.toJoules(EnumFacing.values.map(globalBufferSize).max)

  @Optional.Method(modid = Mods.IDs.Mekanism)
  def getEnergy: Double = Power.toJoules(EnumFacing.values.map(globalBuffer).max)

  @Optional.Method(modid = Mods.IDs.Mekanism)
  def setEnergy(energy: Double) = Unit
}
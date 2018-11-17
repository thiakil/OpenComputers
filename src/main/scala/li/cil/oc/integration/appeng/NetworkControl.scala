package li.cil.oc.integration.appeng

import java.util

import appeng.api.AEApi
import appeng.api.config.Actionable
import appeng.api.networking.IGridHost
import appeng.api.networking.crafting.{ICraftingLink, ICraftingRequester}
import appeng.api.networking.security.IActionHost
import appeng.api.storage.data.IAEItemStack
import appeng.api.util.AEPartLocation
import com.google.common.collect.ImmutableSet
import li.cil.oc.OpenComputers
import li.cil.oc.api.machine.Arguments
import li.cil.oc.api.machine.Callback
import li.cil.oc.api.machine.Context
import li.cil.oc.api.network.Node
import li.cil.oc.api.prefab.AbstractValue
import li.cil.oc.common.EventHandler
import li.cil.oc.server.driver.Registry
import li.cil.oc.util.DatabaseAccess
import li.cil.oc.util.ExtendedArguments._
import li.cil.oc.util.ExtendedNBT._
import li.cil.oc.util.ResultWrapper._
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.tileentity.TileEntity
import net.minecraft.util.math.BlockPos
import net.minecraftforge.common.DimensionManager
import net.minecraftforge.common.util.Constants.NBT

import scala.collection.convert.WrapAsJava._
import scala.collection.convert.WrapAsScala._
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.existentials

// Note to self: this class is used by ExtraCells (and potentially others), do not rename / drastically change it.
trait NetworkControl[AETile >: Null <: TileEntity with IActionHost with IGridHost] {
  def tile: AETile
  def pos: AEPartLocation

  def node: Node

  private def aePotentialItem(aeItem: IAEItemStack): IAEItemStack = {
    if (aeItem.getStackSize > 0 || !aeItem.isCraftable)
      aeItem
    else
      aeItem.copy().setStackSize(1)
  }

  private def isSequentialTable(map: scala.collection.mutable.HashMap[_, _]): Boolean = {
    // if this element has n=number, it is the table wrapping showing how large the array is
    map.forall {
      case (key: String, _: Number) => key == "n"
      case (key: Number, _: AnyRef) => key.intValue >= 1
      case _ => false
    }
  }

  private def reduceSequentialTable(map: scala.collection.mutable.HashMap[_, _]): AnyRef = {
    // in place of a table pack, we want a hash map of tuples
    val tuples = new util.LinkedList[AnyRef]()
    map.collect {
      case (key: AnyRef, value: AnyRef) =>
        if (!key.isInstanceOf[String] || key.asInstanceOf[String] != "n") {
          tuples.add(reduceLuaValue(value))
        }
    }
    tuples.toArray
  }

  private def reduceHashTable(map: scala.collection.mutable.HashMap[_, _]): AnyRef = {
    val hash = new java.util.HashMap[AnyRef, AnyRef]()
    map.foreach {
      case (key: AnyRef, value: AnyRef) => {
        hash += key -> value
      }
    }
    hash
  }

  private def reduceLuaValue(any: AnyRef): AnyRef = {
    any match {
      case m: scala.collection.mutable.HashMap[_, _] =>
        if (isSequentialTable(m))
          reduceSequentialTable(m)
        else
          reduceHashTable(m)
      case _ => any
    }
  }

  private def getFilter(args: Arguments, index: Int): java.util.Map[AnyRef, AnyRef] = {
    val hash = new java.util.HashMap[AnyRef, AnyRef]()
    Registry.convert(Array[AnyRef](args.optTable (index, Map.empty[AnyRef, AnyRef] )))
      .head match {
        case map: mutable.Map[_, _] => map.collect {
          case (key: AnyRef, value: AnyRef) => hash += reduceLuaValue(key) -> reduceLuaValue(value)
        }
        case _ =>
      }
    hash
  }

  private def allItems: Iterable[IAEItemStack] = AEUtil.getGridStorage(tile.getGridNode(pos).getGrid).getInventory(AEUtil.itemStorageChannel).getStorageList
  private def allCraftables: Iterable[IAEItemStack] = allItems.filter(_.isCraftable)

  private def convert(aeItem: IAEItemStack): java.util.Map[AnyRef, AnyRef] = {
    // I would prefer to move the convert code to the registry for IAEItemStack
    // but craftables need the device that crafts them
    val hash = new java.util.HashMap[AnyRef, AnyRef]()
    Registry.convert(Array[AnyRef](aePotentialItem(aeItem).createItemStack()))
      .head
      .asInstanceOf[java.util.Map[Object, Object]]
      .collect {
      case (key, value) => hash += key -> value
    }
    hash.update("isCraftable", Boolean.box(aeItem.isCraftable))
    hash.update("size", Int.box(aeItem.getStackSize.toInt))
    hash
  }

  @Callback(doc = "function():table -- Get a list of tables representing the available CPUs in the network.")
  def getCpus(context: Context, args: Arguments): Array[AnyRef] = {
    val buffer = new mutable.ListBuffer[Map[String, Any]]
    AEUtil.getGridCrafting(tile.getGridNode(pos).getGrid).getCpus.foreach(cpu => {
      buffer.append(Map(
        "name" -> cpu.getName,
        "storage" -> cpu.getAvailableStorage,
        "coprocessors" -> cpu.getCoProcessors,
        "busy" -> cpu.isBusy))
    })
    result(buffer.toArray)
  }

  @Callback(doc = "function([filter:table]):table -- Get a list of known item recipes. These can be used to issue crafting requests.")
  def getCraftables(context: Context, args: Arguments): Array[AnyRef] = {
    val filter = getFilter(args, 0)
    result(allCraftables
      .collect{ case aeCraftItem if matches(convert(aeCraftItem), filter) => new NetworkControl.Craftable(tile, pos, aeCraftItem) }
      .toArray)
  }

  @Callback(doc = "function([filter:table]):table -- Get a list of the stored items in the network.")
  def getItemsInNetwork(context: Context, args: Arguments): Array[AnyRef] = {
    val filter = getFilter(args, 0)
    result(allItems
      .map(convert)
      .filter(matches(_, filter))
      .toArray)
  }

  @Callback(doc = "function([filter:table,] [dbAddress:string,] [startSlot:number,] [count:number]): bool -- Store items in the network matching the specified filter in the database with the specified address.")
  def store(context: Context, args: Arguments): Array[AnyRef] = {
    val filter = getFilter(args, 0)
    val database = args.optString(1, null) match {
      case address: String => DatabaseAccess.database(node, address)
      case _ => DatabaseAccess.databases(node).headOption.getOrElse(throw new IllegalArgumentException("no database upgrade found"))
    }
    val items = allItems.collect{ case aeItem if matches(convert(aeItem), filter) => aePotentialItem(aeItem)}.toArray
    val offset = args.optSlot(database.data, 2, 0)
    val count = args.optInteger(3, Int.MaxValue) min (database.size - offset) min items.length
    var slot = offset
    for (i <- 0 until count) {
      val stack = Option(items(i)).map(_.createItemStack).orNull
      while (!database.getStackInSlot(slot).isEmpty && slot < database.size) slot += 1
      if (database.getStackInSlot(slot).isEmpty) {
        database.setStackInSlot(slot, stack)
      }
    }
    result(true)
  }

  @Callback(doc = "function():table -- Get a list of the stored fluids in the network.")
  def getFluidsInNetwork(context: Context, args: Arguments): Array[AnyRef] =
    result(AEUtil.getGridStorage(tile.getGridNode(pos).getGrid).getInventory(AEUtil.fluidStorageChannel).getStorageList.filter(stack =>
      stack != null).
        map(_.getFluidStack).toArray)

  @Callback(doc = "function():number -- Get the average power injection into the network.")
  def getAvgPowerInjection(context: Context, args: Arguments): Array[AnyRef] =
    result(AEUtil.getGridEnergy(tile.getGridNode(pos).getGrid).getAvgPowerInjection)

  @Callback(doc = "function():number -- Get the average power usage of the network.")
  def getAvgPowerUsage(context: Context, args: Arguments): Array[AnyRef] =
    result(AEUtil.getGridEnergy(tile.getGridNode(pos).getGrid).getAvgPowerUsage)

  @Callback(doc = "function():number -- Get the idle power usage of the network.")
  def getIdlePowerUsage(context: Context, args: Arguments): Array[AnyRef] =
    result(AEUtil.getGridEnergy(tile.getGridNode(pos).getGrid).getIdlePowerUsage)

  @Callback(doc = "function():number -- Get the maximum stored power in the network.")
  def getMaxStoredPower(context: Context, args: Arguments): Array[AnyRef] =
    result(AEUtil.getGridEnergy(tile.getGridNode(pos).getGrid).getMaxStoredPower)

  @Callback(doc = "function():number -- Get the stored power in the network. ")
  def getStoredPower(context: Context, args: Arguments): Array[AnyRef] =
    result(AEUtil.getGridEnergy(tile.getGridNode(pos).getGrid).getStoredPower)

  private def matches(stack: java.util.Map[AnyRef, AnyRef], filter: scala.collection.mutable.Map[AnyRef, AnyRef]): Boolean = {
    if (stack == null) return false
    filter.forall {
      case (key: AnyRef, value: AnyRef) => contains(stack, key, value)
      case _ => false
    }
  }

  private def contains(stack: java.util.Map[AnyRef, AnyRef], key: AnyRef, value: AnyRef): Boolean = {
    stack.containsKey(key) && valueMatch(value, stack.get(key))
  }

  private def valueMatch(a: AnyRef, b: AnyRef): Boolean = {
    if ((a == null) != (b == null)) return false
    if (a == b) return true
    (a, b) match {
      case (a_number: Number, b_number: Number) => a_number.intValue == b_number.intValue
      case (a_vec: Array[_], b_vec: Array[_]) => a_vec.forall {
        case a_map: util.HashMap[_, _] => a_map.forall {
          case (a_key: AnyRef, a_value: AnyRef) => b_vec.collectFirst[Boolean] {
            case b_map: scala.collection.mutable.HashMap[_, _] => b_map.collectFirst[Boolean] {
              case (b_key: AnyRef, b_value: AnyRef) => valueMatch(a_key, b_key) && valueMatch(a_value, b_value)
            }.isDefined
          }.isDefined
          case _ => false
        }
      }
      case _ => false
    }
  }
}

object NetworkControl {

  class Craftable(var controller: TileEntity with IActionHost with IGridHost, var pos: AEPartLocation, var stack: IAEItemStack) extends AbstractValue with ICraftingRequester {
    def this() = this(null, null, null)

    private val links = mutable.Set.empty[ICraftingLink]

    if (stack != null && stack.getStackSize < 1){
      stack = stack.copy()
      stack.setStackSize(1)
    }

    // ----------------------------------------------------------------------- //

    override def getRequestedJobs = ImmutableSet.copyOf(links.toIterable)

    override def jobStateChange(link: ICraftingLink) {
      links -= link
    }

    // rv1
    def injectCratedItems(link: ICraftingLink, stack: IAEItemStack, p3: Actionable) = stack

    // rv2
    def injectCraftedItems(link: ICraftingLink, stack: IAEItemStack, p3: Actionable) = stack

    override def getActionableNode = controller.getActionableNode

    //override def getCableConnectionType(side: AEPartLocation) = controller.getCableConnectionType(side)

    //override def securityBreak() = controller.securityBreak()

    //override def getGridNode(side: AEPartLocation) = controller.getGridNode(side)

    // ----------------------------------------------------------------------- //

    @Callback(doc = "function():table -- Returns the item stack representation of the crafting result.")
    def getItemStack(context: Context, args: Arguments): Array[AnyRef] = Array(aeCraftItem(stack).createItemStack())

    @Callback(doc = "function([amount:int[, prioritizePower:boolean[, cpuName:string]]]):userdata -- Requests the item to be crafted, returning an object that allows tracking the crafting status.")
    def request(context: Context, args: Arguments): Array[AnyRef] = {
      if (controller == null || controller.isInvalid) {
        return result(Unit, "no controller")
      }

      val count = args.optInteger(0, 1)
      val request = stack.copy
      request.setStackSize(count)

      val craftingGrid = AEUtil.getGridCrafting(controller.getGridNode(pos).getGrid)
      val source = new MachineSource(controller)
      val future = craftingGrid.beginCraftingJob(controller.getWorld, controller.getGridNode(pos).getGrid, source, request, null)
      val prioritizePower = args.optBoolean(1, true)
      val cpuName = args.optString(2, "")
      val cpu = if (!cpuName.isEmpty()) {
        craftingGrid.getCpus.collectFirst({
          case c if cpuName.equals(c.getName()) => c
        }).orNull
      } else null

      val status = new CraftingStatus()
      Future {
        try {
          val job = future.get() // Make 100% sure we wait for this outside the scheduled closure.
          EventHandler.scheduleServer(() => {
            val link = craftingGrid.submitJob(job, Craftable.this, cpu, prioritizePower, source)
            if (link != null) {
              status.setLink(link)
              links += link
            }
            else {
              status.fail("missing resources?")
            }
          })
        }
        catch {
          case e: Exception =>
            OpenComputers.log.debug("Error submitting job to AE2.", e)
            status.fail(e.toString)
        }
      }

      result(status)
    }

    // ----------------------------------------------------------------------- //

    override def load(nbt: NBTTagCompound) {
      super.load(nbt)
      stack = AEUtil.itemStorageChannel.createStack(new ItemStack(nbt))
      if (nbt.hasKey("dimension")) {
        val dimension = nbt.getInteger("dimension")
        val x = nbt.getInteger("x")
        val y = nbt.getInteger("y")
        val z = nbt.getInteger("z")
        EventHandler.scheduleServer(() => {
          val world = DimensionManager.getWorld(dimension)
          val tileEntity = world.getTileEntity(new BlockPos(x, y, z))
          if (tileEntity != null && tileEntity.isInstanceOf[TileEntity with IActionHost with IGridHost]) {
            controller = tileEntity.asInstanceOf[TileEntity with IActionHost with IGridHost]
          }
        })
      }
      links ++= nbt.getTagList("links", NBT.TAG_COMPOUND).map(
        (nbt: NBTTagCompound) => AEApi.instance.storage.loadCraftingLink(nbt, this))
    }

    override def save(nbt: NBTTagCompound) {
      super.save(nbt)
      stack.createItemStack().writeToNBT(nbt)
      if (controller != null && !controller.isInvalid) {
        nbt.setInteger("dimension", controller.getWorld.provider.getDimension)
        nbt.setInteger("x", controller.getPos.getX)
        nbt.setInteger("y", controller.getPos.getY)
        nbt.setInteger("z", controller.getPos.getZ)
      }
      nbt.setNewTagList("links", links.map((link) => {
        val comp = new NBTTagCompound()
        link.writeToNBT(comp)
        comp
      }))
    }

    private def aeCraftItem(aeItem: IAEItemStack): IAEItemStack = {
      val patterns = AEUtil.getGridCrafting(controller.getGridNode(pos).getGrid).getCraftingFor(aeItem, null, 0, controller.getWorld)
      patterns.find(pattern => pattern.getOutputs.exists(_.isSameType(aeItem))) match {
        case Some(pattern) => pattern.getOutputs.find(_.isSameType(aeItem)).get
        case _ => aeItem.copy.setStackSize(0) // Should not be possible, but hey...
      }
    }
  }

  class CraftingStatus extends AbstractValue {
    private var isComputing = true
    private var link: Option[ICraftingLink] = None
    private var failed = false
    private var reason = "no link"

    def setLink(value: ICraftingLink) {
      isComputing = false
      link = Option(value)
    }

    def fail(reason: String) {
      isComputing = false
      failed = true
      this.reason = s"request failed ($reason)"
    }

    @Callback(doc = "function():boolean -- Get whether the crafting request has been canceled.")
    def isCanceled(context: Context, args: Arguments): Array[AnyRef] = {
      if (isComputing) return result(false, "computing")
      link.fold(result(failed, reason))(l => result(l.isCanceled))
    }

    @Callback(doc = "function():boolean -- Get whether the crafting request is done.")
    def isDone(context: Context, args: Arguments): Array[AnyRef] = {
      if (isComputing) return result(false, "computing")
      link.fold(result(!failed, reason))(l => result(l.isDone))
    }

    override def save(nbt: NBTTagCompound) {
      super.save(nbt)
      failed = link.fold(true)(!_.isDone)
      nbt.setBoolean("failed", failed)
      if (failed && reason != null) {
        nbt.setString("reason", reason)
      }
    }

    override def load(nbt: NBTTagCompound) {
      super.load(nbt)
      isComputing = false
      failed = nbt.getBoolean("failed")
      if (failed && nbt.hasKey("reason")) {
        reason = nbt.getString("reason")
      }
    }
  }

}
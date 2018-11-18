package li.cil.oc.integration.appeng

import java.util

import appeng.api.storage.data.{IAEFluidStack, IAEItemStack, IAEStack}
import li.cil.oc.api.driver.Converter
import li.cil.oc.server.driver.Registry

import scala.collection.convert.WrapAsScala._

object ConverterAEStack extends Converter{
  override def convert(value: Any, output: util.Map[AnyRef, AnyRef]): Unit = {
    value match {
      case aeStack: IAEStack[_] =>
        aeStack match {
          case item: IAEItemStack =>
            output.put("aeStackType", "item")
            convertDefinition(item.getDefinition, output)
          case fluid: IAEFluidStack =>
            output.put("aeStackType", "fluid")
            convertDefinition(fluid.getFluidStack, output)
          case _ => if (!output.containsKey("aeStackType")) output.put("aeStackType", "unknown")
        }
        output.put("size", Long.box(aeStack.getStackSize))
        output.put("countRequestable", Long.box(aeStack.getCountRequestable))
        output.put("isCraftable", Boolean.box(aeStack.isCraftable))
      case _ => //nope
    }
  }

  def convert(value: Any): util.Map[AnyRef, AnyRef] = {
    val map = new util.HashMap[AnyRef, AnyRef]()
    convert(value, map)
    map
  }

  private def convertDefinition(definition: AnyRef, output: util.Map[AnyRef,AnyRef]): Unit = {
    Registry.convert(Array[AnyRef](definition))
      .head
      .asInstanceOf[java.util.Map[AnyRef, AnyRef]]
      .collect {
        case (key, value) => output += key -> value
      }
  }
}
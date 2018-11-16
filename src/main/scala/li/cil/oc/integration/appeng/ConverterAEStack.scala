package li.cil.oc.integration.appeng

import java.util

import appeng.api.storage.data.{IAEFluidStack, IAEItemStack, IAEStack}
import li.cil.oc.api.driver.Converter
import li.cil.oc.server.driver.Registry

class ConverterAEStack extends Converter{
  override def convert(value: Any, output: util.Map[AnyRef, AnyRef]): Unit = {
    value match {
      case aeStack: IAEStack[_] =>
        output.put("stackSize", aeStack.getStackSize.asInstanceOf[AnyRef])
        output.put("countRequestable", aeStack.getCountRequestable.asInstanceOf[AnyRef])
        output.put("craftable", aeStack.isCraftable.asInstanceOf[AnyRef])

        aeStack match {
          case item: IAEItemStack =>
            output.put("type", "item")
            output.put("definition", Registry.convertRecursively(item.getDefinition, new util.IdentityHashMap()))
          case fluid: IAEFluidStack =>
            output.put("type", "fluid")
            output.put("definition", Registry.convertRecursively(fluid.getFluidStack, new util.IdentityHashMap()))
          case _ => if (!output.containsKey("type")) output.put("type", "unknown")
        }
      case _ => //nope
    }
  }
}

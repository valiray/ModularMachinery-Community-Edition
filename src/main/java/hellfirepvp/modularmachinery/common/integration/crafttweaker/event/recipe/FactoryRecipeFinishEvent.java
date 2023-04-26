package hellfirepvp.modularmachinery.common.integration.crafttweaker.event.recipe;

import crafttweaker.annotations.ZenRegister;
import hellfirepvp.modularmachinery.common.integration.crafttweaker.IMachineController;
import hellfirepvp.modularmachinery.common.machine.factory.FactoryRecipeThread;
import stanhebben.zenscript.annotations.ZenClass;

@ZenRegister
@ZenClass("mods.modularmachinery.FactoryRecipeFinishEvent")
public class FactoryRecipeFinishEvent extends FactoryRecipeEvent {
    public FactoryRecipeFinishEvent(FactoryRecipeThread recipeThread, IMachineController controller) {
        super(recipeThread, recipeThread.getActiveRecipe(), controller);
    }
}

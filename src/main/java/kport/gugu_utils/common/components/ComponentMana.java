package kport.gugu_utils.common.components;

import hellfirepvp.modularmachinery.common.crafting.ComponentType;

import javax.annotation.Nullable;

public class ComponentMana extends ComponentType {

    @Nullable
    @Override
    public String requiresModid() {
        return "botania";
    }
}
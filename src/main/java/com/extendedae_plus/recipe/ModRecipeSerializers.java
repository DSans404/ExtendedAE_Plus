package com.extendedae_plus.recipe;

import com.extendedae_plus.ExtendedAEPlus;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, ExtendedAEPlus.MODID);
    private ModRecipeSerializers() {
    }
}

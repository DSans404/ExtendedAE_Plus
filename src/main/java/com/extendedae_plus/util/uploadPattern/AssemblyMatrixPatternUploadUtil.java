package com.extendedae_plus.util.uploadPattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.extendedae_plus.content.matrix.PatternCorePlusBlockEntity;
import com.extendedae_plus.content.matrix.UploadCoreBlockEntity;
import com.extendedae_plus.menu.locator.CuriosItemLocator;
import com.extendedae_plus.mixin.ae2.accessor.PatternEncodingTermMenuAccessor;
import com.extendedae_plus.util.wireless.WirelessTerminalLocator;
import com.glodblock.github.extendedae.common.me.matrix.ClusterAssemblerMatrix;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixPattern;
import de.mari_023.ae2wtlib.api.registration.WTDefinition;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Auto-upload support that targets Assembly Matrix clusters containing an Upload Core. */
public final class AssemblyMatrixPatternUploadUtil {
    private AssemblyMatrixPatternUploadUtil() {
    }

    public static boolean uploadFromEncodingMenuToMatrix(ServerPlayer player, PatternEncodingTermMenu menu) {
        if (player == null || menu == null) {
            return false;
        }

        var encodedSlot = ((PatternEncodingTermMenuAccessor) (Object) menu).eap$getEncodedPatternSlot();
        ItemStack pattern = encodedSlot.getItem();
        if (!isSupportedPattern(player, pattern)) {
            return false;
        }

        IGrid grid = null;
        try {
            if (menu instanceof AEBaseMenu baseMenu
                    && baseMenu.getTarget() instanceof IActionHost host
                    && host.getActionableNode() != null) {
                grid = host.getActionableNode().getGrid();
            }
        } catch (Throwable ignored) {
        }
        if (grid == null) {
            return false;
        }

        if (matrixContainsPattern(grid, pattern)) {
            ItemStack blanks = AEItems.BLANK_PATTERN.stack(pattern.getCount());
            try {
                var blankSlot = ((PatternEncodingTermMenuAccessor) (Object) menu).eap$getBlankPatternSlot();
                ItemStack remainder = blankSlot != null && blankSlot.mayPlace(blanks)
                        ? blankSlot.safeInsert(blanks)
                        : blanks;
                if (!remainder.isEmpty()) {
                    player.getInventory().placeItemBackInInventory(remainder, false);
                }
            } catch (Throwable ignored) {
                player.getInventory().placeItemBackInInventory(blanks, false);
            }
            encodedSlot.set(ItemStack.EMPTY);
            return false;
        }

        for (MatrixInventoryTarget target : findMatrixPatternInventories(grid)) {
            ItemStack remainder = target.insertInventory().addItems(pattern.copy());
            int inserted = pattern.getCount() - remainder.getCount();
            if (inserted > 0) {
                pattern.shrink(inserted);
                if (pattern.isEmpty()) {
                    encodedSlot.set(ItemStack.EMPTY);
                }
                return true;
            }
        }
        return false;
    }

    public static boolean uploadPatternToMatrix(ServerPlayer player, ItemStack pattern, IGrid grid) {
        if (player == null || grid == null || !isSupportedPattern(player, pattern)
                || matrixContainsPattern(grid, pattern)) {
            return false;
        }

        for (MatrixInventoryTarget target : findMatrixPatternInventories(grid)) {
            ItemStack toInsert = pattern.copy();
            ItemStack remainder = target.insertInventory().addItems(toInsert);
            if (remainder.getCount() < toInsert.getCount()) {
                return true;
            }
        }
        return false;
    }

    public static IGrid findPlayerGrid(ServerPlayer player) {
        WirelessTerminalLocator.LocatedTerminal located = WirelessTerminalLocator.find(player);
        ItemStack terminal = located.stack;
        if (terminal.isEmpty()) {
            return null;
        }

        if (terminal.getItem() instanceof WirelessTerminalItem wirelessTerminal) {
            return wirelessTerminal.getLinkedGrid(terminal, player.serverLevel(), null);
        }

        String curiosSlotId = located.getCuriosSlotId();
        int curiosIndex = located.getCuriosIndex();
        if (curiosSlotId == null || curiosIndex < 0) {
            return null;
        }
        try {
            WTDefinition definition = WTDefinition.ofOrNull(terminal);
            if (definition == null) {
                return null;
            }
            WTMenuHost host = definition.wTMenuHostFactory().create(
                    definition.item(), player, new CuriosItemLocator(curiosSlotId, curiosIndex), (p, sub) -> {
                    });
            return host != null && host.getActionableNode() != null ? host.getActionableNode().getGrid() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSupportedPattern(ServerPlayer player, ItemStack pattern) {
        if (pattern == null || pattern.isEmpty() || !PatternDetailsHelper.isEncodedPattern(pattern)) {
            return false;
        }
        IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, player.level());
        return details instanceof AECraftingPattern
                || details instanceof AESmithingTablePattern
                || details instanceof AEStonecuttingPattern;
    }

    private static List<MatrixInventoryTarget> findMatrixPatternInventories(IGrid grid) {
        List<MatrixInventoryTarget> result = new ArrayList<>();
        if (grid == null) {
            return result;
        }
        try {
            Set<ClusterAssemblerMatrix> acceptedClusters = new HashSet<>();
            for (TileAssemblerMatrixPattern tile : grid.getMachines(TileAssemblerMatrixPattern.class)) {
                addMatrixInventory(result, acceptedClusters, tile);
            }
            for (PatternCorePlusBlockEntity tile : grid.getMachines(PatternCorePlusBlockEntity.class)) {
                addMatrixInventory(result, acceptedClusters, tile);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static void addMatrixInventory(List<MatrixInventoryTarget> result,
                                           Set<ClusterAssemblerMatrix> acceptedClusters,
                                           TileAssemblerMatrixPattern tile) {
        if (tile == null || !tile.isFormed() || !tile.getMainNode().isActive()) {
            return;
        }
        ClusterAssemblerMatrix cluster = tile.getCluster();
        if (cluster == null || (!acceptedClusters.contains(cluster) && !hasUploadCore(cluster))) {
            return;
        }
        acceptedClusters.add(cluster);
        InternalInventory insertInventory = tile.getExposedInventory();
        InternalInventory patternInventory = tile.getTerminalPatternInventory();
        if (insertInventory != null && patternInventory != null) {
            result.add(new MatrixInventoryTarget(insertInventory, patternInventory));
        }
    }

    private static boolean hasUploadCore(ClusterAssemblerMatrix cluster) {
        try {
            var iterator = cluster.getBlockEntities();
            while (iterator.hasNext()) {
                if (iterator.next() instanceof UploadCoreBlockEntity) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean matrixContainsPattern(IGrid grid, ItemStack pattern) {
        CustomData defaultData = CustomData.of(new CompoundTag());
        ItemStack expected = withoutEncodePlayer(pattern, defaultData);
        for (MatrixInventoryTarget target : findMatrixPatternInventories(grid)) {
            InternalInventory inventory = target.patternInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack existing = withoutEncodePlayer(inventory.getStackInSlot(slot), defaultData);
                if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ItemStack withoutEncodePlayer(ItemStack stack, CustomData defaultData) {
        ItemStack copy = stack.copy();
        CompoundTag tag = copy.getOrDefault(DataComponents.CUSTOM_DATA, defaultData).copyTag();
        tag.remove("encodePlayer");
        copy.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return copy;
    }

    private record MatrixInventoryTarget(InternalInventory insertInventory, InternalInventory patternInventory) {
    }
}

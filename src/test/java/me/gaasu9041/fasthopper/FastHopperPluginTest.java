package me.gaasu9041.fasthopper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

public class FastHopperPluginTest {

  @Test
  public void clampTransferAmountLimitsValues() {
    assertEquals(1, FastHopperPlugin.clampTransferAmount(0));
    assertEquals(5, FastHopperPlugin.clampTransferAmount(5));
    assertEquals(64, FastHopperPlugin.clampTransferAmount(128));
  }

  @Test
  public void clampTransferTicksLimitsValues() {
    assertEquals(1, FastHopperPlugin.clampTransferTicks(0));
    assertEquals(8, FastHopperPlugin.clampTransferTicks(8));
  }

  @Test
  public void shulkerBoxesCannotEnterShulkerBoxes() {
    assertTrue(FastHopperPlugin.isShulkerBox(Material.SHULKER_BOX));
    assertTrue(FastHopperPlugin.isShulkerBox(Material.PURPLE_SHULKER_BOX));
  }

  @Test
  public void otherInventoryMovesRemainAllowed() {
    assertFalse(FastHopperPlugin.isShulkerBox(Material.STONE));
  }

  @Test
  public void batchingOnlyUsesSimpleStorageInventories() {
    assertTrue(FastHopperPlugin.isSafeBatchInventory(InventoryType.CHEST));
    assertTrue(FastHopperPlugin.isSafeBatchInventory(InventoryType.HOPPER));
    assertFalse(FastHopperPlugin.isSafeBatchInventory(InventoryType.FURNACE));
    assertFalse(FastHopperPlugin.isSafeBatchInventory(InventoryType.CHISELED_BOOKSHELF));
  }

  @Test
  public void insertedSlotMustContainExactlyTheVanillaMove() {
    ItemStack sample = new ItemStack(Material.STONE, 1);
    ItemStack[] before = new ItemStack[] {null, null};

    assertEquals(
        0,
        FastHopperPlugin.findInsertedSlot(
            before, new ItemStack[] {new ItemStack(Material.STONE, 1), null}, sample));
    assertEquals(
        -1,
        FastHopperPlugin.findInsertedSlot(
            before, new ItemStack[] {new ItemStack(Material.STONE, 2), null}, sample));
    assertEquals(
        -1,
        FastHopperPlugin.findInsertedSlot(
            before,
            new ItemStack[] {
              new ItemStack(Material.STONE, 1), new ItemStack(Material.STONE, 1)
            },
            sample));
  }

  @Test
  public void takingSimilarItemsConservesOtherStacks() {
    ItemStack[] contents =
        new ItemStack[] {
          new ItemStack(Material.STONE, 2),
          new ItemStack(Material.DIRT, 4),
          new ItemStack(Material.STONE, 5)
        };

    assertEquals(
        5,
        FastHopperPlugin.takeSimilarItems(
            contents, new ItemStack(Material.STONE, 1), 5));
    assertNull(contents[0]);
    assertEquals(4, contents[1].getAmount());
    assertEquals(2, contents[2].getAmount());
  }
}

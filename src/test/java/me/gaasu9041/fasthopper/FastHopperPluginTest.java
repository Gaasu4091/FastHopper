package me.gaasu9041.fasthopper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
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
}

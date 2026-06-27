package me.gaasu9041.fasthopper;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class FastHopperPlugin extends JavaPlugin
    implements Listener, CommandExecutor, TabCompleter {
  private static final int defaultTransferAmount = 5;
  private static final int defaultTransferTicks = 8;
  private static final int minTransferAmount = 1;
  private static final int minTransferTicks = 1;
  private static final int maxTransferAmountLimit = 64;
  private static final String commandName = "fasthopper";
  private static final String adminPermission = "fasthopper.admin";
  private static final String transferAmountPath = "max-transfer-amount";
  private static final String transferTicksPath = "hopper-transfer-ticks";

  private volatile int maxTransferAmount = defaultTransferAmount;
  private volatile int hopperTransferTicks = defaultTransferTicks;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    reloadSettings();
    registerCommand();
    getServer().getPluginManager().registerEvents(this, this);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void preventShulkerBoxNesting(InventoryMoveItemEvent event) {
    if (!isHopperInventory(event.getInitiator())) {
      return;
    }

    if (isShulkerBoxInventory(event.getDestination())
        && isShulkerBox(event.getItem().getType())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInventoryMoveItem(InventoryMoveItemEvent event) {
    if (!isHopperInventory(event.getInitiator())) {
      return;
    }

    ItemStack movedItem = event.getItem();
    if (movedItem == null || movedItem.getType().isAir()) {
      return;
    }

    int extraAmount = Math.max(0, maxTransferAmount - movedItem.getAmount());
    Inventory initiator = event.getInitiator();
    Location location = initiator.getLocation();
    if (location == null) {
      return;
    }

    Inventory source = event.getSource();
    Inventory destination = event.getDestination();
    ItemStack sampleItem = movedItem.clone();
    List<InventoryBlock> sourceBlocks = getInventoryBlocks(source);
    List<InventoryBlock> destinationBlocks = getInventoryBlocks(destination);
    boolean canBatchTransfer =
        isSafeBatchInventory(source.getType())
            && isSafeBatchInventory(destination.getType())
            && !sourceBlocks.isEmpty()
            && !destinationBlocks.isEmpty();
    ItemStack[] destinationBefore =
        canBatchTransfer ? copyStorageContents(destination) : new ItemStack[0];
    getServer()
        .getRegionScheduler()
        .runDelayed(
            this,
            location,
            task -> {
              if (!setTransferCooldown(initiator)) {
                return;
              }
              if (canBatchTransfer
                  && areInventoryBlocksAvailable(sourceBlocks)
                  && areInventoryBlocksAvailable(destinationBlocks)) {
                transferItems(
                    source, destination, sampleItem, destinationBefore, extraAmount);
              }
            },
            1L);
  }

  @Override
  public synchronized boolean onCommand(
      CommandSender sender, Command command, String label, String[] args) {
    if (!commandName.equalsIgnoreCase(command.getName())) {
      return false;
    }

    if (!sender.hasPermission(adminPermission)) {
      sender.sendMessage("You do not have permission to use this command.");
      return true;
    }

    if (args.length == 0) {
      sendStatus(sender);
      return true;
    }

    String subcommand = args[0];
    if ("transfar".equalsIgnoreCase(subcommand)) {
      return handleTransferCommand(sender, args);
    }

    if ("tick".equalsIgnoreCase(subcommand)) {
      return handleTickCommand(sender, args);
    }

    if ("reload".equalsIgnoreCase(subcommand)) {
      reloadConfig();
      reloadSettings();
      sendStatus(sender);
      return true;
    }

    sendUsage(sender, label);
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (!commandName.equalsIgnoreCase(command.getName())) {
      return List.of();
    }

    if (args.length == 1) {
      List<String> suggestions = new ArrayList<>();
      String prefix = args[0].toLowerCase();

      if ("transfar".startsWith(prefix)) {
        suggestions.add("transfar");
      }
      if ("tick".startsWith(prefix)) {
        suggestions.add("tick");
      }
      if ("reload".startsWith(prefix)) {
        suggestions.add("reload");
      }
      return suggestions;
    }

    if (args.length == 2) {
      if ("transfar".equalsIgnoreCase(args[0])) {
        return List.of(String.valueOf(maxTransferAmount));
      }
      if ("tick".equalsIgnoreCase(args[0])) {
        return List.of(String.valueOf(hopperTransferTicks));
      }
    }

    return List.of();
  }

  static int clampTransferAmount(int amount) {
    return Math.max(minTransferAmount, Math.min(maxTransferAmountLimit, amount));
  }

  static int clampTransferTicks(int ticks) {
    return Math.max(minTransferTicks, ticks);
  }

  static boolean isShulkerBox(Material material) {
    return material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX");
  }

  static boolean isSafeBatchInventory(InventoryType type) {
    return switch (type) {
      case BARREL, CHEST, DISPENSER, DROPPER, HOPPER, SHULKER_BOX -> true;
      default -> false;
    };
  }

  static int findInsertedSlot(ItemStack[] before, ItemStack[] after, ItemStack sampleItem) {
    if (before.length != after.length) {
      return -1;
    }

    int changedSlot = -1;
    for (int slot = 0; slot < after.length; slot++) {
      ItemStack currentItem = after[slot];
      if (currentItem == null || !currentItem.isSimilar(sampleItem)) {
        continue;
      }

      ItemStack previousItem = before[slot];
      int previousAmount = 0;
      if (previousItem != null && previousItem.isSimilar(sampleItem)) {
        previousAmount = previousItem.getAmount();
      }

      int increase = currentItem.getAmount() - previousAmount;
      if (increase > 0) {
        if (increase != sampleItem.getAmount() || changedSlot >= 0) {
          return -1;
        }
        changedSlot = slot;
      }
    }

    return changedSlot;
  }

  static int takeSimilarItems(ItemStack[] contents, ItemStack sampleItem, int amount) {
    int remainingAmount = amount;

    for (int slot = 0; slot < contents.length && remainingAmount > 0; slot++) {
      ItemStack item = contents[slot];
      if (item == null || item.getType().isAir() || !item.isSimilar(sampleItem)) {
        continue;
      }

      int amountTakenFromSlot = Math.min(remainingAmount, item.getAmount());
      remainingAmount -= amountTakenFromSlot;

      int newAmount = item.getAmount() - amountTakenFromSlot;
      if (newAmount <= 0) {
        contents[slot] = null;
      } else {
        item.setAmount(newAmount);
      }
    }

    return amount - remainingAmount;
  }

  private void registerCommand() {
    PluginCommand command = getCommand(commandName);
    if (command == null) {
      getLogger().severe("Command " + commandName + " is missing from plugin.yml.");
      return;
    }

    command.setExecutor(this);
    command.setTabCompleter(this);
  }

  private boolean handleTransferCommand(CommandSender sender, String[] args) {
    if (args.length != 2) {
      sender.sendMessage("Usage: /fasthopper transfar <1-64>");
      return true;
    }

    Integer requestedAmount = parseNumber(sender, args[1]);
    if (requestedAmount == null) {
      return true;
    }

    maxTransferAmount = clampTransferAmount(requestedAmount);
    saveSettings();
    sendTransferAmount(sender);
    return true;
  }

  private boolean handleTickCommand(CommandSender sender, String[] args) {
    if (args.length != 2) {
      sender.sendMessage("Usage: /fasthopper tick <1 or higher>");
      return true;
    }

    Integer requestedTicks = parseNumber(sender, args[1]);
    if (requestedTicks == null) {
      return true;
    }

    hopperTransferTicks = clampTransferTicks(requestedTicks);
    saveSettings();
    sendTransferTicks(sender);
    return true;
  }

  private Integer parseNumber(CommandSender sender, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      sender.sendMessage("Please enter a whole number.");
      return null;
    }
  }

  private void sendStatus(CommandSender sender) {
    sendTransferAmount(sender);
    sendTransferTicks(sender);
  }

  private void sendTransferAmount(CommandSender sender) {
    sender.sendMessage("Hopper transfer amount is " + maxTransferAmount + ".");
  }

  private void sendTransferTicks(CommandSender sender) {
    sender.sendMessage("Hopper transfer interval is " + hopperTransferTicks + " ticks.");
  }

  private void sendUsage(CommandSender sender, String label) {
    sender.sendMessage("Usage: /" + label + " transfar <1-64>");
    sender.sendMessage("Usage: /" + label + " tick <1 or higher>");
    sender.sendMessage("Usage: /" + label + " reload");
  }

  private void reloadSettings() {
    int configuredAmount = getConfig().getInt(transferAmountPath, defaultTransferAmount);
    int configuredTicks = getConfig().getInt(transferTicksPath, defaultTransferTicks);
    maxTransferAmount = clampTransferAmount(configuredAmount);
    hopperTransferTicks = clampTransferTicks(configuredTicks);
    boolean configChanged = false;

    if (configuredAmount != maxTransferAmount) {
      getConfig().set(transferAmountPath, maxTransferAmount);
      configChanged = true;
      getLogger()
          .warning(
              transferAmountPath + " must be between 1 and 64; using " + maxTransferAmount + ".");
    }

    if (!getConfig().contains(transferTicksPath) || configuredTicks != hopperTransferTicks) {
      getConfig().set(transferTicksPath, hopperTransferTicks);
      configChanged = true;
      if (configuredTicks != hopperTransferTicks) {
        getLogger()
            .warning(transferTicksPath + " must be at least 1; using " + hopperTransferTicks + ".");
      }
    }

    if (configChanged) {
      saveConfig();
    }
  }

  private void saveSettings() {
    getConfig().set(transferAmountPath, maxTransferAmount);
    getConfig().set(transferTicksPath, hopperTransferTicks);
    saveConfig();
  }

  private void transferItems(
      Inventory source,
      Inventory destination,
      ItemStack sampleItem,
      ItemStack[] destinationBefore,
      int amount) {
    if (amount <= 0) {
      return;
    }

    if (isShulkerBoxInventory(destination) && isShulkerBox(sampleItem.getType())) {
      return;
    }

    ItemStack[] destinationCurrent = copyStorageContents(destination);
    int destinationSlot = findInsertedSlot(destinationBefore, destinationCurrent, sampleItem);
    if (destinationSlot < 0) {
      return;
    }

    ItemStack destinationItem = destinationCurrent[destinationSlot];
    if (destinationItem == null || !destinationItem.isSimilar(sampleItem)) {
      return;
    }

    int stackLimit = Math.min(destinationItem.getMaxStackSize(), destination.getMaxStackSize());
    amount = Math.min(amount, stackLimit - destinationItem.getAmount());
    amount = Math.min(amount, sampleItem.getMaxStackSize());
    ItemStack[] sourceBefore = copyStorageContents(source);
    amount = Math.min(amount, countSimilarItems(sourceBefore, sampleItem, amount));
    if (amount <= 0) {
      return;
    }

    ItemStack[] sourceAfter = copyStorageContents(sourceBefore);
    int removedAmount = takeSimilarItems(sourceAfter, sampleItem, amount);
    if (removedAmount <= 0) {
      return;
    }

    ItemStack destinationAfter = destinationItem.clone();
    destinationAfter.setAmount(destinationAfter.getAmount() + removedAmount);

    try {
      source.setStorageContents(sourceAfter);
      destination.setItem(destinationSlot, destinationAfter);
    } catch (RuntimeException exception) {
      boolean restored =
          restoreInventories(
              source, sourceBefore, destination, destinationSlot, destinationItem);
      String result = restored ? "restored the previous state" : "rollback also failed";
      getLogger().log(Level.SEVERE, "Batch transfer failed; " + result + ".", exception);
    }
  }

  private boolean setTransferCooldown(Inventory inventory) {
    if (inventory.getHolder(false) instanceof Hopper hopper) {
      hopper.setTransferCooldown(hopperTransferTicks);
      return true;
    }
    return false;
  }

  private ItemStack[] copyStorageContents(Inventory inventory) {
    return copyStorageContents(inventory.getStorageContents());
  }

  private ItemStack[] copyStorageContents(ItemStack[] contents) {
    ItemStack[] copy = new ItemStack[contents.length];
    for (int slot = 0; slot < contents.length; slot++) {
      if (contents[slot] != null) {
        copy[slot] = contents[slot].clone();
      }
    }
    return copy;
  }

  private int countSimilarItems(
      ItemStack[] contents, ItemStack sampleItem, int requiredAmount) {
    int availableAmount = 0;
    for (ItemStack item : contents) {
      if (item != null && !item.getType().isAir() && item.isSimilar(sampleItem)) {
        availableAmount += item.getAmount();
      }

      if (availableAmount >= requiredAmount) {
        return requiredAmount;
      }
    }

    return availableAmount;
  }

  private boolean isHopperInventory(Inventory inventory) {
    return inventory.getType() == InventoryType.HOPPER;
  }

  private boolean isShulkerBoxInventory(Inventory inventory) {
    return inventory.getType() == InventoryType.SHULKER_BOX;
  }

  private List<InventoryBlock> getInventoryBlocks(Inventory inventory) {
    List<InventoryBlock> blocks = new ArrayList<>();
    collectInventoryBlocks(inventory.getHolder(false), blocks);
    return List.copyOf(blocks);
  }

  private void collectInventoryBlocks(
      InventoryHolder holder, List<InventoryBlock> blocks) {
    if (holder instanceof BlockInventoryHolder blockHolder) {
      Block block = blockHolder.getBlock();
      blocks.add(new InventoryBlock(block.getLocation().clone(), block.getType()));
      return;
    }

    if (holder instanceof DoubleChest doubleChest) {
      collectInventoryBlocks(doubleChest.getLeftSide(false), blocks);
      collectInventoryBlocks(doubleChest.getRightSide(false), blocks);
    }
  }

  private boolean areInventoryBlocksAvailable(List<InventoryBlock> inventoryBlocks) {
    for (InventoryBlock inventoryBlock : inventoryBlocks) {
      Location location = inventoryBlock.location();
      if (!Bukkit.isOwnedByCurrentRegion(location)) {
        return false;
      }

      Block block = location.getBlock();
      if (block.getType() != inventoryBlock.material()
          || !(block.getState(false) instanceof InventoryHolder)) {
        return false;
      }
    }
    return true;
  }

  private boolean restoreInventories(
      Inventory source,
      ItemStack[] sourceBefore,
      Inventory destination,
      int destinationSlot,
      ItemStack destinationItem) {
    boolean restored = true;
    try {
      source.setStorageContents(sourceBefore);
    } catch (RuntimeException exception) {
      restored = false;
      getLogger().log(Level.SEVERE, "Could not restore the source inventory.", exception);
    }

    try {
      destination.setItem(destinationSlot, destinationItem);
    } catch (RuntimeException exception) {
      restored = false;
      getLogger().log(Level.SEVERE, "Could not restore the destination inventory.", exception);
    }
    return restored;
  }

  private record InventoryBlock(Location location, Material material) {}
}

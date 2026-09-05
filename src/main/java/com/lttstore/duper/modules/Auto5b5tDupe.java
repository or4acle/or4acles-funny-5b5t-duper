package com.lttstore.duper.modules;

import com.lttstore.duper.DuperAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.TrappedChestBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.minecraft.item.Items.CRAFTING_TABLE;
import static net.minecraft.item.Items.STICK;

public class Auto5b5tDupe extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChest = settings.createGroup("Auto Chest");

    // --- General Settings ---
    private final Setting<DupeMode> dupeMode = sgGeneral.add(new EnumSetting.Builder<DupeMode>()
        .name("mode")
        .description("How to choose items to dupe.")
        .defaultValue(DupeMode.TargetItems)
        .build()
    );

    private final Setting<List<Item>> itemsToDupe = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Specific items from inventory to automatically dupe.")
        .visible(() -> dupeMode.get() == DupeMode.TargetItems)
        .build()
    );

    private final Setting<Recipe> recipeMode = sgGeneral.add(new EnumSetting.Builder<Recipe>()
        .name("recipe")
        .description("Recipe to craft for desync. Ensure you have planks in inventory.")
        .defaultValue(Recipe.Stick)
        .build()
    );

    private final Setting<Boolean> autoRepeat = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-repeat")
        .description("Continuously repeats the dupe process automatically.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Tick delay between dupe cycles.")
        .defaultValue(5)
        .min(1)
        .max(40)
        .sliderRange(1, 20)
        .visible(autoRepeat::get)
        .build()
    );

    private final Setting<Boolean> smartPickup = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-pickup")
        .description("Wait until dropped items under feet are collected before advancing to next item.")
        .defaultValue(true)
        .visible(autoRepeat::get)
        .build()
    );

    private final Setting<Boolean> cleanGrid = sgGeneral.add(new BoolSetting.Builder()
        .name("clean-crafting-grid")
        .description("Automatically move planks trapped in crafting grid back to inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> dropAll = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-all")
        .description("Drop entire stack instead of single item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<RotationMode> rotationMode = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotation-mode")
        .description("Rotation mode when dropping items.")
        .defaultValue(RotationMode.Silent)
        .build()
    );

    private final Setting<Boolean> single = sgGeneral.add(new BoolSetting.Builder()
        .name("single-instant")
        .description("Only send craft desync packet once without dropping.")
        .defaultValue(false)
        .build()
    );

    // --- Auto Chest Settings ---
    private final Setting<Boolean> autoStore = sgChest.add(new BoolSetting.Builder()
        .name("auto-store")
        .description("Deposit duped items into a nearby chest when inventory gets full.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> chestRange = sgChest.add(new IntSetting.Builder()
        .name("chest-range")
        .description("Horizontal radius in blocks to search for chests, barrels, or shulkers.")
        .defaultValue(4)
        .min(1)
        .max(6)
        .sliderRange(1, 6)
        .visible(autoStore::get)
        .build()
    );

    private final Setting<Integer> keepStacks = sgChest.add(new IntSetting.Builder()
        .name("keep-stacks")
        .description("How many stacks of each duped item to keep in inventory to continue duping.")
        .defaultValue(1)
        .min(1)
        .max(5)
        .sliderRange(1, 5)
        .visible(autoStore::get)
        .build()
    );

    private final Setting<Integer> emptySlotsThreshold = sgChest.add(new IntSetting.Builder()
        .name("empty-slots-threshold")
        .description("Store in chest when empty inventory slots fall below or reach this number.")
        .defaultValue(2)
        .min(0)
        .max(8)
        .sliderRange(0, 8)
        .visible(autoStore::get)
        .build()
    );

    private final Setting<Integer> depositPerTick = sgChest.add(new IntSetting.Builder()
        .name("deposit-per-tick")
        .description("How many stacks to move into the container each tick (prevents packet kick).")
        .defaultValue(3)
        .min(1)
        .max(9)
        .sliderRange(1, 9)
        .visible(autoStore::get)
        .build()
    );

    // --- State Variables ---
    private Phase phase = Phase.PREPARE;
    private int timer = 0;
    private int chestTimeout = 0;
    private int pickupWaitTimer = 0;
    private RecipeEntry<?> recipeEntry;
    private float oldPitch;
    private boolean pitchChanged = false;
    private Item lastDupedItem = null;
    private final Deque<Integer> pendingSlots = new ArrayDeque<>();
    private int totalDuped = 0;

    // Deposit state
    private final Map<Item, Integer> depositBudget = new HashMap<>();
    private int depositCursor = 0;
    private int movedThisRun = 0;

    public Auto5b5tDupe() {
        super(DuperAddon.CATEGORY, "auto-5b5t-dupe", "Automatically dupes multiple items with chest storage on 5b5t.");
    }

    @Override
    public String getInfoString() {
        if (!isActive()) return null;
        if (totalDuped > 0) {
            return totalDuped + " duped";
        }
        return phase.name();
    }

    @Override
    public void onActivate() {
        if (!canAct()) {
            toggle();
            return;
        }

        pitchChanged = false;
        timer = 0;
        chestTimeout = 0;
        pickupWaitTimer = 0;
        lastDupedItem = null;
        pendingSlots.clear();
        depositBudget.clear();
        totalDuped = 0;
        recipeEntry = null;

        if (cleanGrid.get()) {
            clearCraftingGrid();
        }

        if (!findRecipe()) {
            ChatUtils.error("Recipe for " + recipeMode.get().item.getName().getString() + " not found in recipe book!");
            toggle();
            return;
        }

        // Single instant desync: fires one craft request without dropping anything.
        if (single.get()) {
            mc.player.networkHandler.sendPacket(new CraftRequestC2SPacket(mc.player.currentScreenHandler.syncId, recipeEntry, false));
            ChatUtils.info("Sent single craft desync packet.");
            toggle();
            return;
        }

        phase = Phase.PREPARE;
        DuperAddon.LOG.info("{} enabled.", name);
    }

    @Override
    public void onDeactivate() {
        unrotate();
        phase = Phase.PREPARE;
        timer = 0;
        pendingSlots.clear();
        depositBudget.clear();

        if (cleanGrid.get()) {
            clearCraftingGrid();
        }

        DuperAddon.LOG.info("{} disabled. Duped {} items total.", name, totalDuped);
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (!canAct()) {
            toggle();
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        switch (phase) {
            case PREPARE -> phasePrepare();
            case DROP_AND_CRAFT -> phaseDropAndCraft();
            case WAIT_PICKUP -> phaseWaitPickup();
            case OPEN_CHEST -> phaseOpenChest();
            case DEPOSIT_CHEST -> phaseDepositChest();
        }
    }

    // --- Phases ---

    private void phasePrepare() {
        if (cleanGrid.get()) {
            clearCraftingGrid();
        }

        // Verify wood planks exist
        if (!hasPlanks()) {
            ChatUtils.error("No wood planks found in inventory for " + recipeMode.get().item.getName().getString() + "!");
            toggle();
            return;
        }

        // Ensure recipe entry is cached
        if (recipeEntry == null && !findRecipe()) {
            ChatUtils.error("Could not find recipe for " + recipeMode.get().item.getName().getString());
            toggle();
            return;
        }

        // Check if inventory is getting full and auto store is enabled
        if (autoStore.get() && getEmptySlotsCount() <= emptySlotsThreshold.get()) {
            BlockPos chestPos = findNearbyChest(chestRange.get());
            if (chestPos != null) {
                BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                phase = Phase.OPEN_CHEST;
                chestTimeout = 30;
                timer = 2;
                return;
            }

            ChatUtils.warning("Inventory is full and no nearby container was found!");
            toggle();
            return;
        }

        // Build the queue of slots to dupe for this cycle
        pendingSlots.clear();
        collectSlots(pendingSlots);
        if (pendingSlots.isEmpty()) {
            // Check if dropped items are currently on the ground waiting to be picked up
            if (hasNearbyDroppedItem()) {
                phase = Phase.WAIT_PICKUP;
                timer = 2;
                return;
            }
            ChatUtils.warning("No eligible items found in inventory to dupe.");
            toggle();
            return;
        }

        if (!advanceSlot()) {
            toggle();
            return;
        }

        phase = Phase.DROP_AND_CRAFT;
        timer = 1; // 1 tick to ensure slot selection/swap has settled
    }

    private void phaseDropAndCraft() {
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty()) {
            // Hotbar hand is empty, re-check inventory
            phase = Phase.PREPARE;
            timer = 1;
            return;
        }

        lastDupedItem = held.getItem();

        // 1. Rotate down to feet
        rotate();

        // 2. Drop item from hand
        mc.player.dropSelectedItem(dropAll.get());

        // 3. Send craft request desync packet immediately in the same network frame
        mc.player.networkHandler.sendPacket(new CraftRequestC2SPacket(mc.player.currentScreenHandler.syncId, recipeEntry, false));

        // 4. Restore rotation
        unrotate();

        totalDuped++;
        pickupWaitTimer = 0;
        phase = Phase.WAIT_PICKUP;
        timer = Math.max(1, delay.get());
    }

    private void phaseWaitPickup() {
        // If smart pickup is enabled, wait until dropped items under feet are collected
        if (smartPickup.get() && hasNearbyDroppedItem()) {
            if (pickupWaitTimer++ < 30) {
                timer = 2;
                return;
            }
        }

        if (cleanGrid.get()) {
            clearCraftingGrid();
        }

        // If more slots were queued in this round, advance to the next one
        if (!pendingSlots.isEmpty()) {
            if (advanceSlot()) {
                phase = Phase.DROP_AND_CRAFT;
                timer = 1;
                return;
            }
        }

        // Round finished.
        if (!autoRepeat.get()) {
            ChatUtils.info("Finished dupe cycle. Duped " + totalDuped + " items total.");
            toggle();
            return;
        }

        // Continue next round
        phase = Phase.PREPARE;
        timer = 1;
    }

    private void phaseOpenChest() {
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler || mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            depositBudget.clear();
            buildDepositBudget();
            depositCursor = 0;
            movedThisRun = 0;
            phase = Phase.DEPOSIT_CHEST;
            timer = 1;
            return;
        }

        if (chestTimeout-- <= 0) {
            ChatUtils.warning("Failed to open container in time. Resuming dupe...");
            phase = Phase.PREPARE;
            timer = delay.get();
        }
    }

    private void phaseDepositChest() {
        if (!depositBudget.isEmpty()) {
            depositSlice(depositPerTick.get());
        }

        if (depositBudget.isEmpty()) {
            mc.player.closeHandledScreen();
            ChatUtils.info("Deposited " + movedThisRun + " stacks into container. Resuming dupe...");
            depositBudget.clear();
            phase = Phase.PREPARE;
            timer = delay.get();
        } else {
            // Continue moving remaining stacks next tick
            timer = 1;
        }
    }

    // --- Slot Selection ---

    private void collectSlots(Deque<Integer> out) {
        if (mc.player == null) return;

        if (dupeMode.get() == DupeMode.HeldItem) {
            if (!mc.player.getMainHandStack().isEmpty()) {
                out.add(mc.player.getInventory().selectedSlot);
            } else {
                ChatUtils.error("Hold an item to dupe in your hand.");
            }
            return;
        }

        List<Item> targets = dupeMode.get() == DupeMode.TargetItems ? itemsToDupe.get() : null;
        if (dupeMode.get() == DupeMode.TargetItems && (targets == null || targets.isEmpty())) {
            ChatUtils.error("No items selected to dupe in settings.");
            return;
        }

        int queued = 0;
        // Check hotbar first (0-8)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || isCraftIngredient(stack)) continue;

            if (dupeMode.get() == DupeMode.AllInventory || targets.contains(stack.getItem())) {
                out.add(i);
                queued++;
            }
        }

        // Then check main inventory (9-35)
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || isCraftIngredient(stack)) continue;

            if (dupeMode.get() == DupeMode.AllInventory || targets.contains(stack.getItem())) {
                out.add(i);
                queued++;
            }
        }

        if (queued == 0) {
            ChatUtils.warning(dupeMode.get() == DupeMode.AllInventory
                ? "No dupeable items found in inventory."
                : "None of the target items were found in your inventory.");
        }
    }

    private boolean advanceSlot() {
        if (mc.player == null || pendingSlots.isEmpty()) return false;

        int invSlot = pendingSlots.poll();
        if (invSlot < 9) {
            mc.player.getInventory().selectedSlot = invSlot;
            return true;
        }

        // Swap from main inventory slot (9-35) to current hotbar slot
        int currentHotbar = mc.player.getInventory().selectedSlot;
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, invSlot, currentHotbar, SlotActionType.SWAP, mc.player);
        return true;
    }

    // --- Ingredients & Grid Cleaning ---

    private boolean findRecipe() {
        if (mc.player == null || mc.world == null) return false;
        if (recipeEntry != null) {
            ItemStack result = recipeEntry.value().getResult(mc.world.getRegistryManager());
            if (result.getItem() == recipeMode.get().item) {
                return true;
            }
        }

        List<RecipeResultCollection> recipeList = mc.player.getRecipeBook().getOrderedResults();
        for (RecipeResultCollection collection : recipeList) {
            for (RecipeEntry<?> entry : collection.getAllRecipes()) {
                ItemStack resultStack = entry.value().getResult(mc.world.getRegistryManager());
                if (resultStack.getItem() == recipeMode.get().item) {
                    recipeEntry = entry;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasPlanks() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && (stack.isIn(ItemTags.PLANKS) || stack.getItem() == STICK || stack.getItem() == CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private void clearCraftingGrid() {
        if (mc.player == null) return;
        for (int i = 1; i <= 4; i++) {
            Slot slot = mc.player.playerScreenHandler.getSlot(i);
            if (slot != null && !slot.getStack().isEmpty()) {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            }
        }
    }

    private boolean isCraftIngredient(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isIn(ItemTags.PLANKS) || stack.isIn(ItemTags.LOGS)) return true;
        Item item = stack.getItem();
        return item == STICK || item == CRAFTING_TABLE;
    }

    private boolean hasNearbyDroppedItem() {
        if (mc.world == null || mc.player == null) return false;
        List<ItemEntity> items = mc.world.getEntitiesByClass(
            ItemEntity.class,
            mc.player.getBoundingBox().expand(1.8),
            entity -> entity.isAlive() && !entity.getStack().isEmpty()
        );
        return !items.isEmpty();
    }

    // --- Auto Chest / Sorting ---

    private void buildDepositBudget() {
        depositBudget.clear();
        if (mc.player == null) return;

        Map<Item, Integer> stackCounts = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!shouldDeposit(stack)) continue;

            Item item = stack.getItem();
            stackCounts.put(item, stackCounts.getOrDefault(item, 0) + 1);
        }

        for (Map.Entry<Item, Integer> entry : stackCounts.entrySet()) {
            int excess = entry.getValue() - keepStacks.get();
            if (excess > 0) {
                depositBudget.put(entry.getKey(), excess);
            }
        }
    }

    private void depositSlice(int amount) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;

        var handler = mc.player.currentScreenHandler;
        int moved = 0;

        for (; depositCursor < handler.slots.size(); depositCursor++) {
            if (moved >= amount) return;

            Slot slot = handler.slots.get(depositCursor);
            if (slot.inventory != mc.player.getInventory() || slot.getStack().isEmpty()) continue;

            Item item = slot.getStack().getItem();
            int remaining = depositBudget.getOrDefault(item, 0);
            if (remaining <= 0) continue;

            mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
            depositBudget.put(item, remaining - 1);
            moved++;
            movedThisRun++;
        }

        // If we reached the end of the inventory slots, clean up budget
        if (depositCursor >= handler.slots.size()) {
            depositBudget.clear();
        }
    }

    private boolean shouldDeposit(ItemStack stack) {
        if (isCraftIngredient(stack)) return false;

        return switch (dupeMode.get()) {
            case TargetItems -> itemsToDupe.get() != null && itemsToDupe.get().contains(stack.getItem());
            case AllInventory -> true;
            case HeldItem -> lastDupedItem != null && stack.getItem() == lastDupedItem;
        };
    }

    private int getEmptySlotsCount() {
        if (mc.player == null) return 0;
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private BlockPos findNearbyChest(int range) {
        if (mc.player == null || mc.world == null) return null;
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;

        for (int x = -range; x <= range; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    Block block = state.getBlock();
                    if (block instanceof ChestBlock || block instanceof TrappedChestBlock
                        || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
                        double dist = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestPos = pos;
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    // --- Rotation ---

    private void rotate() {
        if (mc.player == null) return;
        switch (rotationMode.get()) {
            case Silent -> mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(mc.player.getYaw(), 90.0f, mc.player.isOnGround()));
            case Client -> {
                oldPitch = mc.player.getPitch();
                pitchChanged = true;
                mc.player.setPitch(90.0f);
            }
            case None -> {}
        }
    }

    private void unrotate() {
        if (mc.player != null && pitchChanged && rotationMode.get() == RotationMode.Client) {
            mc.player.setPitch(oldPitch);
            pitchChanged = false;
        }
    }

    // --- Helpers ---

    private boolean canAct() {
        return mc.player != null && mc.world != null && mc.interactionManager != null
            && mc.player.networkHandler != null && mc.player.currentScreenHandler != null;
    }

    public enum DupeMode {
        TargetItems,
        AllInventory,
        HeldItem
    }

    private enum Phase {
        PREPARE,
        DROP_AND_CRAFT,
        WAIT_PICKUP,
        OPEN_CHEST,
        DEPOSIT_CHEST
    }

    public enum RotationMode {
        Silent,
        Client,
        None
    }

    public enum Recipe {
        Stick(STICK),
        CraftingTable(CRAFTING_TABLE);

        final Item item;

        Recipe(Item item) {
            this.item = item;
        }
    }
}

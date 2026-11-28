package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.engine.RulesetResolution;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ApEvent;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ApState;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ExchangeSide;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ExchangeWithNpc;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.Item;

import java.util.ArrayList;
import java.util.List;

/** 从双方的权威资产结算一次 NPC 交换。 */
final class ApNpcExchange {
    /** 此类只提供无副作用的结算方法。 */
    private ApNpcExchange() {}

    /** 验证双方交付并返回一个可一次性保存的新状态。
     * @param state 交换前的权威 AP 状态
     * @param exchange 包含 NPC 标识和两侧交付的意图
     */
    static RulesetResolution<ApState, ApEvent> resolve(ApState state, ExchangeWithNpc exchange) {
        ApNpc npc = state.npcs().stream()
                .filter(candidate -> candidate.npcId().equals(exchange.npcId()) && candidate.status() == ApNpc.Status.ACTIVE)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("NPC 未登记或当前不可互动"));
        ExchangeSide give = exchange.give();
        ExchangeSide receive = exchange.receive();
        if (give == null || receive == null || (empty(give) && empty(receive))) {
            throw new IllegalArgumentException("交易至少需要一方交付铜币或物品");
        }
        if (state.resources().copper() < give.copper()) throw new IllegalArgumentException("玩家铜币不足");
        if (npc.copper() < receive.copper()) throw new IllegalArgumentException("NPC 铜币不足");

        Split playerItems = split(state.inventory(), give, "玩家");
        Split npcItems = split(npc.inventory(), receive, "NPC");
        Item given = playerItems.transferred();
        if (given != null && (given.name().equals(state.equipment().weapon()) || given.name().equals(state.equipment().armor()))
                && playerItems.remaining().stream().noneMatch(item -> item.name().equals(given.name()) && item.quantity() > 0)) {
            throw new IllegalArgumentException("不能交出正在装备的最后一件物品：「" + given.name() + "」");
        }

        ApState updated;
        try {
            long npcCopper = Math.addExact(npc.copper() - receive.copper(), give.copper());
            List<Item> nextPlayerItems = add(playerItems.remaining(), npcItems.transferred());
            List<Item> nextNpcItems = add(npcItems.remaining(), given);
            updated = state.withResources(state.resources().add(receive.copper() - give.copper(), 0, 0))
                    .withInventory(nextPlayerItems)
                    .withSystems(state.systems().replaceNpc(npc.withAssets(nextNpcItems, npcCopper)));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("交易后的铜币或物品数量超出容量", exception);
        }
        String description = "与「" + npc.name() + "」完成交易：玩家交付" + describe(give) + "，获得" + describe(receive) + "。";
        return new RulesetResolution<>(updated, List.of(new ApEvent("npc_exchange", 1, description, "player", npc.npcId())));
    }

    /** 判断一侧是否没有资产。
     * @param side 已通过参数校验的一侧交付
     */
    private static boolean empty(ExchangeSide side) {
        return side.copper() == 0 && side.quantity() == 0;
    }

    /** 从库存中取出指定数量，保留物品属性，并拒绝同名但属性不同的来源。
     * @param inventory 交付方交换前的库存
     * @param side 本侧交付的资产，交付铜币或为空时保持库存
     * @param owner 用于错误提示的库存归属
     */
    private static Split split(List<Item> inventory, ExchangeSide side, String owner) {
        if (side.quantity() == 0) return new Split(inventory, null);
        List<Item> remaining = new ArrayList<>();
        Item unit = null;
        int needed = side.quantity();
        for (Item item : inventory) {
            if (!item.name().equals(side.itemName()) || item.quantity() == 0) {
                remaining.add(item);
                continue;
            }
            Item candidate = item.withQuantity(1);
            if (unit != null && !unit.equals(candidate)) {
                throw new IllegalArgumentException(owner + "库存存在属性不同的同名物品：「" + side.itemName() + "」");
            }
            unit = candidate;
            int taken = Math.min(needed, item.quantity());
            needed -= taken;
            if (item.quantity() > taken) remaining.add(item.withQuantity(item.quantity() - taken));
        }
        if (unit == null || needed > 0) throw new IllegalArgumentException(owner + "的「" + side.itemName() + "」数量不足");
        return new Split(remaining, unit.withQuantity(side.quantity()));
    }

    /** 将收到的物品加入库存，只有全部属性一致的同名物品可以合并。
     * @param inventory 接收方扣除交付后的库存
     * @param incoming 收到的物品，未收到物品时为空
     */
    private static List<Item> add(List<Item> inventory, Item incoming) {
        if (incoming == null) return inventory;
        List<Item> next = new ArrayList<>(inventory);
        int stack = -1;
        for (int index = 0; index < next.size(); index++) {
            Item existing = next.get(index);
            if (!existing.name().equals(incoming.name())) continue;
            if (!existing.withQuantity(1).equals(incoming.withQuantity(1))) {
                throw new IllegalArgumentException("接收方已有属性不同的同名物品：「" + incoming.name() + "」");
            }
            if (stack == -1) stack = index;
        }
        if (stack == -1) next.add(incoming);
        else next.set(stack, incoming.withQuantity(Math.addExact(next.get(stack).quantity(), incoming.quantity())));
        return next;
    }

    /** 格式化一侧已经结算的资产。
     * @param side 已完成交付或接收的一侧
     */
    private static String describe(ExchangeSide side) {
        if (side.copper() > 0) return side.copper() + " 枚铜币";
        if (side.quantity() > 0) return side.quantity() + " 件「" + side.itemName() + "」";
        return "无";
    }

    /** 从一侧库存中拆分出的物品及剩余库存。
     * @param remaining 扣除交付物品后的库存
     * @param transferred 被交付的物品，没有物品交易时为空
     */
    private record Split(List<Item> remaining, Item transferred) {}
}

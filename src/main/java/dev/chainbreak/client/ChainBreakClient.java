package dev.chainbreak.client;

import dev.chainbreak.ChainBreakState;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * 客户端入口：注册按键并把按键状态写进 ChainBreakState。
 * <p>
 * 默认按键：` 启用连锁挖矿，[ / ] 切换形状，K 打开配置界面。
 */
public class ChainBreakClient implements ClientModInitializer {

    public static KeyMapping activateKey;
    public static KeyMapping shapeNextKey;
    public static KeyMapping shapePrevKey;
    public static KeyMapping configKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.parse("chainbreak"));

        // 96 = GLFW_KEY_GRAVE_ACCENT（`），91 = [，93 = ]，75 = K
        activateKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.chainbreak.activate", 96, category));
        shapePrevKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.chainbreak.shape_prev", 91, category));
        shapeNextKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.chainbreak.shape_next", 93, category));
        configKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.chainbreak.config", 75, category));

        // 注册形状菜单 HUD
        HudElementRegistry.addLast(Identifier.parse("chainbreak:shape_hud"), new ChainBreakHud());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ChainBreakState.keyDown = activateKey != null && activateKey.isDown();
            if (shapeNextKey != null && shapeNextKey.consumeClick()) {
                ChainBreakState.cycleShape(1);
            }
            if (shapePrevKey != null && shapePrevKey.consumeClick()) {
                ChainBreakState.cycleShape(-1);
            }
            if (configKey != null && configKey.consumeClick() && client.canInterruptScreen()) {
                client.setScreenAndShow(new ChainBreakConfigMenu(null));
            }
        });
    }
}

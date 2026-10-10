package tw.sunrisemc.geyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.predicate.item.ItemRangeDispatchPredicate;
import org.geysermc.geyser.api.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * SunriseItems — 讓日出伺服器的 Geyser custom item mappings 從 GitHub release 動態載入。
 *
 * <p>為什麼要這個 extension：Geyser 的 custom item 註冊只在開機 {@code initialize()} 時跑一次
 * （{@code Registries.populate()} → fire {@link GeyserDefineCustomItemsEvent}），{@code /geyser reload}
 * 走的是 {@code startInstance()} 子集、不重跑註冊。所以原生沒有「不重啟換 mappings」的能力。</p>
 *
 * <p>這個 extension 的價值：在開機的 {@link GeyserDefineCustomItemsEvent} 當下，<b>直接從 GitHub
 * release 的 {@code geyser_item_mappings.json} 動態抓最新版</b>來註冊，省掉每次手動把 mappings 檔
 * {@code docker cp} 進容器。CI pipeline 推新 release 後，伺服器只要（完整）重啟 Geyser，就會自動
 * 拉到最新 mappings——mappings 的「真實來源」變成 GitHub release，不再是容器內的本地檔。</p>
 *
 * <p>抓不到網路時 fallback 到上次成功快取（dataFolder 內），確保離線也能開機。</p>
 */
public class SunriseItemsExtension implements Extension {

    /** mappings JSON 的權威來源：GitHub release latest，經 gh-proxy（content-type 已修正為 application/zip 不影響 JSON）。 */
    private static final String MAPPINGS_URL =
            "https://gh.gdtw.qzz.io/https://github.com/sunrisemc-tw/resourcepack/releases/latest/download/geyser_item_mappings.json";

    /** 本地快取檔名（dataFolder 內）。 */
    private static final String CACHE_FILE = "geyser_item_mappings.cached.json";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Subscribe
    public void onDefineCustomItems(GeyserDefineCustomItemsEvent event) {
        String json = fetchMappings();
        if (json == null) {
            logger().error("[SunriseItems] 無法取得 mappings（遠端與快取都失敗），不註冊任何 custom item。");
            return;
        }

        int registered = 0;
        int failed = 0;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject items = root.getAsJsonObject("items");
            if (items == null) {
                logger().error("[SunriseItems] mappings JSON 缺 \"items\" 區塊。");
                return;
            }
            // items: { "minecraft:crossbow": [ {bedrock_identifier, bedrock_options.icon, custom_model_data, type}, ... ], ... }
            for (String javaItem : items.keySet()) {
                JsonArray defs = items.getAsJsonArray(javaItem);
                Identifier javaId = Identifier.of(javaItem);
                for (int i = 0; i < defs.size(); i++) {
                    JsonObject d = defs.get(i).getAsJsonObject();
                    try {
                        register(event, javaId, d);
                        registered++;
                    } catch (Exception ex) {
                        failed++;
                        if (failed <= 5) {
                            logger().warning("[SunriseItems] 註冊失敗 "
                                    + (d.has("bedrock_identifier") ? d.get("bedrock_identifier").getAsString() : "?")
                                    + ": " + ex.getMessage());
                        }
                    }
                }
            }
        } catch (Exception ex) {
            logger().error("[SunriseItems] 解析 mappings 失敗: " + ex.getMessage());
            return;
        }
        logger().info("[SunriseItems] 已註冊 " + registered + " 個 custom item"
                + (failed > 0 ? "（" + failed + " 個失敗）" : "") + "。");
    }

    private void register(GeyserDefineCustomItemsEvent event, Identifier javaId, JsonObject d) {
        String bedrockId = d.get("bedrock_identifier").getAsString();
        int cmd = d.get("custom_model_data").getAsInt();

        // bedrock_identifier 同時當作 Bedrock item model identifier（與我方 j2b 轉換器、attachable 命名一致）。
        Identifier bedrockIdentifier = Identifier.of(bedrockId);

        CustomItemBedrockOptions.Builder options = CustomItemBedrockOptions.builder();
        if (d.has("bedrock_options")) {
            JsonObject bo = d.getAsJsonObject("bedrock_options");
            if (bo.has("icon") && !bo.get("icon").isJsonNull()) {
                options.icon(bo.get("icon").getAsString());
            }
        }

        // legacy custom_model_data predicate：對應 Java <1.21.4 的 CMD 整數（我方 type:"legacy"）。
        CustomItemDefinition.Builder def = CustomItemDefinition.builder(bedrockIdentifier, bedrockIdentifier)
                .bedrockOptions(options)
                .predicate(ItemRangeDispatchPredicate.legacyCustomModelData(cmd));

        event.register(javaId, def.build());
    }

    /** 先抓遠端，成功則更新快取；失敗退回快取。 */
    private String fetchMappings() {
        Path cache = dataFolder().resolve(CACHE_FILE);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(MAPPINGS_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Cache-Control", "no-cache")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                try {
                    Files.createDirectories(dataFolder());
                    Files.writeString(cache, resp.body(), StandardCharsets.UTF_8);
                } catch (IOException io) {
                    logger().warning("[SunriseItems] 寫快取失敗（不影響本次）: " + io.getMessage());
                }
                logger().info("[SunriseItems] 已從 GitHub release 取得最新 mappings。");
                return resp.body();
            }
            logger().warning("[SunriseItems] 遠端 mappings 回應異常 HTTP " + resp.statusCode() + "，改用快取。");
        } catch (Exception ex) {
            logger().warning("[SunriseItems] 下載 mappings 失敗（" + ex.getMessage() + "），改用快取。");
        }

        // fallback：快取
        try {
            if (Files.exists(cache)) {
                return Files.readString(cache, StandardCharsets.UTF_8);
            }
        } catch (IOException io) {
            logger().error("[SunriseItems] 讀快取失敗: " + io.getMessage());
        }
        return null;
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        logger().info("[SunriseItems] 已載入。mappings 來源 = GitHub release（動態）。"
                + "改了物品要生效：推新 release → 完整重啟 Geyser/proxy。");
    }
}

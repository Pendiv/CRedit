package DIV.credit.command;

import DIV.credit.Credit;
import DIV.credit.client.draft.DraftStore;
import DIV.credit.client.draft.IngredientSpec;
import DIV.credit.client.draft.RecipeDraft;
import DIV.credit.client.draft.RecipeDraft.SlotKind;
import DIV.credit.client.io.ScriptWriter;
import DIV.credit.jei.CraftPatternJeiPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import mezz.jei.api.recipe.category.IRecipeCategory;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * EmitTest: 宣言ファイルから「アイテム/液体/気体 + レシピタイプ要求」 を読み、 各要求の draft を構築・slot 充填・
 * emit して、 本番 dump とは別の <b>テスト専用フォルダ</b> に出力する。 リモート運用 (起動→ログ確認→kill) 用。
 *
 * <h3>入力</h3>
 * {@code <gamedir>/credit/test_requests.json} — 要求の配列を持つ JSON:
 * <pre>
 * {
 *   "requests": [
 *     {
 *       "id": "asm1",                       // 任意。 出力ファイル名 + recipe id に使う
 *       "type": "gtceu:assembler",          // 必須。 JEI category UID
 *       "inputs":  [ {"item":"minecraft:iron_ingot","count":4}, {"fluid":"minecraft:water","amount":1000} ],
 *       "outputs": [ {"item":"minecraft:iron_block"} ],
 *       "fields":  { "duration":200, "EUt":30 }   // 任意。 NumericField.label に case-insensitive で対応
 *     }
 *   ]
 * }
 * </pre>
 * ingredient のキー: {@code item}/{@code tag}/{@code fluid}/{@code fluidtag}/{@code gas}。
 *  {@code count}(item/tag)、 {@code amount}(fluid/gas)。 gas は {@code chemical}: GAS/INFUSION/PIGMENT/SLURRY (既定 GAS)。
 *
 * <h3>起爆</h3>
 * <ul>
 *   <li>起動時自動: {@link CraftPatternJeiPlugin#onRuntimeAvailable} から (ファイルが在る時のみ)</li>
 *   <li>コマンド: {@code /credit dev emittest}</li>
 * </ul>
 *
 * <h3>出力</h3>
 * {@code <gamedir>/credit/test_out/emit_<timestamp>.js} (= 全要求を ServerEvents.recipes でラップした 1 ファイル) +
 * {@code emit_<timestamp>.log.txt} (= 要求ごとの成否)。 <b>KubeJS は読まない場所</b> なのでゲームに影響しない。
 */
public final class EmitTestRunner {

    private EmitTestRunner() {}

    public static final String REQUESTS_FILE = "credit/test_requests.json";
    public static final String OUT_DIR       = "credit/test_out";

    /** 1 要求の処理結果。 */
    public record Result(String id, String type, boolean ok, String detail, @Nullable String code) {}

    // ─── 起動時自動実行 (= category 登録完了を tick で待ってから発火) ───

    private static volatile boolean armed = false;
    private static int lastCatCount = -1;
    private static int stableTicks = 0;
    private static int waitedTicks = 0;

    /**
     * 起動時自動実行を予約 (= onRuntimeAvailable から呼ぶ)。 即実行はしない。
     * JEI の recipe category 登録は runtime 受領後も非同期で続くため、 ClientTick で
     * category 数が安定する (= 2 連続同数) まで待ってから {@link #run} する。
     */
    public static void armStartupRun() {
        Path req = gameDir().resolve(REQUESTS_FILE);
        if (!Files.exists(req)) return;  // ファイル無ければ無音 (= arm もしない)
        armed = true;
        lastCatCount = -1; stableTicks = 0; waitedTicks = 0;
        Credit.LOGGER.info("[C8002] EmitTest: armed (waiting for JEI categories to settle)");
    }

    /** Forge ClientTickEvent.Post から呼ぶ。 category 数が安定したら 1 回だけ発火。 */
    public static void onClientTick() {
        if (!armed) return;
        var rt = CraftPatternJeiPlugin.runtime;
        if (rt == null) return;
        int count;
        try { count = (int) rt.getRecipeManager().createRecipeCategoryLookup().get().count(); }
        catch (Throwable t) { return; }
        waitedTicks++;
        if (count > 0 && count == lastCatCount) {
            stableTicks++;
        } else {
            stableTicks = 0;
            lastCatCount = count;
        }
        // 安定 (= 5 tick 同数) で発火。 保険で 600 tick (=30s) 経過時も強制発火。
        if ((stableTicks >= 5 && count > 0) || waitedTicks > 600) {
            armed = false;
            Credit.LOGGER.info("[C8008] EmitTest: categories settled ({} after {} ticks), running",
                count, waitedTicks);
            run(false);
        }
    }

    /** Forge event subscriber (client tick で category 安定を待って発火)。 */
    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(
        modid = Credit.MODID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static final class Hook {
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onClientTickEvent(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
            if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) onClientTick();
        }
    }

    /**
     * 本体。 requests.json を読み → 各要求を emit → test_out に書く。
     * @param verboseChat true なら player chat に結果を出す (= コマンド経由)
     * @return 成功した要求数 (-1 = ファイル/JEI 不備で実行不能)
     */
    public static int run(boolean verboseChat) {
        if (CraftPatternJeiPlugin.runtime == null) {
            log(verboseChat, "[EmitTest] JEI runtime not ready");
            return -1;
        }
        Path reqFile = gameDir().resolve(REQUESTS_FILE);
        if (!Files.exists(reqFile)) {
            log(verboseChat, "[EmitTest] no " + REQUESTS_FILE + " (create it under <gamedir>/credit/)");
            return -1;
        }

        List<JsonObject> requests;
        try {
            String raw = Files.readString(reqFile, StandardCharsets.UTF_8);
            requests = parseRequests(raw);
        } catch (Exception e) {
            Credit.LOGGER.warn("[C6017] EmitTest: requests.json parse failed: {}", e.toString());
            log(verboseChat, "[EmitTest] parse failed: " + e.getMessage());
            return -1;
        }
        if (requests.isEmpty()) {
            log(verboseChat, "[EmitTest] requests.json has no requests");
            return 0;
        }

        List<Result> results = new ArrayList<>(requests.size());
        int idx = 0;
        for (JsonObject r : requests) {
            results.add(processOne(r, idx++));
        }

        Path written = writeOutput(results);
        int okCount = (int) results.stream().filter(Result::ok).count();
        log(verboseChat, "[EmitTest] " + okCount + "/" + results.size() + " ok" +
            (written != null ? " → " + written : " (write failed)"));
        // 失敗詳細は常に log、 chat には verbose 時のみ
        for (Result res : results) {
            if (!res.ok()) {
                Credit.LOGGER.info("[C8003] EmitTest FAIL [{}] {}: {}", res.id(), res.type(), res.detail());
                if (verboseChat) log(true, "  FAIL " + res.id() + " (" + res.type() + "): " + res.detail());
            }
        }
        return okCount;
    }

    // ─── 1 要求処理 ───

    private static Result processOne(JsonObject req, int idx) {
        String type = req.has("type") ? req.get("type").getAsString() : null;
        String id = req.has("id") ? req.get("id").getAsString() : ("req" + idx);
        if (type == null || type.isBlank()) {
            return new Result(id, "?", false, "missing 'type'", null);
        }
        ResourceLocation uid;
        try { uid = new ResourceLocation(type); }
        catch (Exception e) { return new Result(id, type, false, "invalid type id", null); }

        // category 解決: createRecipeCategoryLookup を全走査して UID 一致で引く。
        //  JeiRenderBridge.findCategoryByUid (= getRecipeType(uid) 依存、 deprecated) は
        //  gtceu:assembler 等で null を返したため、 確実な全走査方式に変更 (= PreviewTestCommand と同経路)。
        IRecipeCategory<?> cat = resolveCategory(uid);
        if (cat == null) {
            return new Result(id, type, false, "no JEI category for type (mod not loaded? wrong uid?)", null);
        }
        // draft 構築 (= 本番と同じ DraftStore 経路 = 汎化)
        RecipeDraft draft = DraftStore.create(cat, DraftStore.CraftingVariant.SHAPED);
        if (draft == null) {
            return new Result(id, type, false, "credit has no draft for this category", null);
        }

        // slot 充填: draft.slotKind を走査して input/output を順に詰める
        List<IngredientSpec> inputs  = parseIngredients(req.getAsJsonArray("inputs"));
        List<IngredientSpec> outputs = parseIngredients(req.getAsJsonArray("outputs"));
        FillReport fr = fillSlots(draft, inputs, outputs);

        // numeric fields
        applyFields(draft, req);

        // emit (= 本番と同じ ScriptWriter.buildAddCode)
        String recipeId = "credit:test/" + sanitize(uid.getPath()) + "_" + sanitize(id);
        String code = ScriptWriter.buildAddCode(draft, recipeId);
        if (code == null || code.isBlank()) {
            return new Result(id, type, false, "emit returned null (draft empty? " + fr + ")", null);
        }
        String detail = "draft=" + draft.getClass().getSimpleName() + " " + fr;
        return new Result(id, type, true, detail, code);
    }

    /**
     * recipe type UID → JEI IRecipeCategory。 本番と同じ backend 抽象 (BackendRegistry.active())
     * 経由で解決する。 EMI backend では GT/Mek 等が JEMI bridge 経由 (= JemiCategory) で出るため、
     * JEI runtime 直叩き (getAllRecipeTypes) では取りこぼす。 backend.findCategory は JemiCategory の
     * 内部 JEI category を nativeRef に入れて返すので、 ここで IRecipeCategory に cast できる。
     */
    @Nullable
    private static IRecipeCategory<?> resolveCategory(ResourceLocation uid) {
        try {
            var backend = DIV.credit.client.runtime.BackendRegistry.active();
            var opt = backend.findCategory(uid);
            if (opt.isEmpty()) {
                Credit.LOGGER.warn("[C8006] EmitTest: backend({}) has no category for '{}'", backend.id(), uid);
                return null;
            }
            Object nat = opt.get().nativeRef();
            if (nat instanceof IRecipeCategory<?> jeiCat) return jeiCat;
            Credit.LOGGER.warn("[C8009] EmitTest: category '{}' nativeRef is {} (not IRecipeCategory) — backend={}",
                uid, nat == null ? "null" : nat.getClass().getName(), backend.id());
            return null;
        } catch (Throwable t) {
            Credit.LOGGER.warn("[C8007] EmitTest: category lookup threw for {}: {}", uid, t.toString());
            return null;
        }
    }

    /** input/output を draft の slotKind に従って配置。 型 (ITEM/FLUID/GAS) が一致する次の空き input/output slot に入れる。 */
    private static FillReport fillSlots(RecipeDraft draft, List<IngredientSpec> inputs, List<IngredientSpec> outputs) {
        int n = draft.slotCount();
        boolean[] used = new boolean[n];
        int inOk = 0, outOk = 0, inMiss = 0, outMiss = 0;

        for (IngredientSpec spec : inputs) {
            SlotFamily fam = familyOf(spec);
            int slot = findSlot(draft, used, fam, false);
            if (slot < 0) { inMiss++; continue; }
            draft.setSlot(slot, spec);
            // setSlot は acceptsAt で弾く可能性があるので反映確認
            if (!draft.getSlot(slot).isEmpty()) { used[slot] = true; inOk++; }
            else inMiss++;
        }
        for (IngredientSpec spec : outputs) {
            SlotFamily fam = familyOf(spec);
            int slot = findSlot(draft, used, fam, true);
            if (slot < 0) { outMiss++; continue; }
            draft.setSlot(slot, spec);
            if (!draft.getSlot(slot).isEmpty()) { used[slot] = true; outOk++; }
            else outMiss++;
        }
        return new FillReport(inOk, outOk, inMiss, outMiss);
    }

    private record FillReport(int inOk, int outOk, int inMiss, int outMiss) {
        @Override public String toString() {
            return "fill[in " + inOk + (inMiss > 0 ? "/-" + inMiss : "")
                 + ", out " + outOk + (outMiss > 0 ? "/-" + outMiss : "") + "]";
        }
    }

    private enum SlotFamily { ITEM, FLUID, GAS }

    private static SlotFamily familyOf(IngredientSpec s) {
        IngredientSpec b = s.unwrap();
        if (b instanceof IngredientSpec.Fluid || b instanceof IngredientSpec.FluidTag) return SlotFamily.FLUID;
        if (b instanceof IngredientSpec.Gas) return SlotFamily.GAS;
        return SlotFamily.ITEM;
    }

    /** 指定 family・input/output に合致する次の空きスロット index。 無ければ -1。 */
    private static int findSlot(RecipeDraft draft, boolean[] used, SlotFamily fam, boolean output) {
        for (int i = 0; i < draft.slotCount(); i++) {
            if (used[i]) continue;
            if (!draft.getSlot(i).isEmpty()) continue;
            SlotKind k = draft.slotKind(i);
            boolean isOut = draft.isOutputSlot(i);
            if (isOut != output) continue;
            SlotFamily sf = switch (k) {
                case FLUID_INPUT, FLUID_OUTPUT -> SlotFamily.FLUID;
                case GAS_INPUT, GAS_OUTPUT -> SlotFamily.GAS;
                default -> SlotFamily.ITEM;
            };
            if (sf == fam) return i;
        }
        return -1;
    }

    /** fields オブジェクトを NumericField.label に case-insensitive マッチで反映。 */
    private static void applyFields(RecipeDraft draft, JsonObject req) {
        if (!req.has("fields") || !req.get("fields").isJsonObject()) return;
        JsonObject fields = req.getAsJsonObject("fields");
        var numFields = draft.numericFields();
        for (var entry : fields.entrySet()) {
            String key = entry.getKey();
            double val;
            try { val = entry.getValue().getAsDouble(); } catch (Exception e) { continue; }
            for (RecipeDraft.NumericField nf : numFields) {
                if (nf.label().equalsIgnoreCase(key)) {
                    double clamped = Math.max(nf.min(), Math.min(nf.max(), val));
                    nf.setter().accept(clamped);
                    break;
                }
            }
        }
    }

    // ─── ingredient parse ───

    private static List<IngredientSpec> parseIngredients(@Nullable JsonArray arr) {
        List<IngredientSpec> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            IngredientSpec s = parseIngredient(el.getAsJsonObject());
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }

    @Nullable
    private static IngredientSpec parseIngredient(JsonObject o) {
        try {
            if (o.has("item")) {
                ResourceLocation rl = new ResourceLocation(o.get("item").getAsString());
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item == net.minecraft.world.item.Items.AIR) return null;
                int count = o.has("count") ? o.get("count").getAsInt() : 1;
                ItemStack stack = new ItemStack(item, Math.max(1, count));
                return IngredientSpec.ofItem(stack);
            }
            if (o.has("tag")) {
                ResourceLocation rl = new ResourceLocation(o.get("tag").getAsString());
                int count = o.has("count") ? o.get("count").getAsInt() : 1;
                return IngredientSpec.withCount(IngredientSpec.ofTag(rl), Math.max(1, count));
            }
            if (o.has("fluid")) {
                ResourceLocation rl = new ResourceLocation(o.get("fluid").getAsString());
                Fluid fluid = BuiltInRegistries.FLUID.get(rl);
                if (fluid == net.minecraft.world.level.material.Fluids.EMPTY) return null;
                int amount = o.has("amount") ? o.get("amount").getAsInt() : 1000;
                return IngredientSpec.ofFluid(new FluidStack(fluid, Math.max(1, amount)));
            }
            if (o.has("fluidtag")) {
                ResourceLocation rl = new ResourceLocation(o.get("fluidtag").getAsString());
                int amount = o.has("amount") ? o.get("amount").getAsInt() : 1000;
                return IngredientSpec.ofFluidTag(rl, Math.max(1, amount));
            }
            if (o.has("gas")) {
                ResourceLocation rl = new ResourceLocation(o.get("gas").getAsString());
                int amount = o.has("amount") ? o.get("amount").getAsInt() : 1000;
                String chem = o.has("chemical") ? o.get("chemical").getAsString().toUpperCase(java.util.Locale.ROOT) : "GAS";
                return switch (chem) {
                    case "INFUSION" -> IngredientSpec.ofInfusion(rl, amount);
                    case "PIGMENT"  -> IngredientSpec.ofPigment(rl, amount);
                    case "SLURRY"   -> IngredientSpec.ofSlurry(rl, amount);
                    default          -> IngredientSpec.ofGas(rl, amount);
                };
            }
        } catch (Exception e) {
            Credit.LOGGER.debug("[C8004] EmitTest: ingredient parse skip {}: {}", o, e.toString());
        }
        return null;
    }

    /** {requests:[...]} または トップレベルが配列、 どちらも受ける。 */
    private static List<JsonObject> parseRequests(String raw) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(raw);
        JsonArray arr;
        if (root.isJsonObject() && root.getAsJsonObject().has("requests")) {
            arr = root.getAsJsonObject().getAsJsonArray("requests");
        } else if (root.isJsonArray()) {
            arr = root.getAsJsonArray();
        } else {
            return out;
        }
        for (JsonElement el : arr) {
            if (el.isJsonObject()) out.add(el.getAsJsonObject());
        }
        return out;
    }

    // ─── 出力 ───

    @Nullable
    private static Path writeOutput(List<Result> results) {
        try {
            Path dir = gameDir().resolve(OUT_DIR);
            Files.createDirectories(dir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

            // .js (= 本番同様 ServerEvents.recipes でラップ。 ただし test_out なので KubeJS は読まない)
            StringBuilder js = new StringBuilder();
            js.append("// === credit EmitTest output (NOT loaded by KubeJS — inspection only) ===\n");
            js.append("// generated: ").append(LocalDateTime.now()).append("\n");
            js.append("ServerEvents.recipes(event => {\n\n");
            for (Result r : results) {
                js.append("    // [").append(r.ok() ? "OK" : "FAIL").append("] ").append(r.id())
                  .append("  type=").append(r.type()).append("  ").append(r.detail()).append("\n");
                if (r.ok() && r.code() != null) js.append(r.code());
                js.append("\n");
            }
            js.append("});\n");
            Path jsFile = dir.resolve("emit_" + ts + ".js");
            Files.writeString(jsFile, js.toString(), StandardCharsets.UTF_8);

            // .log.txt (= 成否サマリ)
            StringBuilder log = new StringBuilder();
            log.append("=== credit EmitTest ").append(LocalDateTime.now()).append(" ===\n");
            long ok = results.stream().filter(Result::ok).count();
            log.append("total: ").append(results.size()).append(", ok: ").append(ok)
               .append(", fail: ").append(results.size() - ok).append("\n\n");
            for (Result r : results) {
                log.append(r.ok() ? "[OK]   " : "[FAIL] ")
                   .append(String.format("%-20s", r.id())).append(" ")
                   .append(String.format("%-32s", r.type())).append(" ")
                   .append(r.detail()).append("\n");
            }
            Files.writeString(dir.resolve("emit_" + ts + ".log.txt"), log.toString(), StandardCharsets.UTF_8);
            return jsFile.toAbsolutePath();
        } catch (Exception e) {
            Credit.LOGGER.warn("[C6018] EmitTest: write failed: {}", e.toString());
            return null;
        }
    }

    // ─── util ───

    private static Path gameDir() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    private static String sanitize(String s) {
        return s == null ? "x" : s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static void log(boolean chat, String msg) {
        Credit.LOGGER.info("[CraftPattern] {}", msg);
        if (chat) {
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), false);
        }
    }
}
